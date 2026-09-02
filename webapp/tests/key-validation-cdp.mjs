const port = Number(process.env.CDP_PORT || 9337);
const debugBase = `http://127.0.0.1:${port}`;
const fileUrl = "file:///D:/Desktop/Another/NeteaseCloudMusicForMe/webapp/public/index.html";
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
let targets;
for (let attempt = 0; attempt < 40; attempt += 1) {
  try {
    targets = await fetch(`${debugBase}/json/list`).then((response) => response.json());
    if (targets.some((target) => target.type === "page")) break;
  } catch {}
  await sleep(200);
}
const target = targets?.find((item) => item.type === "page");
if (!target) throw new Error("Chrome 调试目标未就绪");
const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.addEventListener("open", resolve, { once: true });
  socket.addEventListener("error", reject, { once: true });
});
let nextId = 1;
const pending = new Map();
socket.addEventListener("message", (event) => {
  const message = JSON.parse(event.data);
  if (message.id && pending.has(message.id)) {
    const promise = pending.get(message.id);
    pending.delete(message.id);
    message.error ? promise.reject(new Error(message.error.message)) : promise.resolve(message.result);
  }
});
function send(method, params = {}) {
  const id = nextId++;
  socket.send(JSON.stringify({ id, method, params }));
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
}
async function evaluate(expression) {
  const result = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) throw new Error(result.result?.description || result.exceptionDetails.text);
  return result.result.value;
}
async function waitFor(expression) {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    if (await evaluate(`Boolean(${expression})`)) return;
    await sleep(100);
  }
  throw new Error(`等待超时：${expression}`);
}
await send("Runtime.enable");
await send("Page.enable");
await send("Page.navigate", { url: fileUrl });
await waitFor(`document.querySelector('.connection-empty')`);
await evaluate(`document.querySelector('[data-route="my"]').click()`);
await evaluate(`document.querySelector('[data-panel="settings"]').click()`);
await evaluate(`document.querySelector('.setting-row[data-panel="source"]').click()`);
await evaluate(`(() => { document.querySelector('#source-key').value='definitely-invalid-key'; document.querySelector('[data-action="validate-key"]').click(); })()`);
await waitFor(`document.querySelector('#toast')?.textContent.includes('格式不正确') || document.querySelector('#toast')?.textContent.includes('无效')`);
const result = await evaluate(`({ toast:document.querySelector('#toast').textContent, saved:JSON.parse(localStorage.getItem('jianyun.web.state') || '{}').key || '' })`);
if (result.saved) throw new Error("无效卡密被错误保存");
const valid = await evaluate(`(async () => {
  const originalFetch = window.fetch;
  window.fetch = async (input, options) => {
    if (String(input).includes('/api/music/url')) return new Response(JSON.stringify({ code:400, message:'缺少必要参数: source, songId, quality' }), { status:400, headers:{'Content-Type':'application/json'} });
    return originalFetch(input, options);
  };
  try { return await window.__JianYunTest.validateSourceKey('12345678-valid-shape'); }
  finally { window.fetch = originalFetch; }
})()`);
if (valid !== true) throw new Error("通过鉴权并到达参数校验层的卡密没有被判定为有效");
const savedAfterValid = await evaluate(`JSON.parse(localStorage.getItem('jianyun.web.state') || '{}').key || ''`);
if (savedAfterValid) throw new Error("有效卡密被写入 localStorage");
console.log(JSON.stringify({ ...result, validProbeAccepted: valid, savedAfterValid }));
socket.close();
