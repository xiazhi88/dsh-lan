/**
 * 验证流式：session/follow 能不能拿到实时消息增量（自定义聊天界面的命脉）。
 */
const BASE = process.env.DSH_URL || 'http://100.100.190.107:3081';
const MUX = BASE.replace(/^http/, 'ws') + '/api/remote.mux';

let CK = '';
async function getCookie() {
  const r = await fetch(BASE + '/', { redirect: 'manual' });
  CK = (r.headers.getSetCookie?.() ?? []).map((c) => c.split(';')[0]).join('; ');
  await r.text().catch(() => {});
  await fetch(BASE + '/', { headers: { cookie: CK } }).then((x) => x.text());
  return CK;
}

async function unary(endpoint, args) {
  const r = await fetch(BASE + '/api/' + endpoint, {
    method: 'POST',
    headers: { 'content-type': 'application/json', cookie: CK },
    body: JSON.stringify({
      type: 'client-request', rpcId: 'r' + Math.random().toString(36).slice(2, 7),
      method: endpoint, payload: { args },
    }),
  });
  const j = await r.json();
  if (!j.result?.ok) throw new Error(endpoint + ' 失败: ' + JSON.stringify(j.result));
  return j.result.value;
}

function stream(endpoint, args, onFrame, waitMs) {
  return new Promise((resolve) => {
    const ws = new WebSocket(MUX, { headers: { cookie: CK, origin: BASE } });
    const streamId = 's' + Math.random().toString(36).slice(2, 7);
    let n = 0;
    let settled = false;
    const finish = (why) => {
      if (settled) return; settled = true;
      try { ws.close(); } catch {}
      console.log(`\n── ${endpoint} 结束（${why}，共 ${n} 帧）`);
      resolve();
    };
    const t = setTimeout(() => finish('超时'), waitMs);
    ws.onopen = () => ws.send(JSON.stringify({ type: 'open', streamId, endpoint, payload: { args } }));
    ws.onmessage = (ev) => {
      n++;
      const f = JSON.parse(ev.data);
      onFrame(f, n);
      if (f.type === 'end' || f.type === 'error') { clearTimeout(t); setTimeout(() => finish(f.type), 200); }
    };
    ws.onerror = () => { clearTimeout(t); finish('WS 错误'); };
    ws.onclose = () => { clearTimeout(t); finish('WS 关闭'); };
  });
}

(async () => {
  await getCookie();
  console.log('cookie ok\n');

  const { items } = await unary('session/list', { _request: {} });
  const target = items.find((s) => s.running) ?? items[0];
  const proj = target.projections?.values ?? {};
  console.log(`目标会话: ${target.sessionId}`);
  console.log(`  标题: ${proj.title}`);
  console.log(`  running: ${target.running}  cwd: ${target.cwd}`);
  console.log(`  asOfSeq: ${target.projections?.asOfSeq}\n`);

  const address = { kind: 'session', sessionId: target.sessionId };

  // ① 历史
  console.log('══ ① session/page（历史消息）══');
  const page = await unary('session/page', {
    request: { address, throughSeq: target.projections?.asOfSeq ?? 0, maxMessages: 4 },
  });
  console.log('  返回字段:', Object.keys(page).join(', '));
  const recs = page.records ?? [];
  console.log(`  记录数: ${recs.length}  hasMore: ${page.hasMore}`);
  for (const r of recs.slice(-4)) {
    console.log(`    - [${r.event?.type}] seq=${r.event?.seq} ${JSON.stringify(r.event?.data).slice(0, 150)}`);
  }

  // ② 流式跟随
  console.log('\n══ ② session/follow（实时流）══');
  const seen = new Map();
  await stream('session/follow', { request: { address, assistantStream: true } }, (f, n) => {
    const key = f.type + ':' + (f.value?.type ?? f.value?.kind ?? '');
    seen.set(key, (seen.get(key) ?? 0) + 1);
    if (n <= 5) console.log(`   帧${n} ${f.type} ${JSON.stringify(f.value ?? f.error).slice(0, 260)}`);
  }, 7000);
  console.log('  帧类型统计:');
  for (const [k, v] of [...seen.entries()].sort((a, b) => b[1] - a[1])) console.log(`    ${k}  ×${v}`);
})().catch((e) => { console.error('失败:', e.message); process.exit(1); });
