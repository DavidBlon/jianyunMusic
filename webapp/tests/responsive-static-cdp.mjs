import { writeFileSync } from "node:fs";

const port = Number(process.env.CDP_PORT || 9334);
const debugBase = `http://127.0.0.1:${port}`;
const appBase = "http://127.0.0.1:4180";
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
const browserErrors = [];
const apiRequests = [];
socket.addEventListener("message", (event) => {
  const message = JSON.parse(event.data);
  if (message.id && pending.has(message.id)) {
    const promise = pending.get(message.id);
    pending.delete(message.id);
    message.error ? promise.reject(new Error(message.error.message)) : promise.resolve(message.result);
  }
  if (message.method === "Runtime.exceptionThrown") browserErrors.push(message.params.exceptionDetails.text);
  if (message.method === "Network.requestWillBeSent" && message.params.request.url.includes("source.shiqianjiang.cn")) apiRequests.push(message.params.request.url);
});
function send(method, params = {}) {
  const id = nextId++;
  socket.send(JSON.stringify({ id, method, params }));
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
}
async function evaluate(expression) {
  const result = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.text);
  return result.result.value;
}
async function waitFor(expression) {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    if (await evaluate(`Boolean(${expression})`)) return;
    await sleep(100);
  }
  throw new Error(`等待超时：${expression}`);
}
function assert(value, message) { if (!value) throw new Error(message); }
async function screenshot(path) {
  const result = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
  writeFileSync(path, Buffer.from(result.data, "base64"));
}

await send("Runtime.enable");
await send("Page.enable");
await send("Network.enable");
await send("Emulation.setDeviceMetricsOverride", { width: 1536, height: 960, deviceScaleFactor: 1, mobile: false });
await send("Page.navigate", { url: appBase });
await waitFor(`document.querySelector('.connection-empty strong')`);
let result = await evaluate(`({ heading: document.querySelector('.connection-empty strong')?.textContent, cards: document.querySelectorAll('.media-card').length })`);
assert(result.heading === "尚未连接在线音乐来源" && result.cards === 0, "未绑定状态不正确");
assert(apiRequests.length === 0, "未绑定状态不应请求聆澜在线数据");

await evaluate(`document.querySelector('[data-route="my"]').click()`);
await waitFor(`document.querySelector('.page-title')?.textContent === '我的音乐'`);
await evaluate(`document.querySelector('[data-panel="settings"]').click()`);
await evaluate(`document.querySelector('.setting-row[data-panel="source"]').click()`);
result = await evaluate(`[...document.querySelectorAll('.source-option span')].map(node => node.textContent)`);
assert(JSON.stringify(result) === JSON.stringify(["酷狗音乐 v7", "酷我音乐 v7", "QQ音乐 v7", "网易云音乐 v7"]), "四音源入口不完整");
await evaluate(`(() => { const input=document.querySelector('#source-key'); input.value='definitely-invalid-key'; document.querySelector('[data-action="validate-key"]').click(); })()`);
await waitFor(`document.querySelector('#toast')?.textContent.includes('卡密无效')`);
result = await evaluate(`JSON.parse(localStorage.getItem('jianyun.web.state') || '{}').key || ''`);
assert(result === "", "无效卡密被错误保存");

const seed = {
  dataVersion: 2, theme: "deep", sourceId: "linglan.kg", key: "", maskedKey: "",
  current: { id: "demo", remoteId: "demo", source: "linglan.kg", sourceName: "酷狗", title: "南山雪（古风DJ）", artist: "王野川、Sixteen", artwork: "./assets/app-icon.png", duration: 162 },
  queue: [], liked: [], recent: [], playlists: [], searchHistory: [], returnRoute: "discover",
  playerLayout: "disc", playerEffect: "none", rhythmArtwork: true,
  componentVisibility: { songInfo: true, artwork: true, progress: true, transport: true, extras: true, favorite: true }
};
await evaluate(`localStorage.setItem('jianyun.web.state', ${JSON.stringify(JSON.stringify(seed))}); location.href='${appBase}/?route=player'`);
await waitFor(`document.querySelector('.player-layout')`);
result = await evaluate(`(() => { const layout=getComputedStyle(document.querySelector('.player-layout')); const d=document.querySelector('.disc-stage').getBoundingClientRect(); const c=document.querySelector('.player-detail').getBoundingClientRect(); return {display:layout.display, discLeft:d.left, discRight:d.right, detailLeft:c.left, detailRight:c.right, fake:document.body.innerText.includes('让音乐回归音乐本身'), mobileTop:getComputedStyle(document.querySelector('.player-mobile-top')).display}; })()`);
assert(result.display === "grid", "桌面播放页未展开为双栏");
assert(result.discLeft >= 0 && result.discRight <= result.detailLeft + 50 && result.detailRight <= 1536, "桌面播放页布局越界或没有分栏");
assert(!result.fake && result.mobileTop === "none", "桌面播放页仍有伪文案或手机顶栏");
await screenshot("tests/desktop-player.png");

await send("Emulation.setDeviceMetricsOverride", { width: 430, height: 932, deviceScaleFactor: 1, mobile: true });
await send("Page.reload", { ignoreCache: true });
await waitFor(`document.querySelector('.player-mobile-top')`);
result = await evaluate(`({ mobileTop:getComputedStyle(document.querySelector('.player-mobile-top')).display, fakeLyrics:document.querySelectorAll('.lyrics').length, overflow:document.documentElement.scrollWidth > innerWidth })`);
assert(result.mobileTop !== "none" && result.fakeLyrics === 0 && !result.overflow, "移动端播放页布局异常");
await screenshot("tests/mobile-player.png");

await send("Emulation.setDeviceMetricsOverride", { width: 1280, height: 800, deviceScaleFactor: 1, mobile: false });
const first = { id: "a", source: "linglan.kg", title: "第一首", artist: "A", artwork: "./assets/app-icon.png", url: "data:audio/mp3;base64," };
const second = { id: "b", source: "linglan.kg", title: "第二首", artist: "B", artwork: "./assets/app-icon.png", url: "data:audio/mp3;base64," };
await evaluate(`localStorage.setItem('jianyun.web.state', ${JSON.stringify(JSON.stringify({ ...seed, current: first, queue: [first, second] }))}); location.href='${appBase}/'`);
await waitFor(`document.querySelector('.connection-empty')`);
result = await evaluate(`(() => { const host=document.createElement('div'); host.className='carousel-shell'; host.innerHTML='<button class="carousel-arrow next" data-action="scroll-carousel" data-direction="1"></button><div class="horizontal-scroller" data-carousel="test">'+Array.from({length:9},(_,i)=>'<article class="media-card"><div class="card-art"></div><h3>测试 '+i+'</h3></article>').join('')+'</div>'; document.querySelector('.content-frame').append(host); const scroller=host.querySelector('.horizontal-scroller'); const before=scroller.scrollLeft; host.querySelector('.carousel-arrow.next').click(); return new Promise(resolve => setTimeout(() => resolve({ before, after:scroller.scrollLeft, cards:scroller.children.length, overflow:scroller.scrollWidth > scroller.clientWidth }), 550)); })()`);
assert(result.cards > 4 && result.overflow && result.after > result.before, "发现页横向滚动按钮未生效");

await evaluate(`document.querySelector('.mini-player [data-action="next"]').click()`);
await waitFor(`document.querySelector('.mini-copy strong')?.textContent === '第二首'`);
result = await evaluate(`({ discover:Boolean(document.querySelector('.connection-empty')), player:Boolean(document.querySelector('.player-screen')), title:document.querySelector('.mini-copy strong')?.textContent })`);
assert(result.discover && !result.player && result.title === "第二首", "切歌仍然强制跳转播放页");

assert(browserErrors.length === 0, `浏览器脚本异常：${browserErrors.join(" | ")}`);
console.log("静态未绑定状态、四音源入口、桌面双栏播放页、移动播放页均通过");
socket.close();
