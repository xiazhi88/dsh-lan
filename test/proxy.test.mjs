// dshgo 的代理行为测试。用一个「假 dsh web」把关键契约钉死：
//   · Host/Origin 必须被改写成 loopback（否则真实 DSH 的栅栏 401）
//   · 首屏必须补一次 ?token=（否则永远拿不到会话 cookie）
//   · 已有 cookie 时不能再补（否则每次导航都多一跳 303）
//   · HTML 注入 polyfill 且只注入一次；非 HTML 原样透传
//   · WebSocket upgrade 必须穿透（事件流靠它）
//
// 跑法：node --test test/

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer, request as httpRequest } from 'node:http';
import { once } from 'node:events';

import {
  createLanProxy,
  buildInfoPayload,
  injectPolyfill,
  isTailscaleAddress,
  INJECT_MARK,
  INFO_PATH,
} from '../lib/proxy.mjs';

const TOKEN = 'tok-abc123';
const COOKIE = 'dsh-auth-testcookie';

/** 假 dsh web：复刻 browser-auth 的关键行为，并把收到的头记下来供断言。 */
function fakeDsh() {
  const seen = [];
  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://x');
    seen.push({
      url: req.url,
      host: req.headers.host,
      origin: req.headers.origin,
    });

    // 模拟 isTrustedApiRequest 的栅栏
    const host = String(req.headers.host ?? '');
    if (!/^127\.0\.0\.1(:\d+)?$/.test(host.split(':')[0] === '127.0.0.1' ? host : '')) {
      res.writeHead(401, { 'content-type': 'text/plain' });
      res.end('unauthorized');
      return;
    }

    if (url.pathname === '/api/thing') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: true, host: req.headers.host }));
      return;
    }

    const cookie = String(req.headers.cookie ?? '');
    if (url.searchParams.get('token') === TOKEN) {
      // token 换 cookie，然后按真实实现 303 到相对路径 /
      res.writeHead(303, {
        location: '/',
        'set-cookie': `${COOKIE}=v1.signed; Max-Age=2592000; Path=/; HttpOnly`,
      });
      res.end();
      return;
    }
    if (cookie.includes(COOKIE)) {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      res.end('<html><head><meta name="viewport" content="width=device-width"></head><body>DSH</body></html>');
      return;
    }
    res.writeHead(401, { 'content-type': 'text/plain' });
    res.end('unauthorized');
  });
  server.on('upgrade', (req, socket) => {
    seen.push({ upgrade: req.url, host: req.headers.host, origin: req.headers.origin });
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n');
    socket.write('hello-from-upstream');
    socket.end();
  });
  return { server, seen };
}

async function startFakeDsh() {
  const { server, seen } = fakeDsh();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return { server, seen, port: server.address().port };
}

function fetchThrough(port, path, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = httpRequest(
      { host: '127.0.0.1', port, path, method: 'GET', headers },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => resolve({
          status: res.statusCode,
          headers: res.headers,
          body: Buffer.concat(chunks).toString('utf8'),
        }));
      },
    );
    req.on('error', reject);
    req.end();
  });
}

test('代理把 Host/Origin 改写成 loopback，栅栏才放行', async (t) => {
  const up = await startFakeDsh();
  const proxy = createLanProxy({
    port: 0,
    bind: '127.0.0.1',
    upstream: { host: '127.0.0.1', port: up.port },
    launchToken: () => TOKEN,
  });
  const addr = await proxy.listen();
  t.after(async () => {
    await proxy.close();
    // keep-alive / WS 会把 close 卡住，必须强制断开
    up.server.closeAllConnections?.();
    up.server.close();
  });

  // 客户端用「局域网 Host」访问，外加一个跨源 Origin
  const res = await fetchThrough(addr.port, '/api/thing', {
    host: '192.168.1.50:3081',
    origin: 'http://192.168.1.50:3081',
    accept: 'application/json',
  });

  assert.equal(res.status, 200, '改写后栅栏应放行（否则 401）');
  const last = up.seen.at(-1);
  assert.equal(last.host, `127.0.0.1:${up.port}`, 'Host 必须被改写成 loopback');
  assert.equal(last.origin, `http://127.0.0.1:${up.port}`, 'Origin 必须跟着改，保持同源');
});

test('首屏补 ?token=，拿到 cookie 后不再补', async (t) => {
  const up = await startFakeDsh();
  const proxy = createLanProxy({
    port: 0,
    bind: '127.0.0.1',
    upstream: { host: '127.0.0.1', port: up.port },
    launchToken: () => TOKEN,
  });
  const addr = await proxy.listen();
  t.after(async () => {
    await proxy.close();
    // keep-alive / WS 会把 close 卡住，必须强制断开
    up.server.closeAllConnections?.();
    up.server.close();
  });

  // ① 无 cookie 的首屏导航 → 应被补 token → 上游 303 换 cookie
  const first = await fetchThrough(addr.port, '/', {
    host: '192.168.1.50:3081',
    accept: 'text/html,application/xhtml+xml',
  });
  assert.equal(first.status, 303, '首屏应拿到换 cookie 的 303');
  assert.ok(up.seen.at(-1).url.includes(`token=${TOKEN}`), '上游应收到注入的 token');
  assert.ok(String(first.headers['set-cookie'] ?? '').includes(COOKIE), '应下发会话 cookie');

  // ② 带上 cookie 再访问 → 不能再补 token（否则每次导航多一跳）
  const second = await fetchThrough(addr.port, '/', {
    host: '192.168.1.50:3081',
    cookie: COOKIE,
    accept: 'text/html,application/xhtml+xml',
  });
  assert.equal(second.status, 200);
  assert.ok(!up.seen.at(-1).url.includes('token='), '已有 cookie 时不该再注入 token');
});

test('HTML 注入 polyfill 且只注入一次，非 HTML 不碰', async (t) => {
  const up = await startFakeDsh();
  const proxy = createLanProxy({
    port: 0,
    bind: '127.0.0.1',
    upstream: { host: '127.0.0.1', port: up.port },
    launchToken: () => '',
  });
  const addr = await proxy.listen();
  t.after(async () => {
    await proxy.close();
    // keep-alive / WS 会把 close 卡住，必须强制断开
    up.server.closeAllConnections?.();
    up.server.close();
  });

  const html = await fetchThrough(addr.port, '/', {
    host: '192.168.1.50:3081',
    cookie: COOKIE,
    accept: 'text/html',
  });
  assert.ok(html.body.includes(INJECT_MARK), 'HTML 应被注入 polyfill');
  assert.ok(html.body.includes('randomUUID'), 'polyfill 应补 randomUUID');
  assert.ok(html.body.indexOf(INJECT_MARK) < html.body.indexOf('<body'), 'polyfill 必须在页面内容之前');
  assert.equal(
    html.body.split(INJECT_MARK).length - 1,
    1,
    '同一次响应里只能出现一次（重复注入会让标记失去意义）',
  );

  // 幂等：拿注入过的 HTML 再注入一次不该叠加
  const twice = injectPolyfill(injectPolyfill('<html><head></head><body>x</body></html>'));
  assert.equal(twice.split(INJECT_MARK).length - 1, 1);

  const json = await fetchThrough(addr.port, '/api/thing', {
    host: '192.168.1.50:3081',
    accept: 'application/json',
  });
  assert.ok(!json.body.includes(INJECT_MARK), 'JSON 不能被注入');
  assert.match(json.body, /"ok":true/);
});

test('WebSocket upgrade 穿透', async (t) => {
  const up = await startFakeDsh();
  const proxy = createLanProxy({
    port: 0,
    bind: '127.0.0.1',
    upstream: { host: '127.0.0.1', port: up.port },
    launchToken: () => TOKEN,
  });
  const addr = await proxy.listen();
  t.after(async () => {
    await proxy.close();
    // keep-alive / WS 会把 close 卡住，必须强制断开
    up.server.closeAllConnections?.();
    up.server.close();
  });

  const got = await new Promise((resolve, reject) => {
    const req = httpRequest({
      host: '127.0.0.1',
      port: addr.port,
      path: '/api/remote.mux',
      headers: { host: '192.168.1.50:3081', connection: 'Upgrade', upgrade: 'websocket' },
    });
    // 注意第三个参数 head：上游把 101 和首帧写在同一个 TCP 包里时，
    // 首帧数据会落在这里而不是 socket 流里 —— 漏了它就会误判成「代理没转发」
    req.on('upgrade', (res, socket, head) => {
      if (head?.length) { resolve(head.toString('utf8')); socket.destroy(); return; }
      socket.on('data', (c) => { resolve(c.toString('utf8')); socket.destroy(); });
    });
    req.on('error', reject);
    req.end();
  });

  assert.equal(got, 'hello-from-upstream');
  assert.equal(up.seen.at(-1).host, `127.0.0.1:${up.port}`, 'upgrade 也要改写 Host');
});

test('自描述端点转发给上游，由 web server 路由来答', async (t) => {
  const up = await startFakeDsh();
  const proxy = createLanProxy({
    port: 0,
    bind: '127.0.0.1',
    upstream: { host: '127.0.0.1', port: up.port },
    launchToken: () => '',
  });
  const addr = await proxy.listen();
  t.after(async () => {
    await proxy.close();
    up.server.closeAllConnections?.();
    up.server.close();
  });

  const before = up.seen.length;
  await fetchThrough(addr.port, INFO_PATH, { host: '192.168.1.50:3081' });

  // 关键：必须打到上游。早期版本由代理自己答，于是「本机直连 3080」这条路上
  // 端点根本不存在 —— 设置页只能显示一句含糊的「未在监听」。
  assert.equal(up.seen.length, before + 1, '应转发给上游，而不是自己答');
  assert.equal(up.seen.at(-1).url, INFO_PATH, '转发的就是那个路径');
  assert.equal(up.seen.at(-1).host, `127.0.0.1:${up.port}`, 'Host 照常改写成 loopback');
});

test('Tailscale 地址识别（100.64.0.0/10）', () => {
  // 设置页靠这个判断「从外面访问」是否已就绪，边界必须准
  const yes = ['100.64.0.1', '100.101.102.103', '100.127.255.254'];
  const no = ['100.63.255.255', '100.128.0.1', '192.168.1.100', '10.0.0.5', '::1', '', null];

  for (const ip of yes) assert.equal(isTailscaleAddress(ip), true, `${ip} 应判为 Tailscale`);
  for (const ip of no) assert.equal(isTailscaleAddress(ip), false, `${ip} 不应判为 Tailscale`);
});

test('端点响应体：区分「代理在跑」和「代理没跑」', async () => {
  const nets = [
    { name: 'en0', address: '192.168.0.21' },
    { name: 'utun3', address: '100.101.102.103' },
  ];

  const up = await buildInfoPayload({ port: 3081, upstreamPort: 3080 }, nets);
  assert.equal(up.listening, true);
  assert.equal(up.port, 3081);
  assert.equal(up.upstreamPort, 3080);
  assert.deepEqual(up.addresses, [
    'http://192.168.0.21:3081',
    'http://100.101.102.103:3081',
  ]);
  assert.deepEqual(up.tailscale, ['http://100.101.102.103:3081'], 'Tailscale 单独一组');
  for (const url of up.tailscale) assert.ok(up.addresses.includes(url), '且是 addresses 的子集');

  // 代理没在跑时必须说清楚 —— 前端据此区分「没起来」和「读不到端点」
  const down = await buildInfoPayload({ port: null, upstreamPort: 3080 }, nets);
  assert.equal(down.listening, false);
  assert.equal(down.port, null);
  assert.deepEqual(down.addresses, [], '没监听就不该报地址');
  assert.deepEqual(down.tailscale, []);

  // 二维码：地址对应的 SVG，前端拿它渲染扫码。没在监听就不该有。
  assert.ok(up.qrcodes['http://192.168.0.21:3081'].startsWith('<svg'), '监听中应有二维码 SVG');
  assert.ok(up.qrcodes['http://192.168.0.21:3081'].includes('crispEdges'), '是二维码（不是普通图形）');
  assert.deepEqual(Object.keys(down.qrcodes), [], '没监听就不该生成二维码');
});
