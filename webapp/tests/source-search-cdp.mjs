const port = Number(process.env.CDP_PORT || 9335);
const debugBase = `http://127.0.0.1:${port}`;
const appBase = "http://127.0.0.1:4181";
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
const mixedContent = [];
socket.addEventListener("message", (event) => {
  const message = JSON.parse(event.data);
  if (message.id && pending.has(message.id)) {
    const promise = pending.get(message.id);
    pending.delete(message.id);
    message.error ? promise.reject(new Error(message.error.message)) : promise.resolve(message.result);
  }
  if (message.method === "Log.entryAdded" && /mixed content/i.test(message.params.entry.text)) mixedContent.push(message.params.entry.text);
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
await send("Log.enable");
await send("Page.navigate", { url: `${appBase}/subdir/` });
await waitFor(`document.querySelector('.connection-empty')`);
const resources = await evaluate(`performance.getEntriesByType('resource').filter(item => ['script','link','img'].includes(item.initiatorType)).map(item => ({name:item.name, duration:item.duration}))`);
if (!resources.some((item) => item.name.includes("/subdir/app.js?v=4") && item.duration > 0)) throw new Error("二级目录中的脚本资源未加载");
const results = await evaluate(`(async () => {
  const sources = ['linglan.kg','linglan.kw','linglan.tx','linglan.wy'];
  const output = {};
  for (const source of sources) {
    try {
      const tracks = await window.__JianYunTest.searchSource('周杰伦', source);
      output[source] = { count: tracks.length, sample: tracks[0] ? { id: tracks[0].id, title: tracks[0].title, artist: tracks[0].artist } : null };
    } catch (error) {
      output[source] = { count: 0, error: error.message };
    }
  }
  return output;
})()`);
for (const [source, result] of Object.entries(results)) {
  if (!result.count || !result.sample?.id || !result.sample?.title) throw new Error(`${source} 搜索失败：${JSON.stringify(result)}`);
}
if (mixedContent.length) throw new Error(`发现混合内容请求：${mixedContent.join(" | ")}`);
console.log(JSON.stringify(results, null, 2));
socket.close();
