// dshgo 插件入口。
//
// 职责就一件事：**把只监听 127.0.0.1 的 dsh web 暴露到局域网**，让手机客户端能连上。
//
// 它不做（也不该做）的事：不注入移动端 CSS、不做公网隧道、不管访问密码、不碰会话数据。
// 客户端该自己解决的事（布局、缩放、通知）都留在客户端。
//
// 服务端只需要给三样东西，缺一不可：
//   1. 一个绑在 0.0.0.0 上的监听（`dsh web` 官方禁了 `--host 0.0.0.0`）
//   2. 把 Host/Origin 改写成 loopback —— 否则 `/api` 的栅栏直接 401，
//      而且 DSH 的会话 cookie 是**绑定 authority** 的，必须每次一致
//   3. 首屏注入一次 launch token —— 它在每次 `dsh web` 启动时变化，
//      不带就永远拿不到会话 cookie

import Schema from '@deepseek-ai/schemastery';

// 内联自 dsh-web-mobile（MIT）—— 见同目录 LICENSE.dsh-web-mobile。
// 删除会话的路由原本要用户另外装一个包才有；内联是为了「只装一个包」。
//
// 注：同一个包里那份「大响应压缩」**没有**内联进来 —— 它 patch
// http.ServerResponse.prototype 的 writeHead/write/end（进程级），
// 在 DSH 0.1.1-rc.2 上会把会话列表这类大 JSON 弄坏，导致 Web 端整个
// 加载不出会话。收益（手机上快一点）远不值这个风险。
import { deleteSession } from './vendor/delete-session.js';
import { createGate, UNLOCK_PATH } from './gate.mjs';
import { authFilePath, clearPassword, currentAuth, hasPassword, setPassword } from './auth-store.mjs';

import {
  createLanProxy,
  buildInfoPayload,
  lanAddresses,
  injectPolyfill,
  POLYFILL,
  INJECT_MARK,
  INFO_PATH,
} from './proxy.mjs';

export const name = 'dshgo';

/** 需要宿主提供的服务：连接层（拿 launch token）与 web server（拿端口）。 */
export const inject = ['connection', 'webServer'];

/** 默认端口。和 dsh-pocket 保持一致，客户端不用改默认地址。 */
const DEFAULT_PORT = 3081;

/** 端口被占时往后试几个。默认端口很可能被别的工具占着，不该因此整个失效。 */
const PORT_ATTEMPTS = 10;

/**
 * 插件配置 schema。
 *
 * **必须导出**：cordis 的 `resolveConfig()` 是 `if (!runtime.Config) return config` ——
 * 不导出 `Config` 就完全不校验用户写的配置，也没有默认值。导出后：
 *   · 用户填错类型会在启动时明确报错，而不是到运行时才出怪问题
 *   · `--dump-config` 能看到每个字段的说明
 */
export const Config = Schema.object({
  port: Schema.natural()
    .default(DEFAULT_PORT)
    .description('对外监听端口。被占用时插件只警告，不影响 dsh web 本身'),
  bind: Schema.string()
    .default('0.0.0.0')
    .description('对外绑定地址。只想本机访问就填 127.0.0.1'),
});

/**
 * 从连接层实时取 launch token。
 *
 * 不能缓存：token 每次 `dsh web` 启动都会变，缓存了重启后就 401。
 * 老版本 dsh 没有 `authenticatedUrl`，那就返回空串 —— 代理退化成纯转发，
 * 行为等价于「客户端自己带 token 访问」。
 */
function makeLaunchTokenReader(ctx, dshPort, warn) {
  return () => {
    try {
      const fn = ctx.connection?.authenticatedUrl;
      if (typeof fn !== 'function') return '';
      const url = new URL(fn.call(ctx.connection, `http://127.0.0.1:${dshPort}`));
      return url.searchParams.get('token') ?? '';
    } catch (err) {
      warn(`dshgo: 取 launch token 失败 —— ${err?.message ?? err}`);
      return '';
    }
  };
}

/**
 * @param {import('@deepseek-ai/cordis').Context} ctx 宿主上下文。
 * @param {{port?: number, bind?: string}} [config] 插件配置（可选）。
 */
export function apply(ctx, config = {}) {
  /**
   * 用 `console` 而不是 `ctx.logger` 打用户必须看到的信息。
   *
   * 依据：官方 `dsh web` 自己打印启动地址用的就是 `console.log`
   * （见 `dsh-web-app` 的 `announceReady`），而标准 profile 里并没有挂 logger 的
   * console 导出器 —— 走 `ctx.logger.info` 会写进一个没人看的 sink，
   * 用户在终端里什么都看不到。插件没起来时更该让人看见原因。
   */
  const warn = (msg) => {
    try {
      console.warn(msg);
    } catch {
      /* 日志失败不能影响代理 */
    }
  };

  const dshPort = ctx.webServer?.port;
  if (!dshPort) {
    warn('dshgo: 拿不到 web server 端口，未启动（这是 headless 场景？）');
    return;
  }

  const startPort = Number(config.port) || DEFAULT_PORT;
  const bind = config.bind ?? '0.0.0.0';

  /**
   * 删除会话的路由（内联自 dsh-web-mobile，是社区分支 wzxmt-zhc 的移植）。
   *
   * 路径与上游保持一致，这样内联进来的客户端不用改。persistence / sessions /
   * agents / workspaceRegistry 都**按请求现取** —— 宿主形态不同时降级成 503，
   * 而不是启动就崩。
   */
  ctx.inject(['webServer'], (webCtx) => {
    webCtx.effect(() => webCtx.webServer.register({
      kind: 'exact',
      path: '/api/mobile-nav.session.delete',
      handler: async (req, res) => {
        const send = (status, body) => {
          const payload = JSON.stringify(body);
          res.writeHead(status, {
            'Content-Type': 'application/json; charset=utf-8',
            'Content-Length': Buffer.byteLength(payload),
          });
          res.end(payload);
        };
        if (req.method !== 'POST') {
          send(405, { error: { code: 'method-not-allowed', message: 'POST required' } });
          return;
        }
        let parsed;
        try {
          const chunks = [];
          for await (const c of req) chunks.push(c);
          parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        } catch {
          send(400, { error: { code: 'invalid-body', message: 'expected { "sessionId": string }' } });
          return;
        }
        const { sessionId } = parsed ?? {};
        if (typeof sessionId !== 'string' || sessionId === '') {
          send(400, { error: { code: 'invalid-session-id', message: 'sessionId must be a non-empty string' } });
          return;
        }
        const persistence = ctx.get('sessionPersistence');
        if (persistence === undefined) {
          send(503, { error: { code: 'persistence-unavailable', message: 'session persistence is not configured' } });
          return;
        }
        const result = await deleteSession({
          persistence,
          sessions: ctx.get('sessions'),
          agents: ctx.get('agents'),
          workspaceRegistry: ctx.get('workspaceRegistry'),
        }, sessionId);
        if (result.ok) {
          send(200, { ok: true, deleted: result.deleted });
          return;
        }
        warn(`dshgo: 删除会话 '${sessionId}' 失败（${result.error.code}）：${result.error.message}`);
        send(result.status, { error: result.error });
      },
    }), 'dshgo: 删除会话路由');
  });

  /** 已真正监听的那个代理（端口被占时可能不是 startPort）。 */
  let live = null;

  /**
   * 自描述端点注册在 **DSH 的 web server** 上 —— 不是只由代理自己答。
   *
   * 这一点很关键，早期版本踩过：前端取的是**相对路径** `/__dshgo__/info`，
   * 而用户在本机是直接看 `http://127.0.0.1:3080` 的页面。端点只存在于 3081 的
   * 代理上，于是本机永远是 404 —— 页签写「服务端半没有运行」，而终端里插件明明
   * 跑得好好的，连重启都救不了。
   *
   * 注册到 web server 后两条路都通：
   *   本机  http://127.0.0.1:3080/__dshgo__/info  → 直接命中
   *   手机  http://192.168.x.x:3081/…                → 代理转发到 3080，同一个处理器
   *
   * 内容只是本机网卡地址，不含凭据，所以不额外加鉴权 —— 够得着这台机器的人
   * 本来就能列出这些地址。
   */
  /**
   * 访问密码的设置接口。
   *
   * ★ **只接受来自本机（127.0.0.1）的请求。**
   *
   * 这一条不是洁癖，是必须的：这个端点如果从转发端口也能调，那么同网络里
   * 任何人都能**抢先设一个密码**，把真正的机主锁在门外 —— 一个"防外人"的
   * 功能变成了"让外人进来锁门"的工具。
   *
   * 所以改密码只能在跑着 dsh web 的那台机器上做（浏览器打开 127.0.0.1:3080
   * 的设置页）。App 侧不提供改密码入口，它只负责输密码。
   */
  ctx.effect(() => ctx.webServer.register({
    kind: 'exact',
    path: '/__dshgo__/auth',
    handler: async (req, res) => {
      const send = (status, body) => {
        const text = JSON.stringify(body);
        res.writeHead(status, {
          'content-type': 'application/json; charset=utf-8',
          'content-length': Buffer.byteLength(text),
          'cache-control': 'no-store',
        });
        res.end(text);
      };

      // ★ 「仅本机」必须看**代理标记**，不能只看 remoteAddress。
      //
      // 代理转发给上游时是从 127.0.0.1 发起的，所以从局域网进来的请求
      // 在 remoteAddress 上看起来也是"本机" —— 只判地址的话，同网络里任何人
      // 都能改访问密码，把真正的机主锁在门外。这个标记由代理**覆盖式**写入，
      // 客户端伪造不了。
      const proxied = req.headers['x-dshgo-proxied'] !== undefined;
      const remote = String(req.socket?.remoteAddress ?? '');
      const isLoopback = !proxied
        && (remote === '127.0.0.1' || remote === '::1' || remote === '::ffff:127.0.0.1');
      if (!isLoopback) {
        send(403, {
          error: {
            code: 'loopback-only',
            message: '改密码只能在跑 dsh web 的那台机器上做（打开 127.0.0.1 的设置页）。',
          },
        });
        return;
      }

      if (req.method === 'GET') {
        send(200, { hasPassword: hasPassword(), file: authFilePath() });
        return;
      }

      if (req.method === 'POST') {
        let raw = '';
        await new Promise((resolve) => {
          req.on('data', (c) => { raw += c; if (raw.length > 4096) req.destroy(); });
          req.on('end', resolve);
          req.on('error', resolve);
        });
        let body = {};
        try {
          body = JSON.parse(raw || '{}');
        } catch {
          send(400, { error: { code: 'invalid-body' } });
          return;
        }

        const password = String(body.password ?? '');
        if (password === '') {
          clearPassword();
          warn('dshgo: 访问密码已清除，转发端口不再需要验证');
          send(200, { ok: true, hasPassword: false });
          return;
        }
        // 太短的密码在这个场景下等于没有 —— 它挡的是同网络的人
        if (password.length < 6) {
          send(400, { error: { code: 'too-short', message: '密码至少 6 位' } });
          return;
        }
        setPassword(password);
        warn('dshgo: 访问密码已更新 —— 之前解锁过的设备需要重新验证');
        send(200, { ok: true, hasPassword: true });
        return;
      }

      send(405, { error: { code: 'method-not-allowed' } });
    },
  }), 'dshgo: 访问密码设置');

  /**
   * 宿主有没有设密码。
   *
   * **故意不限制来源**：App 需要知道"要不要弹解锁界面"，而它连的是转发端口，
   * 走不到那个只许本机的设置端点。暴露的信息只有"设没设密码"这一个布尔值 ——
   * 攻击者本来一试就知道（请求会被挡下来），所以算不上泄漏。
   */
  ctx.effect(() => ctx.webServer.register({
    kind: 'exact',
    path: '/__dshgo__/auth/status',
    handler: (_req, res) => {
      const text = JSON.stringify({ hasPassword: hasPassword() });
      res.writeHead(200, {
        'content-type': 'application/json; charset=utf-8',
        'content-length': Buffer.byteLength(text),
        'cache-control': 'no-store',
      });
      res.end(text);
    },
  }), 'dshgo: 密码状态');

  ctx.effect(() => ctx.webServer.register({
    kind: 'exact',
    path: INFO_PATH,
    handler: async (_req, res) => {
      const payload = JSON.stringify(await buildInfoPayload({
        port: live === null ? null : live.port,
        upstreamPort: dshPort,
      }));
      res.writeHead(200, {
        'content-type': 'application/json; charset=utf-8',
        'cache-control': 'no-store',
        'content-length': Buffer.byteLength(payload),
      });
      res.end(payload);
    },
  }), 'dshgo: 自描述端点');

  /**
   * 从 `startPort` 起逐个试到 `PORT_ATTEMPTS`，占用就换下一个。
   *
   * 为什么必须这样：默认端口 3081 很可能已经被 dsh-pocket 之类的工具占着。
   * 早期版本遇到占用只打一条警告就放弃 —— 于是插件在用户看来「装了但没反应」，
   * 设置页只剩一个 HTTP 404 和一个「未在监听」，而终端里那行警告早就滚过去了。
   * 一个转发插件因为默认端口被占就整个失效，是不该的。
   */
  /**
   * 访问闸门。密码没设时它内部直接放行 —— 升级插件不会把人关在门外。
   */
  const gate = createGate(currentAuth);

  async function listenFrom(startAt) {
    let lastErr = null;
    for (let i = 0; i < PORT_ATTEMPTS; i += 1) {
      const port = startAt + i;
      const proxy = createLanProxy({
        port,
        bind,
        upstream: { host: '127.0.0.1', port: dshPort },
        launchToken: makeLaunchTokenReader(ctx, dshPort, warn),
        gate,
        warn,
      });
      try {
        // eslint-disable-next-line no-await-in-loop -- 串行试探，占用就换下一个
        await proxy.listen();
        return proxy;
      } catch (err) {
        lastErr = err;
        // 只有「端口被占」才值得换端口；其它错误（权限等）直接报出来
        if (err?.code !== 'EADDRINUSE') throw err;
      }
    }
    throw lastErr ?? new Error('no free port');
  }

  listenFrom(startPort).then(
    (proxy) => {
      live = proxy;
      const { port } = proxy;
      if (port !== startPort) {
        console.log(`dshgo: 端口 ${startPort} 被占用，改用 ${port}`);
      }
      const urls = lanAddresses().map((it) => `http://${it.address}:${port}`);
      // 用户就是靠这行知道该往手机里填什么地址，所以打清楚
      const line = urls.length
        ? `dshgo: 局域网地址 ${urls.join('  ')}  （上游 127.0.0.1:${dshPort}）`
        : `dshgo: 已监听 :${port}，但没找到非回环网卡 —— 检查网络连接`;
      console.log(line);
      console.log(`dshgo: 自描述端点 ${INFO_PATH}（客户端可据此自动发现地址）`);
      console.log(
        hasPassword()
          ? 'dshgo: 访问密码已启用 —— 浏览器打开会先要密码，App 走生物识别'
          : 'dshgo: 尚未设置访问密码，转发端口对所有同网络设备开放（设置里可开启）',
      );
      console.log(`dshgo: 解锁端点 ${UNLOCK_PATH}（浏览器发密码 / App 发 JSON）`);
    },
    (err) => {
      warn(`dshgo: 监听失败（已试过 ${startPort}–${startPort + PORT_ATTEMPTS - 1}）—— ${err?.message ?? err}`);
    },
  );

  // 插件卸载 / 热重载时收干净，别把端口占着
  ctx.effect(() => async () => {
    if (live !== null) await live.close();
  }, 'dshgo: 关闭局域网代理');
}

export { createLanProxy, buildInfoPayload, lanAddresses, injectPolyfill, POLYFILL, INJECT_MARK, INFO_PATH };
