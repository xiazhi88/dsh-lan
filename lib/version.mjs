/**
 * 查最新版本。
 *
 * ## 为什么服务端查、而不是让页面去查
 *
 * 页面在浏览器里，跨域取 npm registry 会被 CORS 挡下（能不能过取决于对方
 * 有没有放开，不该赌）。服务端本来就在跑 Node，取一次顺手。
 *
 * ## 为什么先 npmmirror
 *
 * 同一件事在 App 那边踩过一整轮：GitHub 国内经常不通，而 **npmmirror 是阿里
 * 托管的 npm 国内镜像，实测 0.2 秒**，又是按版本不可变、天生回答"最新是多少"。
 * GitHub 作为兜底 —— 它在海外更快，而且有时候国内也通。
 *
 * ## 为什么失败要静默
 *
 * 查不到就是"不知道"，不是"出错了"。为了一个更新提示去打扰用户，
 * 比不提示更糟。
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const PKG_NAME = '@xiazhi88/dshgo';

const NPMMIRROR = `https://registry.npmmirror.com/${PKG_NAME.replace('/', '%2F')}/latest`;
const NPMJS = `https://registry.npmjs.org/${PKG_NAME.replace('/', '%2F')}/latest`;

/** 查一次最多等这么久。它挂在 info 端点上，不能把页面拖住。 */
const TIMEOUT_MS = 4000;

/** 多久查一次。更新提示不是实时数据，一天四次足够。 */
const CACHE_MS = 6 * 60 * 60 * 1000;

/** 当前安装的版本 —— 从自己的 package.json 读，不写死。 */
export function currentVersion() {
  try {
    const here = dirname(fileURLToPath(import.meta.url));
    const pkg = JSON.parse(readFileSync(join(here, '..', 'package.json'), 'utf8'));
    return typeof pkg.version === 'string' ? pkg.version : '';
  } catch {
    return '';
  }
}

/** `2.0.10` 比 `2.0.9` 新 —— 必须逐段比数字，字符串比会得出反的结论。 */
export function isNewer(a, b) {
  const parts = (v) => String(v).split(/[.\-+]/).map((x) => parseInt(x, 10)).filter(Number.isFinite);
  const x = parts(a);
  const y = parts(b);
  if (x.length === 0 || y.length === 0) return false;
  for (let i = 0; i < Math.max(x.length, y.length); i += 1) {
    const l = x[i] ?? 0;
    const r = y[i] ?? 0;
    if (l !== r) return l > r;
  }
  return false;
}

let cache = { at: 0, latest: '' };

async function fetchVersion(url) {
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(url, { signal: ac.signal, headers: { accept: 'application/json' } });
    if (!res.ok) return '';
    const body = await res.json();
    return typeof body?.version === 'string' ? body.version : '';
  } finally {
    clearTimeout(timer);
  }
}

/**
 * 最新版本号；查不到时返回空字符串。
 *
 * 带一层缓存 —— info 端点会被反复请求（设置页每次打开都拉），
 * 不能每次都打网络。
 */
export async function latestVersion({ now = Date.now() } = {}) {
  if (cache.latest && now - cache.at < CACHE_MS) return cache.latest;

  for (const url of [NPMMIRROR, NPMJS]) {
    const v = await fetchVersion(url).catch(() => '');
    if (v) {
      cache = { at: now, latest: v };
      return v;
    }
  }
  // 两个源都失败：保留上一次的结果（如果有），并推迟重试时间
  cache = { at: now, latest: cache.latest };
  return cache.latest;
}

/** 测试用：清掉缓存。 */
export function resetVersionCache() {
  cache = { at: 0, latest: '' };
}
