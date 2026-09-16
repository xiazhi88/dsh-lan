import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  hashPassword, verifyPassword, isAppClient,
  issueToken, verifyToken, readCookie, passwordPage, UNLOCK_COOKIE,
} from '../lib/auth.mjs';
import { createGate, UNLOCK_PATH } from '../lib/gate.mjs';

// ---------------------------------------------------------------- 密码哈希
test('哈希后能用原密码验过，错密码验不过', () => {
  const stored = hashPassword('correct horse battery');
  assert.equal(verifyPassword('correct horse battery', stored), true);
  assert.equal(verifyPassword('correct horse batteru', stored), false);
  assert.equal(verifyPassword('', stored), false);
});

test('同一个密码两次哈希结果不同（盐是随机的）', () => {
  assert.notEqual(hashPassword('abc12345'), hashPassword('abc12345'));
});

test('存储格式坏掉时返回 false 而不是抛异常', () => {
  for (const bad of ['', 'garbage', 'scrypt$only-two', 'scrypt$$', null, undefined]) {
    assert.equal(verifyPassword('x', bad), false);
  }
});

// ---------------------------------------------------------------- 令牌
test('签发的令牌能验过', () => {
  const secret = 's3cret';
  assert.equal(verifyToken(issueToken(secret), secret), true);
});

test('换密钥后旧令牌立刻失效', () => {
  const token = issueToken('old-secret');
  assert.equal(verifyToken(token, 'new-secret'), false);
});

test('篡改令牌或过期都验不过', () => {
  const secret = 's3cret';
  const token = issueToken(secret);
  const [exp] = token.split('.');
  assert.equal(verifyToken(`${exp}.tampered`, secret), false);
  assert.equal(verifyToken(`${Number(exp) + 1}.${token.split('.')[1]}`, secret), false);
  // 已经过期的
  assert.equal(verifyToken(issueToken(secret, Date.now() - 40 * 24 * 3600 * 1000), secret), false);
});

// ---------------------------------------------------------------- UA 分流
test('UA 里带 DSHGo 才认作 App', () => {
  assert.equal(isAppClient({ headers: { 'user-agent': 'DSHGo/4.1.0 (Android)' } }), true);
  assert.equal(isAppClient({ headers: { 'user-agent': 'Mozilla/5.0' } }), false);
  assert.equal(isAppClient({ headers: {} }), false);
});

// ---------------------------------------------------------------- 闸门
/** 造一个假的 req/res，把闸门的响应收下来。 */
function fakeReq({ url = '/', method = 'GET', headers = {}, body = '' } = {}) {
  const listeners = {};
  const req = {
    url, method, headers,
    socket: { remoteAddress: '192.168.1.50' },
    on(ev, fn) { (listeners[ev] ??= []).push(fn); return req; },
    destroy() {},
  };
  setImmediate(() => {
    if (body) listeners.data?.forEach((f) => f(Buffer.from(body)));
    listeners.end?.forEach((f) => f());
  });
  return req;
}
function fakeRes() {
  const out = { status: 0, headers: {}, body: '' };
  return {
    out,
    writeHead(status, headers) { out.status = status; Object.assign(out.headers, headers); return this; },
    end(chunk) { out.body = chunk ? String(chunk) : ''; return this; },
    write(chunk) { out.body += String(chunk); return this; },
  };
}

const LOCKED = { passwordHash: hashPassword('hunter2hunter2'), secret: 'secret-a' };

test('没设密码时闸门完全不拦', async () => {
  const gate = createGate(() => ({ passwordHash: '', secret: 'x' }));
  assert.equal(await gate(fakeReq(), fakeRes()), false);
});

test('已解锁的请求放行', async () => {
  const token = issueToken(LOCKED.secret);
  const gate = createGate(() => LOCKED);
  const req = fakeReq({ headers: { cookie: `${UNLOCK_COOKIE}=${token}` } });
  assert.equal(await gate(req, fakeRes()), false);
});

test('浏览器拿到的是密码页，不是 401', async () => {
  const gate = createGate(() => LOCKED);
  const res = fakeRes();
  const handled = await gate(fakeReq({ headers: { accept: 'text/html' } }), res);
  assert.equal(handled, true);
  assert.equal(res.out.status, 200);
  assert.match(res.out.headers['content-type'], /text\/html/);
  assert.match(res.out.body, /需要密码/);
});

test('App 拿到的是 JSON + needAuth，而不是密码页', async () => {
  const gate = createGate(() => LOCKED);
  const res = fakeRes();
  await gate(fakeReq({ headers: { 'user-agent': 'DSHGo/4.1.0' } }), res);
  assert.equal(res.out.status, 401);
  const body = JSON.parse(res.out.body);
  assert.equal(body.needAuth, true);
  // 关键：App 那条路不能把密码页塞给它
  assert.doesNotMatch(res.out.body, /<html/);
});

test('密码对：发解锁 Cookie（App 拿 JSON，浏览器拿 303）', async () => {
  const gate = createGate(() => LOCKED);

  const appRes = fakeRes();
  await gate(fakeReq({
    url: UNLOCK_PATH, method: 'POST',
    headers: { 'user-agent': 'DSHGo/4.1.0', 'content-type': 'application/json' },
    body: JSON.stringify({ password: 'hunter2hunter2' }),
  }), appRes);
  assert.equal(appRes.out.status, 200);
  assert.match(appRes.out.headers['set-cookie'], new RegExp(`^${UNLOCK_COOKIE}=`));
  assert.match(appRes.out.headers['set-cookie'], /HttpOnly/);

  const webRes = fakeRes();
  await gate(fakeReq({
    url: UNLOCK_PATH, method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: 'password=hunter2hunter2',
  }), webRes);
  assert.equal(webRes.out.status, 303);
  assert.equal(webRes.out.headers.location, '/');
});

test('密码错：不签任何 Cookie', async () => {
  const gate = createGate(() => LOCKED);
  const res = fakeRes();
  await gate(fakeReq({
    url: UNLOCK_PATH, method: 'POST',
    headers: { 'user-agent': 'DSHGo/4.1.0', 'content-type': 'application/json' },
    body: JSON.stringify({ password: 'wrong' }),
  }), res);
  assert.equal(res.out.status, 401);
  assert.equal(res.out.headers['set-cookie'], undefined);
});

test('解锁接口本身不需要先解锁，否则永远进不去', async () => {
  const gate = createGate(() => LOCKED);
  const res = fakeRes();
  await gate(fakeReq({ url: UNLOCK_PATH, headers: { accept: 'text/html' } }), res);
  assert.equal(res.out.status, 200);   // 是密码页，不是 401 死循环
});

test('密码状态端点不被闸门拦 —— 否则 App 会死锁在网页版密码页上', async () => {
  const gate = createGate(() => LOCKED);
  const res = fakeRes();
  const handled = await gate(
    fakeReq({ url: '/__dshgo__/auth/status', headers: { 'user-agent': 'DSHGo/4.2.0' } }),
    res,
  );
  // 返回 false = 放行，交给上游处理
  assert.equal(handled, false);
});

test('readCookie 能从一堆 Cookie 里挑出目标', () => {
  const header = 'a=1; dsh-auth-xyz=abc; dshgo-unlock=tok.en; b=2';
  assert.equal(readCookie(header, 'dshgo-unlock'), 'tok.en');
  assert.equal(readCookie(header, 'nope'), undefined);
  assert.equal(readCookie(undefined, 'x'), undefined);
});

test('密码页不含外部资源（内网环境里不该转圈）', () => {
  const html = passwordPage();
  assert.doesNotMatch(html, /https?:\/\//);
  assert.doesNotMatch(html, /<script/);
});
