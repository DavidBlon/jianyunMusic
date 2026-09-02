import { writeFileSync } from "node:fs";

const port = Number(process.env.CDP_PORT || 9339);
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
async function metrics(width, height, mobile) {
  await send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: mobile ? 2 : 1, mobile });
}
async function screenshot(path) {
  const result = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
  writeFileSync(path, Buffer.from(result.data, "base64"));
}
async function touchCenter(selector) {
  const point = await evaluate(`(() => { const rect = document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect(); return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }; })()`);
  const hit = await evaluate(`(() => { const node = document.elementFromPoint(${point.x}, ${point.y}); return { className: node?.className || "", action: node?.closest?.('[data-action]')?.dataset.action || "" }; })()`);
  await send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ ...point, radiusX: 1, radiusY: 1, force: 1, id: 1 }] });
  await send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
  return hit;
}
async function seed(route = "discover", withCurrent = false) {
  await evaluate(`(() => {
    const makeTrack = (index) => ({ id: "ui-track-" + index, title: "界面测试歌曲" + (index + 1), artist: "简云音乐", album: "测试专辑", duration: 120, artwork: "./assets/app-icon.png", url: "data:audio/mpeg;base64,", source: "kg", sourceName: "酷狗" });
    discoveryTracks = Array.from({ length: 12 }, (_, index) => makeTrack(index));
    discoveryPlaylists = [{ id: "ui-list", name: "返回测试歌单", artwork: "./assets/app-icon.png", source: "kg", tracks: discoveryTracks.slice(0, 6) }];
    state.key = "local-test-key";
    state.discoverLoading = false;
    state.results = Array.from({ length: 16 }, (_, index) => makeTrack(index));
    state.current = ${withCurrent ? "discoveryTracks[1]" : "null"};
    state.queue = discoveryTracks.slice(0, 4);
    state.playing = false;
    state.playerLayout = "disc";
    state.playerSurface = "artwork";
    state.returnRoute = "discover";
    state.route = ${JSON.stringify(route)};
    state.panel = null;
    render();
  })()`);
}

await send("Runtime.enable");
await send("Page.enable");
await metrics(1536, 760, false);
await send("Page.navigate", { url: fileUrl });
await sleep(800);

const playbackSwitch = await evaluate(`(async () => {
  const originalFetchJson = fetchJson;
  const originalPlay = $audio.play;
  const originalPause = $audio.pause;
  const originalLoad = $audio.load;
  const starts = [];
  let pauses = 0;
  fetchJson = async (url) => {
    const songId = new URL(String(url)).searchParams.get("songId");
    await new Promise((resolve) => setTimeout(resolve, songId === "old-track" ? 120 : 12));
    return { code: 200, url: "data:audio/mpeg;base64," + (songId === "old-track" ? "T0xE" : "TkVX") };
  };
  $audio.play = async () => { starts.push($audio.src.includes("T0xE") ? "old" : "new"); };
  $audio.pause = () => { pauses += 1; };
  $audio.load = () => {};
  state.key = "local-test-key";
  keyValidated = true;
  lastKeyValidation = Date.now();
  const oldTrack = { id: "old-track", songmid: "old-track", source: "linglan.wy", title: "旧歌曲", artist: "测试" };
  const newTrack = { id: "new-track", songmid: "new-track", source: "linglan.wy", title: "新歌曲", artist: "测试" };
  const first = playTrack(oldTrack, [oldTrack, newTrack]);
  await new Promise((resolve) => setTimeout(resolve, 5));
  const second = playTrack(newTrack, [oldTrack, newTrack]);
  await Promise.all([first, second]);
  fetchJson = originalFetchJson;
  $audio.play = originalPlay;
  $audio.pause = originalPause;
  $audio.load = originalLoad;
  return { starts, pauses, current: state.current?.id || "" };
})()`);

const recommendationSet = await evaluate(`(() => {
  const tracks = Array.from({ length: 18 }, (_, index) => ({ id: "smart-track-" + index, title: "推荐歌曲" + index, artist: "测试", artwork: "./assets/app-icon.png" }));
  const lists = typeof buildSmartPlaylists === "function" ? buildSmartPlaylists(tracks, "linglan.wy", 8, "test-smart") : [];
  discoveryTracks = tracks;
  discoveryPlaylists = lists;
  state.key = "local-test-key";
  state.discoverLoading = false;
  state.route = "discover";
  render();
  return { count: lists.length, rendered: document.querySelectorAll('[data-carousel="playlists"] .media-card').length, uniqueIds: new Set(lists.map((item) => item.id)).size, allPlayable: lists.every((item) => item.tracks?.length > 0) };
})()`);

await seed("search", true);
await evaluate(`(() => {
  Object.defineProperty($audio, "duration", { value: 120, configurable: true });
  Object.defineProperty($audio, "currentTime", { value: 30, configurable: true });
  $audio.dispatchEvent(new Event("timeupdate"));
})()`);
await sleep(260);
const miniProgress = await evaluate(`(() => {
  const mini = document.querySelector(".mini-player");
  const line = getComputedStyle(mini, "::after");
  return { value: mini.style.getPropertyValue("--mini-progress"), lineWidth: Math.round(parseFloat(line.width)), playerWidth: Math.round(mini.getBoundingClientRect().width) };
})()`);
const desktopOverlay = await evaluate(`(() => {
  const screen = document.querySelector(".screen");
  screen.scrollTop = screen.scrollHeight;
  const last = document.querySelector(".track-row:last-child").getBoundingClientRect();
  const mini = document.querySelector(".mini-player").getBoundingClientRect();
  return { lastBottom: Math.round(last.bottom), miniTop: Math.round(mini.top), clear: last.bottom + 12 <= mini.top };
})()`);
await seed("discover", true);
const desktopDiscoverOverlay = await evaluate(`(() => {
  const screen = document.querySelector(".screen");
  screen.scrollTop = screen.scrollHeight;
  const last = document.querySelector('[data-carousel="tracks"] .media-card:last-child').getBoundingClientRect();
  const mini = document.querySelector(".mini-player").getBoundingClientRect();
  const viewport = screen.getBoundingClientRect();
  return { screenBottom: Math.round(viewport.bottom), lastBottom: Math.round(last.bottom), miniTop: Math.round(mini.top), clear: viewport.bottom + 12 <= mini.top && last.bottom + 12 <= mini.top };
})()`);
await screenshot("tests/ui-desktop-clear.png");

const miniHasPrevious = await evaluate(`Boolean(document.querySelector('.mini-player [data-action="prev"]'))`);
if (miniHasPrevious) {
  await evaluate(`document.querySelector('.mini-player [data-action="prev"]').click()`);
  await sleep(80);
}
const previousTitle = await evaluate(`document.querySelector('.mini-copy strong')?.textContent || ""`);

await evaluate(`(() => { state.route = "player"; state.panel = "appearance"; state.current = state.queue[0]; render(); const sheet = document.querySelector('.appearance-sheet'); sheet.scrollTop = Math.min(260, sheet.scrollHeight - sheet.clientHeight); })()`);
const settingsBefore = await evaluate(`document.querySelector('.appearance-sheet').scrollTop`);
await evaluate(`document.querySelector('input[name="player-component"][value="progress"]').click()`);
await sleep(80);
const settingsAfterCheckbox = await evaluate(`document.querySelector('.appearance-sheet')?.scrollTop ?? -1`);
await evaluate(`document.querySelector('[data-action="player-layout"][data-value="cover"]').click()`);
await sleep(80);
const settingsAfterButton = await evaluate(`document.querySelector('.appearance-sheet')?.scrollTop ?? -1`);
const settingsScroll = { before: settingsBefore, afterCheckbox: settingsAfterCheckbox, afterButton: settingsAfterButton };

await metrics(390, 844, true);
await seed("player", true);
const mobileArtwork = await evaluate(`(() => {
  const rect = document.querySelector(".disc-stage").getBoundingClientRect();
  return { left: Math.round(rect.left), right: Math.round(rect.right), width: Math.round(rect.width), viewport: innerWidth, fullyVisible: rect.left >= 16 && rect.right <= innerWidth - 16 };
})()`);
await screenshot("tests/ui-mobile-player.png");

await evaluate(`(() => {
  state.current.lyric = "[00:00.00]第一句歌词\\n[00:12.00]第二句歌词\\n[00:24.00]第三句歌词";
  state.playerLayout = "cover";
  state.playerSurface = "artwork";
  render();
})()`);
const coverAppearance = await evaluate(`(() => {
  const cover = document.querySelector('.cover-stage').getBoundingClientRect();
  const play = getComputedStyle(document.querySelector('.main-play'));
  return { width: Math.round(cover.width), height: Math.round(cover.height), square: Math.abs(cover.width - cover.height) <= 6, playBackground: play.backgroundColor };
})()`);
await screenshot("tests/ui-mobile-cover.png");
const coverTouchHit = await touchCenter('.cover-stage');
await sleep(80);
const lyricsOpened = await evaluate(`({ visible: Boolean(document.querySelector('.lyrics-panel')), first: document.querySelector('.lyric-line')?.textContent?.trim() || '' })`);
const lyricSync = await evaluate(`(() => { if (typeof updateLyricsPosition !== 'function') return { supported: false, active: '' }; updateLyricsPosition(13); return { supported: true, active: document.querySelector('.lyric-line.active')?.textContent?.trim() || '' }; })()`);
await sleep(320);
const lyricsLayout = await evaluate(`(() => {
  const panel = document.querySelector('.lyrics-panel').getBoundingClientRect();
  const surface = document.querySelector('.player-lyrics-surface').getBoundingClientRect();
  const active = document.querySelector('.lyric-line.active').getBoundingClientRect();
  return {
    panelTop: Math.round(panel.top),
    panelHeight: Math.round(panel.height),
    surfaceTop: Math.round(surface.top),
    surfaceHeight: Math.round(surface.height),
    innerTopGap: Math.round(panel.top - surface.top),
    activeCenterRatio: Number((((active.top + active.height / 2) - surface.top) / surface.height).toFixed(3))
  };
})()`);
await screenshot("tests/ui-mobile-lyrics.png");
let lyricsTouchHit = { className: "", action: "" };
if (lyricsOpened.visible) {
  lyricsTouchHit = await touchCenter('.player-lyrics-surface');
  await sleep(80);
}
const returnedToCover = await evaluate(`Boolean(document.querySelector('.cover-stage'))`);
await evaluate(`(() => {
  state.current.lyric = "";
  state.current.lyricLoaded = false;
  state.current.lyricLoading = false;
  state.current.lyricUrl = "data:text/plain;charset=utf-8," + encodeURIComponent("[00:00.00]按需加载歌词");
  state.playerSurface = "artwork";
  render();
  document.querySelector('.cover-stage').click();
})()`);
for (let attempt = 0; attempt < 30 && await evaluate(`!document.querySelector('.lyric-line')`); attempt += 1) await sleep(40);
const lazyLyrics = await evaluate(`document.querySelector('.lyric-line')?.textContent?.trim() || ''`);

await seed("discover", false);
const beforeJitter = await evaluate(`(() => { const scroller = document.querySelector('[data-carousel="tracks"]'); scroller.style.scrollBehavior = "auto"; scroller.scrollLeft = 220; return scroller.scrollLeft; })()`);
await evaluate(`document.querySelectorAll('[data-action="play-track"]')[2].click()`);
await sleep(180);
const afterJitter = await evaluate(`document.querySelector('[data-carousel="tracks"]')?.scrollLeft ?? -1`);

await seed("discover", false);
await evaluate(`(() => { const screen = document.querySelector('.screen'); screen.scrollTop = 100; const scroller = document.querySelector('[data-carousel="tracks"]'); scroller.style.scrollBehavior = "auto"; scroller.scrollLeft = 120; })()`);
await evaluate(`document.querySelector('[data-action="open-discovery-playlist"]').click()`);
for (let attempt = 0; attempt < 20 && await evaluate(`state.route !== "playlist"`); attempt += 1) await sleep(30);
await evaluate(`document.querySelector('.playlist-hero [data-action="back"]').click()`);
await sleep(60);
const returnedRoute = await evaluate(`state.route`);
const returnedPosition = await evaluate(`({ vertical: document.querySelector('.screen').scrollTop, horizontal: document.querySelector('[data-carousel="tracks"]').scrollLeft })`);

await seed("my", true);
await evaluate(`(() => { state.liked = discoveryTracks.slice(0, 4); state.route = "my"; render(); document.querySelector('[data-action="open-playlist"][data-id="liked"]').click(); })()`);
await sleep(60);
await evaluate(`document.querySelector('.mini-copy').click()`);
await sleep(60);
await evaluate(`document.querySelector('.player-screen [data-action="back"]').click()`);
await sleep(60);
const routeAfterPlayerBack = await evaluate(`state.route`);
await evaluate(`document.querySelector('.playlist-hero [data-action="back"]').click()`);
await sleep(60);
const playlistBackChain = { afterPlayerBack: routeAfterPlayerBack, final: await evaluate(`state.route`) };

await seed("my", true);
await evaluate(`(() => {
  state.liked = [
    { id: "playlist-find-1", title: "海与星光", artist: "甲歌手", album: "远方", artwork: "./assets/app-icon.png", url: "data:audio/mpeg;base64,", source: "linglan.wy" },
    { id: "playlist-find-2", title: "冬日恋人", artist: "乙歌手", album: "海岸", artwork: "./assets/app-icon.png", url: "data:audio/mpeg;base64,", source: "linglan.wy" },
    { id: "playlist-find-3", title: "南山夜色", artist: "丙歌手", album: "星光", artwork: "./assets/app-icon.png", url: "data:audio/mpeg;base64,", source: "linglan.wy" }
  ];
  state.route = "my";
  render();
  document.querySelector('[data-action="open-playlist"][data-id="liked"]').click();
})()`);
await sleep(60);
await evaluate(`document.querySelector('[data-action="playlist-search"]').click()`);
await sleep(80);
const playlistSearchOpened = await evaluate(`(() => { const input = document.querySelector('#playlist-search-input'); const box = document.querySelector('.playlist-search-box'); return { route: state.route, visible: Boolean(input), width: Math.round(box?.getBoundingClientRect().width || 0) }; })()`);
if (playlistSearchOpened.visible) {
  await evaluate(`(() => { const input = document.querySelector('#playlist-search-input'); input.value = "星光"; input.dispatchEvent(new Event("input", { bubbles: true })); })()`);
  await sleep(100);
}
const playlistSearchResult = await evaluate(`({ rows: document.querySelectorAll('.playlist-track-list .track-row').length, titles: [...document.querySelectorAll('.playlist-track-list .track-title')].map((node) => node.textContent.trim()) })`);
await screenshot("tests/ui-playlist-search-mobile.png");
await metrics(1536, 760, false);
const desktopPlaylistSearch = await evaluate(`(() => { const box = document.querySelector('.playlist-search-box')?.getBoundingClientRect(); const hero = document.querySelector('.playlist-hero')?.getBoundingClientRect(); return { width: Math.round(box?.width || 0), insideHero: Boolean(box && hero && box.left >= hero.left && box.right <= hero.right) }; })()`);
await screenshot("tests/ui-playlist-search-desktop.png");

const result = {
  playbackSwitch,
  recommendationSet,
  miniProgress,
  settingsScroll,
  desktopOverlay,
  desktopDiscoverOverlay,
  miniHasPrevious,
  previousTitle,
  mobileArtwork,
  coverAppearance,
  coverTouchHit,
  lyricsOpened,
  lyricSync,
  lyricsLayout,
  lyricsTouchHit,
  returnedToCover,
  lazyLyrics,
  horizontalPosition: { before: beforeJitter, after: afterJitter, stable: Math.abs(beforeJitter - afterJitter) <= 2 },
  returnedRoute,
  returnedPosition,
  playlistBackChain,
  playlistSearchOpened,
  playlistSearchResult,
  desktopPlaylistSearch
};
console.log(JSON.stringify(result, null, 2));
socket.close();

if (!desktopOverlay.clear) process.exitCode = 2;
if (!desktopDiscoverOverlay.clear) process.exitCode = 18;
if (playbackSwitch.current !== "new-track" || playbackSwitch.starts.length !== 1 || playbackSwitch.starts[0] !== "new" || playbackSwitch.pauses < 2) process.exitCode = 16;
if (recommendationSet.count !== 8 || recommendationSet.rendered !== 8 || recommendationSet.uniqueIds !== 8 || !recommendationSet.allPlayable) process.exitCode = 17;
if (miniProgress.value !== "25%" || Math.abs(miniProgress.lineWidth - miniProgress.playerWidth * .25) > 3) process.exitCode = 13;
if (settingsScroll.before < 20 || Math.abs(settingsScroll.before - settingsScroll.afterCheckbox) > 2 || Math.abs(settingsScroll.before - settingsScroll.afterButton) > 2) process.exitCode = 14;
if (!miniHasPrevious || previousTitle !== "界面测试歌曲1") process.exitCode = 3;
if (!mobileArtwork.fullyVisible) process.exitCode = 4;
if (!coverAppearance.square || coverAppearance.playBackground !== "rgba(0, 0, 0, 0)") process.exitCode = 8;
if (!lyricsOpened.visible || lyricsOpened.first !== "第一句歌词") process.exitCode = 9;
if (!lyricSync.supported || lyricSync.active !== "第二句歌词") process.exitCode = 10;
if (lyricsLayout.innerTopGap > 40 || lyricsLayout.activeCenterRatio > .44) process.exitCode = 15;
if (coverTouchHit.action !== "toggle-lyrics" || lyricsTouchHit.action !== "toggle-lyrics" || !returnedToCover) process.exitCode = 12;
if (lazyLyrics !== "按需加载歌词") process.exitCode = 11;
if (Math.abs(beforeJitter - afterJitter) > 2) process.exitCode = 5;
if (returnedRoute !== "discover") process.exitCode = 6;
if (returnedPosition.horizontal < 110) process.exitCode = 7;
if (playlistBackChain.afterPlayerBack !== "playlist" || playlistBackChain.final !== "my") process.exitCode = 19;
if (playlistSearchOpened.route !== "playlist" || !playlistSearchOpened.visible || playlistSearchOpened.width < 220 || playlistSearchResult.rows !== 2) process.exitCode = 20;
if (desktopPlaylistSearch.width < 500 || !desktopPlaylistSearch.insideHero) process.exitCode = 21;
