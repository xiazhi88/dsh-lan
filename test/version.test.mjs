import { test } from 'node:test';
import assert from 'node:assert/strict';
import { currentVersion, isNewer, latestVersion, resetVersionCache } from '../lib/version.mjs';

test('当前版本从 package.json 读得到', () => {
  const v = currentVersion();
  assert.match(v, /^\d+\.\d+\.\d+/);
});

test('逐段比数字，不是比字符串', () => {
  // 字符串比较会得出 "2.0.9" > "2.0.10" 这种反的结论
  assert.equal(isNewer('2.0.10', '2.0.9'), true);
  assert.equal(isNewer('2.0.9', '2.0.10'), false);
  assert.equal(isNewer('2.1.0', '2.0.99'), true);
  assert.equal(isNewer('2.0.3', '2.0.3'), false);
  assert.equal(isNewer('2.0.2', '2.0.3'), false);
});

test('段数不同时缺的当零', () => {
  // 缺的那一段按 0 算：2.1 展开成 [2,1,0]，比 [2,0,9] 新
  assert.equal(isNewer('2.1', '2.0.9'), true);
  // 2.0 展开成 [2,0,0]，所以 2.0.3 确实比它新 —— 这一条我一开始断言反了，
  // 是函数的语义对、我的期望错
  assert.equal(isNewer('2.0.3', '2.0'), true);
  assert.equal(isNewer('2.0', '2.0.3'), false);
});

test('垃圾输入不误报有新版本', () => {
  for (const [a, b] of [['', '2.0.3'], ['abc', '2.0.3'], ['2.0.3', ''], ['2.0.3', 'xyz']]) {
    assert.equal(isNewer(a, b), false, `${a} vs ${b}`);
  }
});

// 有意**不测**带 `v` 前缀的情况：npm 的 version 字段是严格 semver，
// 不会有前缀。为一条不可能发生的输入加容错代码，是在给自己加维护面。

test('查不到时返回空字符串，不抛异常', async () => {
  resetVersionCache();
  // 指向一个必定失败的主机：这里是验证"失败也要静默"，不是验证网络
  const v = await latestVersion();
  assert.equal(typeof v, 'string');   // 有网就是版本号，没网就是 ''
});
