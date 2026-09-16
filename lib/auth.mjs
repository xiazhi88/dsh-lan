/**
 * 转发端口的访问闸门。
 *
 * ## 为什么必须有这一层
 *
 * 代理为了让浏览器直接可用，会**注入 DSH 的启动 token** —— 好处是
 * `http://局域网IP:3081` 打开就能用，代价是**同网络里任何人打开都能用**。
 * 这正是「把 DSH 暴露到局域网上」最容易被忽略的那个洞。
 *
 * ## 安全边界放在这里，不放在 App 里
 *
 * 纯应用级的锁（App 启动时弹个指纹）挡不住浏览器 —— 随便一台设备打开 3081
 * 照样进得去。所以闸门必须在**转发层**：所有请求先过这里。
 *
 * ## App 和 Web 的区别**只是界面**，不是权限
 *
 * 两条路要的是**同一个密码**：
 *
 * - `User-Agent` 里带 `DSHGo` → 认为是 App，返回 JSON，由 App 本地做生物识别
 * - 否则 → 认为是浏览器，返回密码页
 *
 * 所以伪造 User-Agent 没有任何收益 —— 它只决定"用什么界面输密码"，
 * 不决定"要不要输密码"。这一点是整个设计的关键，写在这里以免以后有人
 * 误以为 UA 是安全判断。
 *
 * ## 为什么用 scrypt 而不是 sha256
 *
 * 密码是用户自己想的一句话，熵很低。sha256 加盐对离线爆破毫无抵抗力，
 * scrypt 有工作因子，能让爆破变贵。Node 内置 crypto 就有，不用引依赖。
 */
import { createHash, createHmac, randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';

/** 解锁 Cookie 名。和 DSH 自己的 `dsh-auth-*` 分开，互不干扰。 */
export const UNLOCK_COOKIE = 'dshgo-unlock';

/** 解锁有效期。30 天 —— 手机上天天用，太短会烦到用户去关掉这个功能。 */
const UNLOCK_TTL_MS = 30 * 24 * 60 * 60 * 1000;

const SCRYPT_KEYLEN = 32;

/**
 * 一个密码的存储形态。带上算法与参数，将来换参数时能识别旧记录。
 */
export function hashPassword(password) {
  const salt = randomBytes(16);
  const key = scryptSync(password, salt, SCRYPT_KEYLEN);
  return `scrypt$${salt.toString('base64')}$${key.toString('base64')}`;
}

/** 校验密码。**用 timingSafeEqual** —— 普通的 === 会因为提前返回而泄漏信息。 */
export function verifyPassword(password, stored) {
  if (typeof stored !== 'string' || !stored.startsWith('scrypt$')) return false;
  const [, saltB64, keyB64] = stored.split('$');
  if (!saltB64 || !keyB64) return false;
  try {
    const salt = Buffer.from(saltB64, 'base64');
    const expected = Buffer.from(keyB64, 'base64');
    const actual = scryptSync(password, salt, expected.length);
    return actual.length === expected.length && timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

/** 这个请求是不是 App（只看 User-Agent）。**只影响界面，不影响权限**。 */
export function isAppClient(req) {
  const ua = String(req.headers['user-agent'] ?? '');
  return ua.includes('DSHGo');
}

/**
 * 签发解锁令牌。
 *
 * 无状态：`过期时间.HMAC(过期时间)`。服务端不存会话表，
 * 重启也不丢；要吊销就换密钥（或者改密码时换密钥）。
 */
export function issueToken(secret, now = Date.now()) {
  const exp = String(now + UNLOCK_TTL_MS);
  const sig = createHmac('sha256', secret).update(exp).digest('base64url');
  return `${exp}.${sig}`;
}

export function verifyToken(token, secret, now = Date.now()) {
  if (typeof token !== 'string') return false;
  const dot = token.indexOf('.');
  if (dot <= 0) return false;
  const exp = token.slice(0, dot);
  const sig = token.slice(dot + 1);
  if (!/^\d+$/.test(exp)) return false;
  if (Number(exp) < now) return false;

  const want = createHmac('sha256', secret).update(exp).digest('base64url');
  const a = Buffer.from(sig);
  const b = Buffer.from(want);
  return a.length === b.length && timingSafeEqual(a, b);
}

/** 从 Cookie 头里取一个值。不引 cookie 解析库 —— 这里只需要一个名字。 */
export function readCookie(header, name) {
  if (!header) return undefined;
  for (const part of String(header).split(';')) {
    const eq = part.indexOf('=');
    if (eq < 0) continue;
    if (part.slice(0, eq).trim() === name) return part.slice(eq + 1).trim();
  }
  return undefined;
}

/**
 * 浏览器要看的密码页。
 *
 * 刻意做得极简且自包含：不引外部资源（那会在断网/内网环境里转圈），
 * 不依赖 DSH 的前端（那时还没登录）。
 */
export function passwordPage({ error = '', title = 'DSH Go' } = {}) {
  return `<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>${title}</title>
<style>
  :root { color-scheme: light dark; --brand:#4D6BFE; }
  * { box-sizing: border-box; }
  body {
    margin:0; min-height:100dvh; display:flex; align-items:center; justify-content:center;
    font: -apple-system, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
    background:#F4F6F9; color:#10141C; padding:24px;
  }
  @media (prefers-color-scheme: dark) { body { background:#0B0E14; color:#E9EDF6; } }
  .card {
    width:100%; max-width:380px; background:#fff; border:1px solid #E6E9EF;
    border-radius:22px; padding:28px 24px;
  }
  @media (prefers-color-scheme: dark) { .card { background:#141922; border-color:#262D3A; } }
  h1 { font-size:20px; margin:0 0 6px; letter-spacing:-.02em; }
  p  { font-size:13px; line-height:1.6; margin:0 0 20px; opacity:.65; }
  input {
    width:100%; height:48px; padding:0 14px; font-size:16px;
    border:1px solid #E6E9EF; border-radius:14px; background:transparent; color:inherit;
  }
  @media (prefers-color-scheme: dark) { input { border-color:#262D3A; } }
  input:focus { outline:2px solid var(--brand); outline-offset:-1px; border-color:transparent; }
  button {
    width:100%; height:48px; margin-top:14px; border:0; border-radius:14px;
    background:var(--brand); color:#fff; font-size:15px; font-weight:600; cursor:pointer;
  }
  .err { color:#EF4444; font-size:13px; margin-top:12px; }
</style></head>
<body>
  <form class="card" method="POST" action="/__dshgo__/unlock">
    <h1>需要密码</h1>
    <p>这个地址暴露在网络上，需要先验证。记得密码的设备 30 天内不用再输。</p>
    <input name="password" type="password" autocomplete="current-password"
           placeholder="访问密码" autofocus required>
    <button type="submit">解锁</button>
    ${error ? `<div class="err">${error}</div>` : ''}
  </form>
</body></html>`;
}

/** 短小的 JSON 响应助手。 */
export function sendJson(res, status, body, extraHeaders = {}) {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(text),
    'cache-control': 'no-store',
    ...extraHeaders,
  });
  res.end(text);
}

/** 给密码做一次不可逆的"指纹"，只用于日志里确认"改没改"，不用于校验。 */
export function passwordFingerprint(stored) {
  if (!stored) return '';
  return createHash('sha256').update(stored).digest('hex').slice(0, 8);
}
