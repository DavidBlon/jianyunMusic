const port = Number(process.env.CDP_PORT || 9338);
const debugBase = `http://127.0.0.1:${port}`;
const fileUrl = process.env.FILE_URL || "file:///D:/Desktop/Another/NeteaseCloudMusicForMe/webapp/public/index.html";
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
  if (!message.id || !pending.has(message.id)) return;
  const promise = pending.get(message.id);
  pending.delete(message.id);
  message.error ? promise.reject(new Error(message.error.message)) : promise.resolve(message.result);
});

function send(method, params = {}) {
  const id = nextId++;
  socket.send(JSON.stringify({ id, method, params }));
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
}

async function evaluate(expression) {
  const result = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
  return result.result.value;
}

async function seedDiscover() {
  return evaluate(`(() => {
    const track = { id: "test-track", title: "可点击测试歌曲", artist: "简云音乐", album: "测试专辑", duration: 120, artwork: "", url: "data:audio/mpeg;base64,", source: "kg", sourceName: "酷狗" };
    discoveryTracks = Array.from({ length: 8 }, (_, index) => ({ ...track, id: "test-track-" + index, title: track.title + (index + 1) }));
    discoveryPlaylists = Array.from({ length: 8 }, (_, index) => ({ id: "test-list-" + index, name: "可点击测试歌单" + (index + 1), artwork: "", source: "kg", tracks: [discoveryTracks[index]] }));
    state.key = "local-test-key";
    state.discoverLoading = false;
    state.route = "discover";
    state.panel = null;
    state.current = null;
    state.playing = false;
    render();
    return true;
  })()`);
}

async function point(selector, xRatio = 0.5) {
  return evaluate(`(() => {
    const rect = document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();
    return { x: rect.left + rect.width * ${xRatio}, y: rect.top + rect.height * 0.45 };
  })()`);
}

async function click(selector) {
  const position = await point(selector);
  await send("Input.dispatchMouseEvent", { type: "mouseMoved", ...position });
  await send("Input.dispatchMouseEvent", { type: "mousePressed", ...position, button: "left", buttons: 1, clickCount: 1 });
  await send("Input.dispatchMouseEvent", { type: "mouseReleased", ...position, button: "left", buttons: 0, clickCount: 1 });
  await sleep(250);
}

await send("Runtime.enable");
await send("Page.enable");
await send("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
await send("Page.navigate", { url: fileUrl });
await sleep(1000);

await seedDiscover();
await evaluate(`(() => {
  window.__discoverClickTrace = [];
  for (const type of ["pointerdown", "pointerup", "click"]) {
    document.addEventListener(type, (event) => window.__discoverClickTrace.push({ type, target: event.target.className, action: event.target.closest?.("[data-action]")?.dataset.action || "" }), { capture: true, once: true });
  }
})()`);
await click('[data-action="open-discovery-playlist"]');
const playlistRoute = await evaluate("state.route");
const clickTrace = await evaluate("window.__discoverClickTrace");

await seedDiscover();
await click('[data-action="play-track"]');
const songClick = await evaluate(`({ route: state.route, title: state.current?.title || "", miniPlayer: Boolean(document.querySelector(".mini-player")) })`);

await seedDiscover();
const dragStart = await point('[data-carousel="playlists"]', 0.75);
const dragEnd = { x: dragStart.x - 90, y: dragStart.y };
await send("Input.dispatchMouseEvent", { type: "mouseMoved", ...dragStart });
await send("Input.dispatchMouseEvent", { type: "mousePressed", ...dragStart, button: "left", buttons: 1, clickCount: 1 });
await send("Input.dispatchMouseEvent", { type: "mouseMoved", ...dragEnd, button: "left", buttons: 1 });
await send("Input.dispatchMouseEvent", { type: "mouseReleased", ...dragEnd, button: "left", buttons: 0, clickCount: 1 });
await sleep(100);
const dragResult = await evaluate(`({ route: state.route, scrollLeft: document.querySelector('[data-carousel="playlists"]').scrollLeft })`);

await send("Emulation.setDeviceMetricsOverride", { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
await seedDiscover();
const touchPosition = await point('[data-action="open-discovery-playlist"]');
await send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ ...touchPosition, radiusX: 1, radiusY: 1, force: 1, id: 1 }] });
await send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
await sleep(250);
const mobilePlaylistRoute = await evaluate("state.route");

const result = { playlistRoute, clickTrace, songClick, dragResult, mobilePlaylistRoute };
console.log(JSON.stringify(result, null, 2));
socket.close();

if (playlistRoute !== "playlist") process.exitCode = 2;
if (songClick.route !== "discover" || songClick.title !== "可点击测试歌曲1" || !songClick.miniPlayer) process.exitCode = 3;
if (dragResult.route !== "discover" || dragResult.scrollLeft <= 0) process.exitCode = 4;
if (mobilePlaylistRoute !== "playlist") process.exitCode = 5;
