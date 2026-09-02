const port = Number(process.env.CDP_PORT || 9334);
const base = `http://127.0.0.1:${port}`;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
let targets;
for (let attempt = 0; attempt < 30; attempt += 1) {
  try {
    targets = await fetch(`${base}/json/list`).then((response) => response.json());
    if (targets.some((target) => target.type === "page")) break;
  } catch {}
  await sleep(200);
}
const target = targets?.find((item) => item.type === "page");
if (!target) throw new Error("Chrome 调试目标未就绪");
const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { socket.addEventListener("open", resolve, { once: true }); socket.addEventListener("error", reject, { once: true }); });
let nextId = 1;
const pending = new Map();
const apiRequests = [];
socket.addEventListener("message", (event) => {
  const message = JSON.parse(event.data);
  if (message.id && pending.has(message.id)) { const promise = pending.get(message.id); pending.delete(message.id); message.error ? promise.reject(new Error(message.error.message)) : promise.resolve(message.result); }
  if (message.method === "Network.requestWillBeSent" && new URL(message.params.request.url).pathname.startsWith("/api/")) apiRequests.push(message.params.request.url);
});
function send(method, params = {}) { const id = nextId++; socket.send(JSON.stringify({ id, method, params })); return new Promise((resolve, reject) => pending.set(id, { resolve, reject })); }
async function evaluate(expression) { const result = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true }); return result.result.value; }
async function waitFor(expression) { for (let i = 0; i < 60; i += 1) { if (await evaluate(`Boolean(${expression})`)) return; await sleep(100); } throw new Error(`等待超时：${expression}`); }
function assert(condition, message) { if (!condition) throw new Error(message); }

await send("Runtime.enable");
await send("Network.enable");
await send("Emulation.setDeviceMetricsOverride", { width: 430, height: 932, deviceScaleFactor: 1, mobile: true });
await send("Page.reload", { ignoreCache: true });
await waitFor(`document.querySelector('.connection-empty')`);
let result = await evaluate(`(() => ({ heading: document.querySelector('.connection-empty strong')?.textContent, cards: document.querySelectorAll('.media-card').length, stored: JSON.parse(localStorage.getItem('jianyun.web.state') || '{}') }))()`);
assert(result.heading === "尚未连接在线音乐来源", "发现页未显示未连接状态");
assert(result.cards === 0, "未绑定时仍显示预置推荐数据");
assert(!result.stored.key && (!result.stored.liked || result.stored.liked.length === 0), "浏览器仍保存旧密钥或预置收藏");

await evaluate(`document.querySelector('[data-route="search"]').click()`);
await waitFor(`document.querySelector('#search-input')`);
result = await evaluate(`(() => ({ disabled: document.querySelector('#search-input').disabled, rows: document.querySelectorAll('.track-row').length, history: document.querySelectorAll('.history-item').length }))()`);
assert(result.disabled && result.rows === 0 && result.history === 0, "未绑定搜索页仍有可用搜索或旧数据");

await evaluate(`document.querySelector('[data-route="my"]').click()`);
await waitFor(`document.querySelector('.page-title')?.textContent === '我的音乐'`);
result = await evaluate(`[...document.querySelectorAll('.playlist-tile')].map((node) => node.innerText)`);
assert(result.length === 2 && result.every((text) => text.includes("0 首")), "我的音乐仍显示预置个人数据");
assert(apiRequests.length === 0, `未绑定状态发出了在线请求：${apiRequests.join(", ")}`);
console.log("未绑定发现页：无推荐数据\n未绑定搜索页：输入禁用、无结果、无历史\n未绑定我的音乐：收藏 0、最近播放 0\n在线 API 请求：0");
socket.close();
