const port = Number(process.env.CDP_PORT || 9336);
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
const errors = [];
socket.addEventListener("message", (event) => {
  const message = JSON.parse(event.data);
  if (message.id && pending.has(message.id)) {
    const promise = pending.get(message.id);
    pending.delete(message.id);
    message.error ? promise.reject(new Error(message.error.message)) : promise.resolve(message.result);
  }
  if (message.method === "Runtime.exceptionThrown") errors.push(message.params.exceptionDetails.text);
  if (message.method === "Log.entryAdded" && ["error", "warning"].includes(message.params.entry.level)) errors.push(message.params.entry.text);
});
function send(method, params = {}) {
  const id = nextId++;
  socket.send(JSON.stringify({ id, method, params }));
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
}
async function evaluate(expression) {
  const result = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  return result.result.value;
}
await send("Runtime.enable");
await send("Page.enable");
await send("Log.enable");
await send("Emulation.setDeviceMetricsOverride", { width: 1536, height: 900, deviceScaleFactor: 1, mobile: false });
await send("Page.navigate", { url: fileUrl });
await sleep(1500);
await evaluate(`document.querySelector('[data-route="my"]')?.click()`);
await sleep(150);
await evaluate(`document.querySelector('[data-panel="settings"]')?.click()`);
await evaluate(`document.querySelector('.setting-row[data-panel="source"]')?.click()`);
const result = await evaluate(`({ url:location.href, appText:document.querySelector('#app')?.innerText || '', appChildren:document.querySelector('#app')?.children.length || 0, scriptType:document.querySelector('script[src]')?.type || '', sources:[...document.querySelectorAll('.source-option span')].map(node => node.textContent) })`);
result.errors = errors;
const shot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
writeFileSync("tests/file-open-fixed.png", Buffer.from(shot.data, "base64"));
console.log(JSON.stringify(result, null, 2));
socket.close();
if (!result.appChildren) process.exitCode = 2;
if (result.sources.length !== 4 || errors.length) process.exitCode = 3;
import { writeFileSync } from "node:fs";
