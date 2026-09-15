// dsh-lan 插件入口。
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

import {
  createLanProxy,
  lanAddresses,
  injectPolyfill,
  POLYFILL,
  INJECT_MARK,
  INFO_PATH,
} from './proxy.mjs';

export const name = 'dsh-lan';

/** 需要宿主提供的服务：连接层（拿 launch token）与 web server（拿端口）。 */
export const inject = ['connection', 'webServer'];

/** 默认端口。和 dsh-pocket 保持一致，客户端不用改默认地址。 */
const DEFAULT_PORT = 3081;

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
      warn(`dsh-lan: 取 launch token 失败 —— ${err?.message ?? err}`);
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
    warn('dsh-lan: 拿不到 web server 端口，未启动（这是 headless 场景？）');
    return;
  }

  const port = Number(config.port) || DEFAULT_PORT;
  const proxy = createLanProxy({
    port,
    bind: config.bind ?? '0.0.0.0',
    upstream: { host: '127.0.0.1', port: dshPort },
    launchToken: makeLaunchTokenReader(ctx, dshPort, warn),
    warn,
  });

  let listening = false;

  proxy.listen().then(
    () => {
      listening = true;
      const urls = lanAddresses().map((it) => `http://${it.address}:${port}`);
      // 用户就是靠这行知道该往手机里填什么地址，所以打清楚
      const line = urls.length
        ? `dsh-lan: 局域网地址 ${urls.join('  ')}  （上游 127.0.0.1:${dshPort}）`
        : `dsh-lan: 已监听 :${port}，但没找到非回环网卡 —— 检查网络连接`;
      console.log(line);
      console.log(`dsh-lan: 自描述端点 ${INFO_PATH}（客户端可据此自动发现地址）`);
    },
    (err) => {
      warn(
        `dsh-lan: 端口 ${port} 监听失败 —— ${err?.message ?? err}`
        + (err?.code === 'EADDRINUSE' ? `（端口被占用：是不是 dsh-pocket 还在跑？可在配置里换端口）` : ''),
      );
    },
  );

  // 插件卸载 / 热重载时收干净，别把端口占着
  ctx.effect(() => async () => {
    if (listening) await proxy.close();
  }, 'dsh-lan: 关闭局域网代理');
}

export { createLanProxy, lanAddresses, injectPolyfill, POLYFILL, INJECT_MARK, INFO_PATH };
