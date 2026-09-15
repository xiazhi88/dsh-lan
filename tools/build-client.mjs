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

writeFileSync(resolve(root, 'client/client.js'), `${banner}${vendor}\n${index}`);
const kb = (read('client/client.js').length / 1024).toFixed(0);
console.log(`client/client.js 已生成（${kb} KB）`);
