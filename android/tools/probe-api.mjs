/**
 * 验证：自定义 UI 能否通过 /api/remote.mux 拿到会话列表 + 消息历史 + 实时事件。
 * 端点格式 <namespace>/<method>，帧格式 {type:'open',streamId,endpoint,payload:{args:{...}}}
 */
const BASE = process.env.DSH_URL || 'http://100.100.190.107:3081';
const MUX = BASE.replace(/^http/, 'ws') + '/api/remote.mux';

async function getCookie() {
  const r = await fetch(BASE + '/', { redirect: 'manual' });
  const cookie = (r.headers.getSetCookie?.() ?? []).map((c) => c.split(';')[0]).join('; ');
  await r.text().catch(() => {});
  await fetch(BASE + '/', { headers: { cookie } }).then((x) => x.text());
  return cookie;
}

/** 打开一条逻辑流，收集帧，直到 end / error / 超时。 */
function call(cookie, endpoint, args, label, waitMs = 8000) {
  return new Promise((resolve) => {
    const out = [];
    const streamId = 'q' + Math.random().toString(36).slice(2, 7);
    const ws = new WebSocket(MUX, { headers: { cookie, origin: BASE } });
    let settled = false;
    const finish = (why) => {
      if (settled) return;
      settled = true;
      try { ws.close(); } catch {}
      console.log(`\n── ${label}  [${endpoint}]  ${why}`);
      for (const f of out) {
        let v = f.value !== undefined ? f.value : f.error;
        const s = JSON.stringify(v);
        console.log(`   ${f.type}: ${s && s.length > 900 ? s.slice(0, 900) + ' …' : s}`);
      }
      if (!out.length) console.log('   （无帧）');
      resolve(out);
    };
    const timer = setTimeout(() => finish('超时'), waitMs);
    ws.onopen = () => ws.send(JSON.stringify({ type: 'open', streamId, endpoint, payload: { args } }));
    ws.onmessage = (ev) => {
      const f = JSON.parse(ev.data);
      out.push(f);
      if (f.type === 'end' || f.type === 'error') { clearTimeout(timer); setTimeout(() => finish(f.type), 150); }
      else if (out.length >= 6) { clearTimeout(timer); setTimeout(() => finish('收满'), 300); }
    };
    ws.onerror = () => { clearTimeout(timer); finish('WS 错误'); };
    ws.onclose = () => { clearTimeout(timer); finish('WS 关闭'); };
  });
}

(async () => {
  const cookie = await getCookie();
  console.log('cookie ok');

  const frames = await call(cookie, 'session/list', { _request: {} }, '① 会话列表');
  const item = frames.find((f) => f.type === 'item');
  const items = item?.value?.items ?? [];
  console.log(`\n   >>> 解析结果：${items.length} 个会话`);
  if (items[0]) {
    console.log('   >>> 首个会话字段:', Object.keys(items[0]).join(', '));
  }

  const sid = items[0]?.sessionId ?? items[0]?.id;
  if (sid) {
    await call(cookie, 'session/page', { request: { sessionId: sid, limit: 5 } }, `② 消息历史 (${sid.slice(0, 22)}…)`);
  } else {
    console.log('   （没有会话，跳过历史拉取）');
  }

  await call(cookie, 'session/modelCatalog', { _request: {} }, '③ 模型目录');
})().catch((e) => { console.error('失败:', e); process.exit(1); });
