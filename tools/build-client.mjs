// 把内联的移动端适配 + 我们自己的客户端拼成最终产物。
//
// 为什么需要这一步：DSH 的客户端模块系统**一个包只加载一个文件**
// （package.json 的 exports["./client"]），所以 vendor 的代码必须和我们的
// 拼在同一个文件里。拼接顺序有要求 —— vendor 先，它定义
// window.__dshgoVendorMobile，我们的 index.js 再调用。
//
// 用法：node tools/build-client.mjs
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = (p) => readFileSync(resolve(root, p), 'utf8');

const vendor = read('client/vendor/dsh-web-mobile.js');
const index = read('client/index.js');

const banner = `// ┌─────────────────────────────────────────────────────────────────┐
// │  本文件由 tools/build-client.mjs 生成，请勿手工编辑。              │
// │  改 client/index.js，然后跑：node tools/build-client.mjs           │
// └─────────────────────────────────────────────────────────────────┘
`;

// ★ 模块 id 必须等于包名，从 package.json 取，不能写死。
//
// 写死过一次，包名一改就整页 "Failed to load plugins"，而报错是**核心模块**
// 重复注册（`duplicate factory registration for "@deepseek-ai/dsh-api-gateway"`），
// 从错误信息完全看不出根因在这里。查证：同一份代码只改包名 → 必坏。
const pkgName = JSON.parse(read('package.json')).name;
if (typeof pkgName !== 'string' || pkgName.length === 0) {
  throw new Error('package.json 里没有 name，无法确定客户端模块 id');
}
const PLACEHOLDER = '__DSHGO_MODULE_ID__';
if (!index.includes(PLACEHOLDER)) {
  // 不静默 —— 这个项目已经因为"字符串替换没匹配上却没人知道"栽过几次
  throw new Error(`client/index.js 里没有 ${PLACEHOLDER}，构建中止`);
}
const withId = index.split(PLACEHOLDER).join(pkgName);

writeFileSync(resolve(root, 'client/client.js'), `${banner}${vendor}\n${withId}`);
const kb = (read('client/client.js').length / 1024).toFixed(0);
console.log(`client/client.js 已生成（${kb} KB），模块 id = ${pkgName}`);
