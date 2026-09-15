// 从 dsh-web-mobile 同步内联的代码。
//
// 我们内联了它的三块东西，原因是「只装一个包」：
//   client/vendor/dsh-web-mobile.js   客户端（移动端布局的主體）
//   lib/vendor/delete-session.js      删除会话的宿主路由
//   lib/vendor/compress.js            大响应压缩
//
// ── 为什么必须内联而不是依赖 ──
//
// 试过用 cordis.patch.yml 多插一行挂它。那条路会炸：市场的「一键安装」会
// 热挂载插件，读的正是那张补丁 —— 于是它被挂了两次，locale namespace 冲突，
// 整个 dsh web 起不来，连市场的卸载页都打不开。内联则挂载次数完全由我们控制。
//
// ── 用法 ──
//
//   node tools/vendor-mobile.mjs [--from <dsh-web-mobile 的安装目录>]
//
// 默认从本机 profile 的 node_modules 里找。跑完记得：
//   node tools/build-client.mjs    # 重新拼 client/client.js
//
// 同步后务必**实测**：移动端布局、删除会话、通知，以及启动日志里没有
// locale 冲突。上游的改动我们不会自动感知，这是内联的代价。
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const argFrom = process.argv.indexOf('--from');
const candidates = argFrom > -1 ? [process.argv[argFrom + 1]] : [
  join(homedir(), '.dsh/profiles/web/node_modules/dsh-web-mobile'),
  join(process.cwd(), 'node_modules/dsh-web-mobile'),
];
const src = candidates.find((d) => d && existsSync(join(d, 'package.json')));
if (!src) {
  console.error('找不到 dsh-web-mobile。先装一个，或用 --from 指路：');
  for (const c of candidates) console.error('  ' + c);
  process.exit(1);
}

const pkg = JSON.parse(readFileSync(join(src, 'package.json'), 'utf8'));
const ver = pkg.version;
console.log(`同步自 ${src}（v${ver}）`);

const marker = (title) => `// ─────────────────────────────────────────────────────────────────────
// 内联自 dsh-web-mobile v${ver}（MIT，Copyright (c) 2026 mexiaosqwq）
//   https://github.com/mexiaosqwq/dsh-web-mobile
// 完整许可：同目录 LICENSE.dsh-web-mobile
// ${title}
// ─────────────────────────────────────────────────────────────────────
`;

// ── 宿主侧两个模块：整份拷过来，加来源头 ──
mkdirSync(resolve(root, 'lib/vendor'), { recursive: true });
for (const f of ['delete-session.js', 'compress.js']) {
  writeFileSync(resolve(root, `lib/vendor/${f}`), marker('由 tools/vendor-mobile.mjs 生成') + readFileSync(join(src, 'lib', f), 'utf8'));
}
writeFileSync(resolve(root, 'lib/vendor/LICENSE.dsh-web-mobile'), readFileSync(join(src, 'LICENSE'), 'utf8'));

// ── 客户端：抽出 factory 体，包一层工厂 ──
const client = readFileSync(join(src, 'lib/client.js'), 'utf8');
const a = client.indexOf('factory: (require) => {') + 'factory: (require) => {'.length;
const b = client.lastIndexOf('return module.exports; } });');
if (a < 0 || b < 0) {
  console.error('客户端产物的结构变了，抽不出 factory 体 —— 需要人工看一眼。');
  process.exit(1);
}
const body = client.slice(a, b);
mkdirSync(resolve(root, 'client/vendor'), { recursive: true });
writeFileSync(resolve(root, 'client/vendor/dsh-web-mobile.js'), `${marker('由 tools/vendor-mobile.mjs 生成，请勿手工编辑')}
/** 包一层工厂：传入宿主的 require，返回该插件的模块导出（{ name, inject, apply }）。 */
window.__dshgoVendorMobile = function (require) {
${body}  return module.exports;
};
`);
writeFileSync(resolve(root, 'client/vendor/LICENSE.dsh-web-mobile'), readFileSync(join(src, 'LICENSE'), 'utf8'));

console.log('已更新 client/vendor/ 与 lib/vendor/');
console.log('下一步：node tools/build-client.mjs，然后实测。');
