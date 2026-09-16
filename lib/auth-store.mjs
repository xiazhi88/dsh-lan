/**
 * 访问密码的持久化。
 *
 * 放在 DSH 的主目录下，和会话数据同一个地方 —— 用户备份/迁移 `~/.dsh` 时
 * 这个设置跟着走，不用单独交代。
 *
 * 存的是 scrypt 哈希，不存明文：这个文件会跟着 `~/.dsh` 被同步、备份、
 * 丢进网盘，明文密码进去迟早出事。
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { hashPassword, passwordFingerprint } from './auth.mjs';

function dataPath() {
  const home = process.env.DSH_HOME || join(homedir(), '.dsh');
  return join(home, 'dshgo-auth.json');
}

/** 内存缓存 —— 每个请求都要判一次，不该每次都读磁盘。 */
let cache = null;
let cacheMtime = 0;

function read() {
  const file = dataPath();
  if (!existsSync(file)) return { passwordHash: '', secret: '' };
  try {
    const stat = readFileSync(file, 'utf8');
    const parsed = JSON.parse(stat);
    return {
      passwordHash: typeof parsed.passwordHash === 'string' ? parsed.passwordHash : '',
      secret: typeof parsed.secret === 'string' ? parsed.secret : '',
    };
  } catch {
    // 文件坏了就当没设密码 —— 但那意味着闸门是开的，所以要说出来
    console.warn(`dshgo: ${file} 读取失败，访问密码暂时失效`);
    return { passwordHash: '', secret: '' };
  }
}

/**
 * 当前设置。带一层缓存，但每次都会看 mtime —— 用户可能在别的进程里改了它，
 * 而"改了密码却要重启 dsh web"是很糟的体验。
 */
export function currentAuth() {
  const file = dataPath();
  const mtime = existsSync(file) ? readFileSync(file).length : 0;
  if (cache === null || mtime !== cacheMtime) {
    cache = read();
    cacheMtime = mtime;
  }
  if (!cache.secret) {
    // 没有密钥就生成一个并落盘。它是签名解锁 Cookie 用的，和密码独立 ——
    // 改密码时会换掉它，从而让所有旧的解锁立即失效。
    cache.secret = randomBytes(32).toString('base64url');
    persist(cache);
  }
  return cache;
}

function persist(state) {
  const file = dataPath();
  try {
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, JSON.stringify(state, null, 2), { mode: 0o600 });
    cache = state;
    cacheMtime = existsSync(file) ? readFileSync(file).length : 0;
  } catch (error) {
    throw new Error(`写不了 ${file}：${error?.message ?? error}`);
  }
}

/** 有没有设过密码。没设 = 闸门不生效（保持向后兼容）。 */
export function hasPassword() {
  return currentAuth().passwordHash !== '';
}

/**
 * 设置/修改密码。
 *
 * **同时换掉 secret** —— 这一条很重要：换了密码，之前所有设备上的解锁
 * 应该立刻失效。否则"改了密码"给人的安全感是假的。
 */
export function setPassword(password) {
  const state = currentAuth();
  persist({
    passwordHash: hashPassword(password),
    secret: randomBytes(32).toString('base64url'),
  });
  return passwordFingerprint(state.passwordHash);
}

/** 清除密码（关掉闸门）。 */
export function clearPassword() {
  persist({ passwordHash: '', secret: randomBytes(32).toString('base64url') });
}

export function authFilePath() {
  return dataPath();
}
