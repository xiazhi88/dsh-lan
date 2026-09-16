/**
 * 转发端口上的访问闸门。
 *
 * ## 拦在哪一步是关键
 *
 * 这个函数由代理在**注入 DSH 启动 token 之前**调用。顺序不能反：
 * 那个 token 一注入，请求就等于已经登录了 —— 先注入再拦，闸门就是摆设。
 *
 * ## 两条路的区别只是界面
 *
 * 见 auth.mjs 顶部的说明：App 拿 JSON，浏览器拿密码页，**密码是同一个**。
 * 所以伪造 User-Agent 没有收益，这里也就敢于用 UA 做分流。
 */
import {
  UNLOCK_COOKIE,
  isAppClient,
  issueToken,
  passwordPage,
  readCookie,
  sendJson,
  verifyPassword,
  verifyToken,
} from './auth.mjs';

export const UNLOCK_PATH = '/__dshgo__/unlock';

/**
 * 不需要先解锁就能访问的路径。
 *
 * ★ 必须包含密码状态端点，否则会死锁：
 * App 靠它判断"要不要弹解锁界面"，而它自己又被闸门挡着 ——
 * App 拿不到成功响应，只好当作"没设密码"直接加载页面，
 * 结果用户看到的是**网页版密码页**而不是原生的解锁界面。
 *
 * 暴露的信息只有一个布尔值（设没设密码），而且攻击者一试就知道，
 * 所以放行它不损失什么。
 */
const PUBLIC_PATHS = new Set(['/__dshgo__/auth/status']);

/** 请求体大小上限。只是收一个密码，1KB 足够。 */
const MAX_BODY = 1024;

function readBody(req) {
  return new Promise((resolve) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        resolve('');
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', () => resolve(''));
  });
}

/** 表单或 JSON 都能收 —— 浏览器发表单，App 发 JSON。 */
function extractPassword(raw, contentType) {
  const ct = String(contentType ?? '');
  if (ct.includes('application/json')) {
    try {
      return String(JSON.parse(raw)?.password ?? '');
    } catch {
      return '';
    }
  }
  // x-www-form-urlencoded
  return new URLSearchParams(raw).get('password') ?? '';
}

function wantsHtml(req) {
  return String(req.headers.accept ?? '').includes('text/html');
}

/**
 * 造一个闸门。
 *
 * @param getState 返回 `{ passwordHash, secret }`；`passwordHash` 为空表示不启用
 */
export function createGate(getState) {

  /** 返回 true = 已经响应完毕，代理应当直接返回。 */
  return async function intercept(req, res) {
    const state = getState();

    // 没设密码 → 不拦。保持向后兼容：升级插件不会突然把用户关在门外。
    if (!state.passwordHash) return false;

    // 已经解锁过（Cookie 有效）→ 放行
    if (verifyToken(readCookie(req.headers.cookie, UNLOCK_COOKIE), state.secret)) return false;

    const url = new URL(req.url ?? '/', 'http://dshgo.invalid');
    const isApp = isAppClient(req);

    // 白名单路径直接放行（见 PUBLIC_PATHS 的说明）
    if (PUBLIC_PATHS.has(url.pathname)) return false;

    // ---- 解锁接口 ----
    if (url.pathname === UNLOCK_PATH) {
      if (req.method !== 'POST') {
        if (isApp) sendJson(res, 405, { error: { code: 'method-not-allowed' } });
        else {
          const html = passwordPage();
          res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
          res.end(html);
        }
        return true;
      }

      const raw = await readBody(req);
      const password = extractPassword(raw, req.headers['content-type']);
      if (!verifyPassword(password, state.passwordHash)) {
        if (isApp) sendJson(res, 401, { error: { code: 'bad-password', message: '密码不对' } });
        else {
          const html = passwordPage({ error: '密码不对，再试一次' });
          res.writeHead(401, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
          res.end(html);
        }
        return true;
      }

      const cookie = [
        `${UNLOCK_COOKIE}=${issueToken(state.secret)}`,
        'Path=/',
        'HttpOnly',           // 页面脚本读不到，减少 XSS 的收益
        'SameSite=Lax',       // 表单跳转要带上它，所以不能是 Strict
        'Max-Age=' + String(30 * 24 * 60 * 60),
      ].join('; ');

      if (isApp) sendJson(res, 200, { ok: true }, { 'set-cookie': cookie });
      else {
        res.writeHead(303, { location: '/', 'set-cookie': cookie, 'cache-control': 'no-store' });
        res.end();
      }
      return true;
    }

    // ---- 未解锁 ----
    if (isApp) {
      // App 拿这个信号去弹生物识别
      sendJson(res, 401, { error: { code: 'locked', message: '需要解锁' }, needAuth: true });
      return true;
    }

    if (req.method === 'GET' && wantsHtml(req)) {
      const html = passwordPage();
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
      res.end(html);
      return true;
    }

    // 浏览器发起的非页面请求（JS/CSS/接口）也一并挡住，避免半加载
    sendJson(res, 401, { error: { code: 'locked' }, needAuth: true });
    return true;
  };
}
