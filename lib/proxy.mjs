// dshgo 的反向代理。
//
// 为什么需要它：`dsh web` 只监听 127.0.0.1，而且 `/api` 有一道 Host/Origin 栅栏
// （只认 loopback 或 --trusted-host 白名单）。手机连不上 3080，所以需要一个东西
// 绑在 0.0.0.0 上、把入站请求的 Host/Origin 改写成 loopback 再转发 —— 栅栏永远
// 看到 loopback，于是局域网就能访问了。
//
// 三件必须做对的事：
//
// 1. **Host 必须一致地改写**。DSH 的会话 cookie 是**绑定 authority 的**
//    （cookie 名 = `dsh-auth-<authority 的 hash>`，校验时比对 payload.authority）。
//    每次请求都改写成同一个 `127.0.0.1:<dshPort>`，浏览器持有和服务器校验的
//    才是同一个 authority。
//
// 2. **首屏要带一次 `?token=`**。`dsh web` 的 launch token 每次进程启动都变，
//    拿到才会下发会话 cookie；不带就是 401。token 换 cookie 后上游回 303，
//    `location` 是**相对路径** `/`，所以不用改写跳转目标。
//
// 3. **两个 polyfill 得补**。`crypto.randomUUID` 在 `http://<IP>`（非安全上下文）
//    里原生不可用，而 DSH 连接层拿它生成 RPC id；`AbortSignal.any` 在旧 WebView
//    上缺失会让发送消息直接失败。必须在页面自己的脚本之前注入。

import { createServer, request as httpRequest } from 'node:http';
import { networkInterfaces } from 'node:os';
import { gunzipSync, inflateSync, brotliDecompressSync } from 'node:zlib';

import { qrMap } from './qr.mjs';
import { currentVersion, isNewer, latestVersion } from './version.mjs';

/** DSH 会话 cookie 的前缀（`dsh-client-connection` 的 browser-auth）。 */
const COOKIE_PREFIX = 'dsh-auth-';

/** 注入标记：既用于防重复注入，也用于测试断言。 */
export const INJECT_MARK = 'data-dshgo-polyfill';

/** 免鉴权的自描述端点，方便客户端做自动发现。 */
export const INFO_PATH = '/__dshgo__/info';

/**
 * 页面级 polyfill。必须在 DSH 自己的脚本之前跑。
 * 只在缺失时定义，不覆盖浏览器原生实现。
 */
export const POLYFILL = `<script ${INJECT_MARK}="1">(function(){
try{
  if(self.crypto&&!self.crypto.randomUUID){
    self.crypto.randomUUID=function(){
      var b=new Uint8Array(16);self.crypto.getRandomValues(b);
      b[6]=b[6]&15|64;b[8]=b[8]&63|128;
      var h='';for(var i=0;i<16;i++){var x=b[i].toString(16);h+=x.length<2?'0'+x:x;
        if(i===3||i===5||i===7||i===9)h+='-';}
      return h;
    };
  }
}catch(e){}
try{
  if(self.AbortSignal&&!self.AbortSignal.any){
    self.AbortSignal.any=function(signals){
      var ctrl=new AbortController(),list=Array.prototype.slice.call(signals||[]),done=false;
      function stop(s){if(done)return;done=true;try{ctrl.abort(s&&s.reason)}catch(e){ctrl.abort()}}
      for(var i=0;i<list.length;i++){
        if(list[i].aborted){stop(list[i]);break}
        list[i].addEventListener('abort',(function(s){return function(){stop(s)}})(list[i]),{once:true});
      }
      return ctrl.signal;
    };
  }
}catch(e){}
})();</script>`;

/**
 * 自描述端点的响应体。
 *
 * 抽成独立函数是为了能单测：它决定了设置页显示什么，而其中「代理到底有没有在监听」
 * 是前端区分「没在跑」和「读不到端点」的唯一依据 —— 早期版本没有这个字段，
 * 于是本机直连 3080 时（端点只存在于 3081）页签只能说一句含糊的「未在监听」。
 *
 * @param {{port: number|null, upstreamPort: number}} state 代理的当前状态。
 * @param {Array<{name: string, address: string}>} [addresses] 网卡列表（测试注入用）。
 */
export async function buildInfoPayload({ port, upstreamPort }, addresses = lanAddresses()) {
  const listening = typeof port === 'number' && Number.isFinite(port);
  const all = listening ? addresses : [];
  const urlOf = (it) => `http://${it.address}:${port}`;
  const urls = all.map(urlOf);
  // 版本信息：设置页据此提示插件更新。
  // **只提示，不自动装** —— 在宿主进程里改自己的依赖，改错了 dsh web 起不来，
  // 而恢复要靠手改 profile 文件。这件事该由人来按下回车。
  //
  // 查不到时 latest 为空，页面就不显示这一块 —— 宁可不说，也不误报。
  const current = currentVersion();
  const latest = await latestVersion().catch(() => '');

  return {
    name: 'dshgo',
    listening,
    port: listening ? port : null,
    upstreamPort,
    version: current,
    latestVersion: latest,
    updateAvailable: Boolean(latest) && Boolean(current) && isNewer(latest, current),
    addresses: urls,
    // 单独一组：设置页据此判断「从外面访问」是否已经就绪
    tailscale: all.filter((it) => isTailscaleAddress(it.address)).map(urlOf),
    // 地址 → SVG 二维码。手机扫码即可打开，不用手敲 IP。
    // 生成失败就是空对象，前端降级为只显示文字。
    qrcodes: await qrMap(urls),
  };
}

/** 只对 HTML 文档注入，别把二进制响应体读进内存。 */
function isHtml(contentType) {
  return String(contentType ?? '').toLowerCase().includes('text/html');
}

function acceptsHtml(accept) {
  return typeof accept === 'string' && accept.includes('text/html');
}

function decodeBody(buf, encoding) {
  switch (String(encoding ?? '').toLowerCase()) {
    case 'gzip':
    case 'x-gzip':
      return gunzipSync(buf);
    case 'deflate':
      return inflateSync(buf);
    case 'br':
      return brotliDecompressSync(buf);
    default:
      return buf;
  }
}

/** 插到 `<head>` 之后（页面自己的脚本之前）；没有 head 就放最前面。 */
export function injectPolyfill(html) {
  if (html.includes(INJECT_MARK)) return html;
  const m = /<head[^>]*>/i.exec(html);
  if (m) {
    const at = m.index + m[0].length;
    return html.slice(0, at) + POLYFILL + html.slice(at);
  }
  return POLYFILL + html;
}

/**
 * 判断是不是 Tailscale 分配的地址。
 *
 * Tailscale 从 **100.64.0.0/10**（CGNAT 段）里发地址，所以第二个八位组落在
 * 64–127 就是它。这个判断让插件能主动告诉用户「公网访问已经就绪」，
 * 而不是只甩一段静态说明让人自己去对。
 *
 * 注意 100.64/10 严格来说不止 Tailscale 在用（部分运营商 CGNAT 也用），
 * 但落在这一段的网卡地址在实际使用中就是 Tailscale 隧道。
 */
export function isTailscaleAddress(address) {
  const m = /^100\.(\d{1,3})\.\d{1,3}\.\d{1,3}$/.exec(String(address ?? ''));
  if (m === null) return false;
  const second = Number(m[1]);
  return second >= 64 && second <= 127;
}

/** 本机所有非回环 IPv4，用来告诉用户该填哪个地址。 */
export function lanAddresses() {
  const out = [];
  try {
    for (const [name, list] of Object.entries(networkInterfaces() ?? {})) {
      for (const it of list ?? []) {
        if (it.family === 'IPv4' && !it.internal) out.push({ name, address: it.address });
      }
    }
  } catch {
    /* 取不到就算了，不是致命问题 */
  }
  return out;
}

/**
 * 建立局域网转发代理。
 *
 * @param {object} options
 * @param {number} [options.port] 对外监听端口。
 * @param {string} [options.bind] 对外绑定地址，默认 0.0.0.0。
 * @param {{host:string,port:number}} [options.upstream] 上游（本机 dsh web）。
 * @param {() => string} [options.launchToken] 实时取 launch token。
 * @param {(msg:string)=>void} [options.warn] 日志（只报错，避免刷屏）。
 */
export function createLanProxy({
  /** 访问闸门；未设密码时它内部会直接放行。由 lib/index.js 传进来。 */
  gate = null,
  port = 3081,
  bind = '0.0.0.0',
  upstream = { host: '127.0.0.1', port: 3080 },
  launchToken = () => '',
  warn = () => {},
} = {}) {
  // 所有转发到上游的请求都用这一个 authority；cookie 绑定就靠它一致。
  const authority = `${upstream.host}:${upstream.port}`;

  /** 已详打过的请求形状 —— 客户端会不停重连，同一形状打一次就够。 */
  const loggedShapes = new Set();

  /** 把入站请求头改写成「看起来来自本机」的那一份。 */
  function loopbackHeaders(rawHeaders) {
    const headers = { ...rawHeaders };
    headers.host = authority;
    // 栅栏要求 Origin 与 Host 同源（缺 Origin 则放行）
    if (headers.origin !== undefined) headers.origin = `http://${authority}`;

    // ★ 标记「这个请求是从转发端口进来的」，用于区分「本机浏览器」和「网络来的」。
    //
    // 为什么不能只看 remoteAddress：代理转发给上游时**是从 127.0.0.1 发起的**，
    // 所以经由局域网的请求在插件看来也是"本机" —— 只判 remoteAddress 的话，
    // 同网络里任何人都能调那些"仅本机"的端点（比如改访问密码，把机主锁在门外）。
    //
    // 赋值而不是追加：**覆盖客户端自带的同名头**，否则这个标记可以伪造。
    headers['x-dshgo-proxied'] = '1';

    // 压缩交给上游决定，这里不干预
    return headers;
  }

  /** 首屏补一次 `?token=`，否则拿不到会话 cookie。 */
  function pathWithLaunchToken(url) {
    const parsed = new URL(url, `http://${authority}`);
    const token = (() => {
      try {
        return launchToken() || '';
      } catch {
        return '';
      }
    })();
    if (token) parsed.searchParams.set('token', token);
    return parsed.pathname + parsed.search;
  }

  const server = createServer(async (req, res) => {
    // 注意：/__dshgo__/info 不在这里处理，直接转发给上游 ——
    // 那个端点由 web server 上的插件路由提供，这样本机直连 3080 也能拿到，
    // 而不是只有走代理端口才拿得到（早期版本就栽在这上面）。同一个处理器，
    // 一份数据源，不用两处同步。

    // ★ 闸门必须在**注入启动 token 之前**。
    //
    // 那个 token 一注入，请求就等于已经登录了 —— 顺序反了闸门就是摆设。
    // 这一条是整个功能的要害，见 lib/gate.mjs 顶部说明。
    if (gate !== null) {
      const gated = await gate(req, res);
      if (gated) return;
    }

    const isRootNavigation = req.method === 'GET'
      && new URL(req.url ?? '/', `http://${authority}`).pathname === '/'
      && acceptsHtml(req.headers.accept);
    const hasSession = String(req.headers.cookie ?? '').includes(COOKIE_PREFIX);

    let path = req.url ?? '/';
    if (isRootNavigation && !hasSession) path = pathWithLaunchToken(path);

    const proxyReq = httpRequest(
      {
        host: upstream.host,
        port: upstream.port,
        method: req.method,
        path,
        headers: loopbackHeaders(req.headers),
        agent: false,
      },
      (upRes) => {
        const headers = { ...upRes.headers };

        if (!isHtml(headers['content-type'])) {
          // 非 HTML 原样透传（含大 JSON、SSE、资源文件）
          res.writeHead(upRes.statusCode ?? 502, headers);
          upRes.pipe(res);
          return;
        }

        const chunks = [];
        let size = 0;
        upRes.on('data', (c) => {
          size += c.length;
          // 兜底：HTML 不该这么大，超了就放弃注入直接透传，别把内存吃光
          if (size > 8 * 1024 * 1024) {
            res.writeHead(upRes.statusCode ?? 502, headers);
            res.end(Buffer.concat(chunks));
            upRes.destroy();
            return;
          }
          chunks.push(c);
        });
        upRes.on('end', () => {
          try {
            const raw = decodeBody(Buffer.concat(chunks), headers['content-encoding']);
            const body = Buffer.from(injectPolyfill(raw.toString('utf8')), 'utf8');
            // 注入后长度变了：压缩编码、分块传输、原长度三个头全部失效。
            // 漏掉 transfer-encoding 会同时带上 content-length，
            // 客户端直接报 "Content-Length can't be present with Transfer-Encoding"。
            delete headers['content-encoding'];
            delete headers['content-length'];
            delete headers['transfer-encoding'];
            headers['content-length'] = String(body.length);
            headers['cache-control'] = 'no-store';
            res.writeHead(upRes.statusCode ?? 502, headers);
            res.end(body);
          } catch (err) {
            warn(`dshgo: HTML 注入失败，按原样返回 —— ${err?.message ?? err}`);
            res.writeHead(upRes.statusCode ?? 502, headers);
            res.end(Buffer.concat(chunks));
          }
        });
      },
    );

    proxyReq.on('error', (err) => {
      warn(`dshgo: 转发失败 ${req.method} ${req.url} —— ${err?.message ?? err}`);
      if (!res.headersSent) {
        res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' });
      }
      res.end('dshgo: upstream unreachable');
    });

    req.pipe(proxyReq);
  });

  // WebSocket（`/api/remote.mux` 的事件流、流式输出）走 upgrade，必须原样穿透
  server.on('upgrade', (req, socket, head) => {
    const proxyReq = httpRequest({
      host: upstream.host,
      port: upstream.port,
      method: req.method,
      path: req.url ?? '/',
      headers: loopbackHeaders(req.headers),
      agent: false,
    });

    proxyReq.on('upgrade', (upRes, upSocket, upHead) => {
      const lines = [`HTTP/1.1 ${upRes.statusCode} ${upRes.statusMessage}`];
      const raw = upRes.rawHeaders;
      for (let i = 0; i < raw.length; i += 2) lines.push(`${raw[i]}: ${raw[i + 1]}`);
      socket.write(`${lines.join('\r\n')}\r\n\r\n`);
      if (upHead?.length) socket.write(upHead);
      // 双向对接。注意**不要**在 close 时 destroy 对端：
      // 上游写完就 end 的话，客户端这边可能还有没 flush 的写，
      // 立刻 destroy 会把数据丢掉（实测：客户端收到 101 却永远等不到内容）。
      // 正常收尾交给 pipe（默认 end: true），我们只在出错和客户端断开时动手。
      upSocket.pipe(socket);
      socket.pipe(upSocket);
      const bye = () => {
        try { socket.destroy(); } catch { /* 已关 */ }
        try { upSocket.destroy(); } catch { /* 已关 */ }
      };
      upSocket.on('error', bye);
      socket.on('error', bye);
      // 客户端先走 → 上游没必要留着
      socket.on('close', () => {
        try { upSocket.destroy(); } catch { /* 已关 */ }
      });
    });

    proxyReq.on('response', (upRes) => {
      // 上游没同意升级（401/403 最常见）：把它的响应**完整**回给客户端。
      //
      // 必须用 end/pipe，不能 write 完就 destroy —— destroy 会把还没 flush 的
      // 字节丢掉，客户端于是只看到 "unexpected end of stream"，看不到状态码，
      // 也就无从知道是被拒了还是路由不存在。
      const lines = [`HTTP/1.1 ${upRes.statusCode} ${upRes.statusMessage}`];
      const raw = upRes.rawHeaders;
      for (let i = 0; i < raw.length; i += 2) lines.push(`${raw[i]}: ${raw[i + 1]}`);
      socket.write(`${lines.join('\r\n')}\r\n\r\n`);
      upRes.pipe(socket);
      upRes.on('end', () => {
        try { socket.end(); } catch { /* 已关 */ }
      });
      upRes.on('error', () => {
        try { socket.destroy(); } catch { /* 已关 */ }
      });
    });

    proxyReq.on('error', (err) => {
      // 把请求本身的形状一起打出来。只报 "socket hang up" 是没法定位的 ——
      // 究竟是路径不对、方法不对、还是 upgrade 头没传过去，全在这些字段里。
      const h = req.headers;
      const brief = [
        req.method, req.url,
        `host=${h.host ?? '-'}`,
        `origin=${h.origin ?? '-'}`,
        `upgrade=${String(h.upgrade ?? '-')}`,
        `connection=${String(h.connection ?? '-')}`,
        `wsKey=${h['sec-websocket-key'] ? 'yes' : 'no'}`,
        `wsVer=${h['sec-websocket-version'] ?? '-'}`,
        `cookie=${h.cookie ? 'yes' : 'no'}`,
        `ext=${String(h['sec-websocket-extensions'] ?? '-')}`,
        `ua=${String(h['user-agent'] ?? '-').slice(0, 24)}`,
      ].join(' ');
      const seen = loggedShapes.has(brief);
      loggedShapes.add(brief);
      if (!seen) {
        warn(`dshgo: WebSocket 转发失败 ${req.url} —— ${err?.message ?? err}\n  [请求] ${brief}`);
      } else {
        warn(`dshgo: WebSocket 转发失败 ${req.url} —— ${err?.message ?? err}（同样的请求形状，详情见上面）`);
      }
      // 上游整个断了（没回任何东西）。至少给客户端一个 502，
      // 而不是留一个没有任何解释的断流。
      try {
        const body = 'dshgo: upstream closed the websocket upgrade';
        socket.end(
          'HTTP/1.1 502 Bad Gateway\r\n'
          + 'Connection: close\r\n'
          + 'Content-Type: text/plain; charset=utf-8\r\n'
          + `Content-Length: ${Buffer.byteLength(body)}\r\n\r\n`
          + body,
        );
      } catch { /* 已关 */ }
    });

    // 必须 end()：否则请求根本没发出去，客户端会一直挂着等 101
    if (head?.length) proxyReq.write(head);
    proxyReq.end();
  });

  server.on('clientError', (_err, socket) => {
    try { socket.destroy(); } catch { /* 已关 */ }
  });

  return {
    authority,
    port,
    /** 真正开始监听；端口被占用时抛 EADDRINUSE。 */
    listen() {
      return new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(port, bind, () => {
          server.off('error', reject);
          resolve(server.address());
        });
      });
    },
    close() {
      return new Promise((resolve) => {
        try {
          server.close(() => resolve());
        } catch {
          resolve();
        }
        // 挂着的长连接（WS）不会让 close 返回，直接断开
        try { server.closeAllConnections?.(); } catch { /* 老版本 Node */ }
      });
    },
  };
}
