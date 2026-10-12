const fs = require('node:fs');
// 仅用于本机验收；不保存Cookie、视频地址或账号信息。
async function connect(port, match = 'douyin.com') {
  const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const page = pages.find(p => p.type === 'page' && p.url.includes(match));
  if (!page) throw new Error('No matching debug page');
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let id = 0; const pending = new Map();
  ws.onmessage = e => { const m = JSON.parse(e.data); if (pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } };
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const n = ++id;
    const timer = setTimeout(() => { pending.delete(n); reject(new Error(`Timeout: ${method}`)); }, 20000);
    pending.set(n, m => { clearTimeout(timer); m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result); });
    ws.send(JSON.stringify({id:n, method, params}));
  });
  const evaluate = async expression => {
    const r = await call('Runtime.evaluate', {expression, returnByValue:true, awaitPromise:true});
    if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails));
    return r.result.value;
  };
  return { call, evaluate, close: () => ws.close() };
}
module.exports = {connect};
if (require.main === module) (async () => {
  const c = await connect(process.argv[2] || 9224, process.argv[3] || 'douyin.com');
  try { console.log(JSON.stringify(await c.evaluate(process.argv[4] || 'window.LightSwipe?.status()'), null, 2)); }
  finally { c.close(); }
})().catch(e => { console.error(e.message); process.exitCode=1; });
