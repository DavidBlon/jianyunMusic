const $app = document.querySelector("#app");
const $audio = document.querySelector("#audio");
const $toast = document.querySelector("#toast");

const icon = (name, className = "") => {
  const paths = {
    discover: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5.5 9.5V21h13V9.5"/><path d="M9.5 21v-7h5v7"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
    user: '<circle cx="12" cy="8" r="3.5"/><path d="M5 21c.7-4 3-6 7-6s6.3 2 7 6"/>',
    back: '<path d="m15 18-6-6 6-6"/>',
    down: '<path d="m6 9 6 6 6-6"/>',
    tune: '<path d="M4 7h10M18 7h2M4 17h2M10 17h10M8 4v6M16 14v6"/>',
    heart: '<path d="M20.8 4.7a5.5 5.5 0 0 0-7.8 0L12 5.8l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.5a5.5 5.5 0 0 0 0-7.8Z"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    play: '<path d="m8 5 11 7-11 7Z"/>',
    pause: '<path d="M9 5v14M15 5v14"/>',
    prev: '<path d="M6 5v14M18 6l-8 6 8 6Z"/>',
    next: '<path d="M18 5v14M6 6l8 6-8 6Z"/>',
    more: '<circle cx="5" cy="12" r="1" class="fill"/><circle cx="12" cy="12" r="1" class="fill"/><circle cx="19" cy="12" r="1" class="fill"/>',
    chevron: '<path d="m9 18 6-6-6-6"/>',
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    list: '<path d="M8 6h13M8 12h13M8 18h13"/><circle cx="4" cy="6" r="1" class="fill"/><circle cx="4" cy="12" r="1" class="fill"/><circle cx="4" cy="18" r="1" class="fill"/>'
  };
  return `<svg class="icon ${className}" viewBox="0 0 24 24" aria-hidden="true">${paths[name] || ""}</svg>`;
};

const FALLBACK_ART = "./assets/app-icon.png";
const SOURCE_API = "https://source.shiqianjiang.cn";
const SOURCE_META = {
  "linglan.kg": { code: "kg", name: "酷狗", label: "酷狗音乐 v7" },
  "linglan.kw": { code: "kw", name: "酷我", label: "酷我音乐 v7" },
  "linglan.tx": { code: "tx", name: "QQ", label: "QQ音乐 v7" },
  "linglan.wy": { code: "wy", name: "网易云", label: "网易云音乐 v7" }
};
let discoveryPlaylists = [];
let discoveryTracks = [];
let keyValidated = false;
let lastKeyValidation = 0;
let playbackRequestId = 0;
let playbackAbortController = null;

const defaults = {
  route: "discover",
  searchQuery: "",
  dataVersion: 2,
  searchHistory: [],
  results: [],
  loading: false,
  liked: [],
  recent: [],
  playlists: [],
  queue: [],
  current: null,
  playing: false,
  returnRoute: "discover",
  playlistReturnRoute: "my",
  playlistSearchOpen: false,
  playlistSearchQuery: "",
  panel: null,
  theme: "deep",
  background: "",
  backgroundType: "image",
  backgroundGlobal: false,
  playerLayout: "disc",
  playerSurface: "artwork",
  playerEffect: "none",
  rhythmArtwork: true,
  sleepUntil: null,
  componentVisibility: { songInfo: true, artwork: true, progress: true, transport: true, extras: true, favorite: true },
  sourceId: "linglan.kg",
  maskedKey: "",
  key: ""
};

const persisted = (() => {
  try { return JSON.parse(localStorage.getItem("jianyun.web.state") || "{}"); } catch { return {}; }
})();
const launchParams = new URLSearchParams(location.search);
const launchRoute = ["discover", "search", "my", "playlist", "player"].includes(launchParams.get("route")) ? launchParams.get("route") : "discover";
const launchPanel = ["settings", "theme", "background", "source", "disclaimer", "appearance", "queue", "sleep"].includes(launchParams.get("panel")) ? launchParams.get("panel") : null;
const migrated = persisted.dataVersion === defaults.dataVersion ? persisted : { sourceId: persisted.sourceId || defaults.sourceId, theme: persisted.theme || defaults.theme, dataVersion: defaults.dataVersion };
const state = { ...defaults, ...migrated, key: "", route: launchRoute, results: [], loading: false, panel: launchPanel, playing: false };
state.componentVisibility = { ...defaults.componentVisibility, ...(persisted.componentVisibility || {}) };
state.playerSurface = state.playerSurface === "lyrics" ? "lyrics" : "artwork";
if (launchRoute === "playlist") state.playlistId = launchParams.get("list") || "liked";
if (launchRoute === "player" && !state.current) state.route = "discover";
try {
  const existing = JSON.parse(localStorage.getItem("jianyun.web.state") || "{}");
  if (existing && "key" in existing) {
    delete existing.key;
    localStorage.setItem("jianyun.web.state", JSON.stringify(existing));
  }
} catch {}

function persist() {
  const { route, results, loading, panel, playing, playlistSearchOpen, playlistSearchQuery, key, ...saved } = state;
  try { localStorage.setItem("jianyun.web.state", JSON.stringify(saved)); }
  catch { toast("浏览器存储空间不足，请选择更小的背景文件"); }
}

function esc(value = "") {
  return String(value).replace(/[&<>'"]/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[char]);
}

function art(value) { return esc(value || FALLBACK_ART); }
function fmt(seconds = 0) { const value = Math.max(0, Number(seconds || 0)); return `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2, "0")}`; }
function trackKey(track) { return `${track.source || "local"}#${track.remoteId || track.id}`; }
function isLiked(track) { return state.liked.some((item) => trackKey(item) === trackKey(track)); }
function sourceMeta(sourceId = state.sourceId) { return SOURCE_META[sourceId] || SOURCE_META["linglan.kg"]; }
function secureUrl(value = "") { return String(value || "").replace(/^http:/i, "https:").replace("{size}", "480"); }

function parseLrc(value = "") {
  const lines = [];
  for (const rawLine of String(value || "").split(/\r?\n/)) {
    const stamps = [...rawLine.matchAll(/\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]/g)];
    const text = rawLine.replace(/\[[^\]]+\]/g, "").trim();
    if (!text) continue;
    for (const stamp of stamps) {
      const fraction = String(stamp[3] || "0").padEnd(3, "0").slice(0, 3);
      lines.push({ time: Number(stamp[1]) * 60 + Number(stamp[2]) + Number(fraction) / 1000, text });
    }
  }
  return lines.sort((a, b) => a.time - b.time);
}

function trackLyrics(track = state.current) {
  return parseLrc(track?.lyric || track?.lyrics || track?.lrc || track?.rawLrc || "");
}

function renderLyrics(track) {
  if (track.lyricLoading) return '<div class="lyrics-panel"><div class="lyrics-empty">歌词加载中…</div></div>';
  const lines = trackLyrics(track);
  if (!lines.length) return '<div class="lyrics-panel"><div class="lyrics-empty">暂无歌词</div></div>';
  return `<div class="lyrics-panel" data-lyrics-key="${esc(trackKey(track))}"><div class="lyrics-list">${lines.map((line, index) => `<div class="lyric-line" data-time="${line.time}" data-index="${index}">${esc(line.text)}</div>`).join("")}</div></div>`;
}

function updateLyricsPosition(seconds = $audio.currentTime || 0) {
  const panel = document.querySelector(".lyrics-panel");
  if (!panel) return;
  const lines = [...panel.querySelectorAll(".lyric-line")];
  if (!lines.length) return;
  let active = 0;
  for (let index = 0; index < lines.length; index += 1) {
    if (Number(lines[index].dataset.time) <= Number(seconds) + .15) active = index;
    else break;
  }
  lines.forEach((line, index) => line.classList.toggle("active", index === active));
  const current = lines[active];
  const nextTop = current.offsetTop - panel.clientHeight / 2 + current.offsetHeight / 2;
  panel.scrollTo({ top: Math.max(0, nextTop), behavior: panel.dataset.ready ? "smooth" : "auto" });
  panel.dataset.ready = "1";
}

function jsonp(url, callbackParam = "callback", timeout = 14000) {
  return new Promise((resolve, reject) => {
    const callback = `jyjsonp${Date.now()}${Math.floor(Math.random() * 100000)}`.toLowerCase();
    const script = document.createElement("script");
    const timer = setTimeout(() => finish(new Error("在线服务响应超时")), timeout);
    const finish = (error, data) => {
      clearTimeout(timer);
      delete window[callback];
      script.remove();
      error ? reject(error) : resolve(data);
    };
    window[callback] = (data) => finish(null, data);
    script.onerror = () => finish(new Error("在线服务暂时无法连接"));
    const endpoint = new URL(url);
    endpoint.searchParams.set(callbackParam, callback);
    script.src = endpoint.href;
    document.head.append(script);
  });
}

async function fetchJson(url, options = {}) {
  const controller = new AbortController();
  const externalSignal = options.signal;
  const abortFromExternal = () => controller.abort();
  if (externalSignal?.aborted) controller.abort();
  else externalSignal?.addEventListener("abort", abortFromExternal, { once: true });
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(url, { ...options, signal: controller.signal });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || `请求失败（${response.status}）`);
    return body;
  } catch (error) {
    if (error.name === "AbortError") throw new Error("在线服务响应超时");
    throw error;
  } finally {
    clearTimeout(timer);
    externalSignal?.removeEventListener("abort", abortFromExternal);
  }
}

function decodeText(value = "") {
  const decoder = document.createElement("textarea");
  decoder.innerHTML = String(value);
  return decoder.value.replace(/\s+/g, " ").trim();
}

function normalizeKg(item) {
  const id = String(item.FileHash || item.filehash || item.hash || item.ID || item.id || "");
  return { id, remoteId: id, hash: id, songmid: id, source: "linglan.kg", sourceName: "酷狗", title: item.SongName || item.songname || item.OriSongName || item.filename?.split(" - ").slice(1).join(" - ") || "未知歌曲", artist: item.SingerName || item.singername || item.filename?.split(" - ")[0] || "未知歌手", album: item.AlbumName || item.album_name || "", duration: Number(item.Duration || item.duration || 0), artwork: secureUrl(item.Image || item.imgurl || item.trans_param?.union_cover) };
}

function normalizeKw(item) {
  const id = String(item.MUSICRID || item.musicrid || item.DC_TARGETID || item.rid || item.id || "").replace(/^MUSIC_/, "");
  const shortPic = item.web_albumpic_short || item.web_artistpic_short || item.pic || item.albumpic || "";
  const artwork = shortPic && !/^https?:/i.test(shortPic) ? `https://img4.kuwo.cn/star/albumcover/${shortPic}` : shortPic;
  return { id, remoteId: id, songmid: id, source: "linglan.kw", sourceName: "酷我", title: decodeText(item.SONGNAME || item.NAME || item.name || "未知歌曲"), artist: decodeText(item.ARTIST || item.artist || "未知歌手"), album: decodeText(item.ALBUM || item.album || ""), duration: Number(item.DURATION || item.duration || 0), artwork: secureUrl(artwork) };
}

function normalizeTx(item) {
  const id = String(item.songmid || item.media_mid || item.songid || item.id || "");
  const albumMid = item.albummid || item.album?.mid || "";
  return { id, remoteId: id, songmid: id, source: "linglan.tx", sourceName: "QQ", title: item.songname || item.title || item.name || "未知歌曲", artist: (item.singer || []).map((singer) => singer.name).filter(Boolean).join("、") || item.artist || "未知歌手", album: item.albumname || item.album?.name || "", duration: Number(item.interval || item.duration || 0), artwork: albumMid ? `https://y.gtimg.cn/music/photo_new/T002R500x500M000${albumMid}.jpg` : "" };
}

function normalizeWy(item) {
  if (item.title && item.url) {
    const match = String(item.url).match(/[?&]id=([^&]+)/);
    const id = decodeURIComponent(match?.[1] || item.id || "");
    return { id, remoteId: id, songmid: id, source: "linglan.wy", sourceName: "网易云", title: item.title || "未知歌曲", artist: item.author || "未知歌手", album: item.album || "", duration: Number(item.duration || 0), artwork: secureUrl(item.pic), lyricUrl: secureUrl(item.lrc) };
  }
  const id = String(item.id || "");
  return { id, remoteId: id, songmid: id, source: "linglan.wy", sourceName: "网易云", title: item.name || item.title || "未知歌曲", artist: (item.artists || item.ar || []).map((artist) => artist.name).filter(Boolean).join("、") || "未知歌手", album: item.album?.name || item.al?.name || "", duration: Number(item.duration || item.dt || 0) / (Number(item.duration || item.dt || 0) > 10000 ? 1000 : 1), artwork: secureUrl(item.album?.picUrl || item.al?.picUrl) };
}

async function searchSource(query, sourceId = state.sourceId) {
  const encoded = encodeURIComponent(query);
  if (sourceId === "linglan.kg") {
    const body = await jsonp(`https://songsearch.kugou.com/song_search_v2?keyword=${encoded}&page=1&pagesize=30&platform=WebFilter&filter=2`, "callback");
    return (body?.data?.lists || []).map(normalizeKg).filter((item) => item.id);
  }
  if (sourceId === "linglan.kw") {
    const endpoint = `https://search.kuwo.cn/r.s?all=${encoded}&ft=music&itemset=web_2013&client=kt&pn=0&rn=30&rformat=json&encoding=utf8`;
    const body = await jsonp(endpoint, "callback");
    return (body.abslist || []).map(normalizeKw).filter((item) => item.id);
  }
  if (sourceId === "linglan.tx") {
    const body = await jsonp(`https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=jsonp&p=1&n=30&w=${encoded}`, "jsonpCallback");
    return (body?.data?.song?.list || []).map(normalizeTx).filter((item) => item.id);
  }
  const body = await fetchJson(`https://met.liiiu.cn/meting/api?server=netease&type=search&id=${encoded}`);
  return (Array.isArray(body) ? body : []).map(normalizeWy).filter((item) => item.id);
}

function normalizePlaylist(item, source = state.sourceId) {
  return { id: String(item.id || item.specialid || item.dissid || ""), name: item.name || item.title || item.specialname || item.dissname || "推荐歌单", artwork: secureUrl(item.artwork || item.coverImgUrl || item.coverImg || item.img || item.imgurl), artist: item.artist || item.nickname || item.creator?.nickname || item.creator?.name || "", source };
}

const SMART_PLAYLIST_NAMES = ["每日精选", "流行热歌", "经典回响", "华语新声", "治愈时刻", "夜晚聆听", "轻松节奏", "私藏旋律"];

function buildSmartPlaylists(tracks, source = state.sourceId, count = 8, prefix = "smart") {
  const available = (Array.isArray(tracks) ? tracks : []).filter(Boolean);
  if (!available.length) return [];
  const listSize = Math.min(10, available.length);
  return Array.from({ length: Math.min(count, SMART_PLAYLIST_NAMES.length) }, (_, index) => {
    const start = index * 3 % available.length;
    const playlistTracks = Array.from({ length: listSize }, (__, offset) => available[(start + offset) % available.length]);
    return { id: `${prefix}-${index}`, name: SMART_PLAYLIST_NAMES[index], artwork: available[start]?.artwork, source, tracks: playlistTracks };
  });
}

async function loadRecommendedPlaylists() {
  if (state.sourceId === "linglan.kw") {
    const body = await fetchJson("https://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList?loginUid=0&loginSid=0&appUid=76039576&pn=0&rn=18&order=hot");
    return (body?.data?.data || []).map((item) => normalizePlaylist(item));
  }
  if (state.sourceId === "linglan.tx") {
    const body = await jsonp("https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg?inCharset=utf8&outCharset=utf-8&sortId=5&categoryId=10000000&sin=0&ein=17&format=jsonp", "jsonpCallback");
    return (body?.data?.list || []).map((item) => normalizePlaylist(item));
  }
  if (state.sourceId === "linglan.wy") {
    const tracks = await searchSource("热门歌单", "linglan.wy");
    return buildSmartPlaylists(tracks, "linglan.wy", 8, "wy-smart");
  }
  return [];
}

async function loadPlaylistTracks(playlist) {
  const id = encodeURIComponent(playlist.id);
  if (playlist.source === "linglan.kg") {
    const body = await fetchJson(`https://mobilecdn.kugou.com/api/v3/special/song?version=9108&specialid=${id}&page=1&pagesize=100&plat=0&area_code=1`);
    return (body?.data?.info || []).map(normalizeKg).filter((item) => item.id);
  }
  if (playlist.source === "linglan.kw") {
    const body = await fetchJson(`https://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=${id}&pn=0&rn=100&encode=utf8&keyset=pl2012&vipver=MUSIC_8.0.3.1&newver=1`);
    return (body?.musiclist || []).map(normalizeKw).filter((item) => item.id);
  }
  if (playlist.source === "linglan.wy") {
    const body = await fetchJson(`https://music.163.com/api/v3/playlist/detail?id=${id}&n=100`);
    return (body?.playlist?.tracks || []).map(normalizeWy).filter((item) => item.id);
  }
  const body = await jsonp(`https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&disstid=${id}&format=jsonp`, "jsonpCallback");
  return (body?.cdlist?.[0]?.songlist || []).map(normalizeTx).filter((item) => item.id);
}

async function fetchText(url) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(url, { signal: controller.signal });
    if (!response.ok) throw new Error(`歌词请求失败（${response.status}）`);
    return response.text();
  } finally { clearTimeout(timer); }
}

async function loadTrackLyric(track) {
  if (!track || track.lyricLoaded || track.lyricLoading) return;
  track.lyricLoading = true;
  if (state.route === "player" && state.playerSurface === "lyrics") render();
  try {
    let lyric = track.lyric || track.lyrics || track.lrc || track.rawLrc || "";
    if (!lyric && track.lyricUrl) lyric = await fetchText(secureUrl(track.lyricUrl));
    if (!lyric) {
      const query = encodeURIComponent(`${track.title || ""} ${track.artist || ""}`.trim());
      const results = await fetchJson(`https://met.liiiu.cn/meting/api?server=netease&type=search&id=${query}`);
      const candidates = Array.isArray(results) ? results : [];
      const normalizedTitle = String(track.title || "").replace(/[（(].*?[）)]/g, "").trim().toLowerCase();
      const normalizedArtist = String(track.artist || "").split(/[、,/]/)[0].trim().toLowerCase();
      const titleMatches = candidates.filter((item) => String(item.title || "").replace(/[（(].*?[）)]/g, "").trim().toLowerCase() === normalizedTitle);
      const candidate = titleMatches.find((item) => String(item.author || "").toLowerCase().includes(normalizedArtist)) || titleMatches[0];
      if (candidate?.lrc) lyric = await fetchText(secureUrl(candidate.lrc));
    }
    track.lyric = String(lyric || "");
  } catch {
    track.lyric = "";
  }
  track.lyricLoading = false;
  track.lyricLoaded = true;
  if (state.current && trackKey(track) === trackKey(state.current) && state.route === "player" && state.playerSurface === "lyrics") {
    render();
    updateLyricsPosition();
  }
}

async function validateSourceKey(key) {
  if (!key || key.trim().length < 8) throw new Error("卡密内容不完整");
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  const endpoint = new URL(`${SOURCE_API}/api/music/url`);
  endpoint.searchParams.set("source", "kg");
  endpoint.searchParams.set("quality", "128k");
  try {
    const response = await fetch(endpoint, {
      headers: { "X-API-Key": key.trim(), "Content-Type": "application/json" },
      signal: controller.signal
    });
    const body = await response.json().catch(() => ({}));
    const code = Number(body.code || response.status);
    const message = String(body.message || "");
    if ((code === 400 || response.status === 400) && !message.includes("密钥")) return true;
    if (code === 200 || response.ok) return true;
    if ([401, 403].includes(code) || [401, 403].includes(response.status)) throw new Error(message || "卡密无效或已失效");
    if (code === 429 || response.status === 429) throw new Error("验证请求过于频繁，请稍后再试");
    throw new Error(message || "卡密验证服务暂时不可用");
  } catch (error) {
    if (error.name === "AbortError") throw new Error("卡密验证服务响应超时");
    throw error;
  } finally { clearTimeout(timer); }
}

async function ensureConnected() {
  if (!state.key) throw new Error("请先到“我的 → 设置”连接在线音乐来源");
  if (keyValidated && Date.now() - lastKeyValidation < 10 * 60_000) return;
  try {
    await validateSourceKey(state.key);
    keyValidated = true;
    lastKeyValidation = Date.now();
  } catch (error) {
    state.key = "";
    state.maskedKey = "";
    discoveryPlaylists = [];
    discoveryTracks = [];
    persist();
    throw error;
  }
}
function cardArtwork(item) {
  if (item.sprite) return `<span class="card-art sprite-art" style="background-image:url('${art(item.artwork)}');background-position:${item.sprite}" role="img" aria-label="${esc(item.name || item.title)}"></span>`;
  return `<img class="card-art" src="${art(item.artwork)}" alt="${esc(item.name || item.title || "")}" onerror="this.src='${FALLBACK_ART}'">`;
}

let toastTimer;
function toast(message) {
  $toast.textContent = message;
  $toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $toast.classList.remove("show"), 2400);
}

function nav() {
  const items = [["discover", "发现", "discover"], ["search", "搜索", "search"], ["my", "我的", "user"]];
  return `<nav class="bottom-nav" aria-label="主导航">${items.map(([route, label, glyph]) => `<button class="nav-button ${state.route === route ? "active" : ""}" data-action="route" data-route="${route}">${icon(glyph)}<span>${label}</span></button>`).join("")}</nav>`;
}

function miniPlayer() {
  if (!state.current || state.route === "player") return "";
  const duration = Number($audio.duration || state.current.duration || 0);
  const progress = duration > 0 ? Math.min(100, Math.max(0, Number($audio.currentTime || 0) / duration * 100)) : 0;
  return `<div class="mini-player" style="--mini-progress:${progress}%"><img src="${art(state.current.artwork)}" alt="" onerror="this.src='${FALLBACK_ART}'"><div class="mini-copy" data-action="route" data-route="player"><strong>${esc(state.current.title)}</strong><span>${esc(state.current.artist)}</span></div><button class="icon-button mini-previous" data-action="prev" aria-label="上一首">${icon("prev")}</button><button class="icon-button" data-action="play-toggle" aria-label="${state.playing ? "暂停" : "播放"}">${icon(state.playing ? "pause" : "play")}</button><button class="icon-button" data-action="next" aria-label="下一首">${icon("next")}</button></div>`;
}

function backgroundMedia() {
  const visible = state.background && (state.route === "player" || state.backgroundGlobal);
  return visible && state.backgroundType === "video" ? `<video class="custom-background-media" src="${art(state.background)}" autoplay muted loop playsinline></video>` : "";
}

function shell(content, options = {}) {
  return `${backgroundMedia()}${content}${options.nav === false ? "" : miniPlayer() + nav()}${renderPanel()}`;
}

function carousel(items, content, name) {
  if (!items.length) return '<div class="empty-inline">当前音源暂时没有可用内容</div>';
  return `<div class="carousel-shell"><button class="carousel-arrow previous" data-action="scroll-carousel" data-direction="-1" aria-label="向左滑动">${icon("back")}</button><div class="horizontal-scroller" data-carousel="${name}">${items.map(content).join("")}</div><button class="carousel-arrow next" data-action="scroll-carousel" data-direction="1" aria-label="向右滑动">${icon("chevron")}</button></div>`;
}

function renderDiscover() {
  const sourceName = sourceMeta().name;
  const locked = `<div class="connection-empty"><div class="connection-icon">${icon("tune")}</div><strong>尚未连接在线音乐来源</strong><p>绑定并验证卡密后显示推荐内容</p><button class="primary-button" data-action="open-source-settings">前往连接</button></div>`;
  const loading = `<div class="loading"><div class="spinner"></div></div>`;
  const playlists = carousel(discoveryPlaylists, (item) => `<article class="media-card" data-action="open-discovery-playlist" data-id="${item.id}">${cardArtwork(item)}<h3>${esc(item.name)}</h3></article>`, "playlists");
  const tracks = carousel(discoveryTracks, (item, index) => `<article class="media-card" data-action="play-track" data-list="discovery" data-index="${index}">${cardArtwork(item)}<h3>${esc(item.title)}</h3><p>${esc(item.artist)}</p></article>`, "tracks");
  return shell(`<main class="screen"><div class="content-frame"><div class="status-space"></div><h1 class="page-title">发现</h1>${!state.key ? locked : state.discoverLoading ? loading : `<section><div class="section-head"><h2 class="section-title">推荐歌单</h2><span class="section-source">来自${sourceName}</span></div>${playlists}</section><section><div class="section-head"><h2 class="section-title">推荐歌曲</h2><span class="section-source">来自${sourceName}</span></div>${tracks}</section>`}</div></main>`);
}

function resultRow(track, index, list = "results") {
  const inPlaylist = list.startsWith("playlist:");
  return `<article class="track-row" data-action="play-track" data-list="${list}" data-index="${index}"><img src="${art(track.artwork)}" alt="" onerror="this.src='${FALLBACK_ART}'"><div class="track-main"><div class="track-title">${esc(track.title)}</div><div class="track-sub">${esc(track.sourceName ? `${track.sourceName} · ${track.artist}` : track.artist)}</div></div><div class="track-actions"><span class="track-duration">${track.duration ? fmt(track.duration) : ""}</span><button class="icon-button add-track" data-action="${inPlaylist ? "track-menu" : "add-track"}" data-list="${list}" data-index="${index}" aria-label="${inPlaylist ? "歌曲操作" : "添加到歌单"}">${icon(inPlaylist ? "more" : "plus")}</button></div></article>`;
}

function renderSearch() {
  const body = !state.key ? `<div class="connection-empty search-locked"><strong>尚未连接在线音乐来源</strong><p>请先绑定并验证卡密，再搜索在线歌曲</p><button class="primary-button" data-action="open-source-settings">前往连接</button></div>` : state.loading ? `<div class="loading"><div class="spinner"></div></div>` : state.results.length
    ? `<div class="source-caption">在线来源：${sourceMeta().name}</div><div class="track-list">${state.results.map((track, index) => resultRow(track, index)).join("")}</div>`
    : `<div class="history-head"><h2 class="section-title">搜索历史</h2>${state.searchHistory.length ? '<button class="clear-button" data-action="clear-history">清空</button>' : ""}</div><div class="history-list">${state.searchHistory.map((item, index) => `<button class="history-item" data-action="history-search" data-query="${esc(item)}"><span>${esc(item)}</span><span class="x" data-action="remove-history" data-index="${index}">×</span></button>`).join("")}</div>`;
  return shell(`<main class="screen search-screen"><div class="content-frame"><form class="search-box ${state.key ? "" : "disabled"}" id="search-form">${icon("search")}<input id="search-input" value="${esc(state.searchQuery)}" placeholder="${state.key ? "搜索歌曲、歌手、专辑" : "连接在线音源后可搜索"}" autocomplete="off" ${state.key ? "" : "disabled"}>${state.searchQuery ? '<button type="button" class="clear-button" data-action="clear-search">清空</button>' : ""}</form>${body}</div></main>`);
}

function renderMy() {
  const tiles = [
    { id: "liked", name: "我喜欢的音乐", count: state.liked.length, artwork: state.liked[0]?.artwork },
    { id: "recent", name: "最近播放", count: state.recent.length, artwork: state.recent[0]?.artwork },
    ...state.playlists.filter((list) => list.id !== "discover").map((list) => ({ ...list, count: list.tracks.length, artwork: list.tracks[0]?.artwork }))
  ];
  return shell(`<main class="screen"><div class="content-frame"><div class="status-space"></div><h1 class="page-title">我的音乐</h1><button class="glass-row settings-entry" data-action="panel" data-panel="settings"><strong>设置</strong><span>主题、背景与音源</span>${icon("chevron")}</button><div class="section-head library-title"><h2 class="section-title">我的歌单</h2><button class="icon-button" style="margin-left:auto" data-action="new-playlist" aria-label="新建歌单">${icon("plus")}</button></div><div>${tiles.map((item) => `<article class="playlist-tile" data-action="open-playlist" data-id="${item.id}"><img src="${art(item.artwork)}" alt="" onerror="this.src='${FALLBACK_ART}'"><div><strong>${esc(item.name)}</strong><span>${item.count} 首</span></div>${item.id.startsWith("user-") ? `<button class="icon-button playlist-more" data-action="playlist-menu" data-id="${item.id}" aria-label="歌单操作">${icon("more")}</button>` : ""}</article>`).join("")}</div></div></main>`);
}

function getPlaylist(id) {
  if (id === "liked") return { id, name: "我喜欢的音乐", type: "歌单", tracks: state.liked };
  if (id === "recent") return { id, name: "最近播放", type: "歌单", tracks: state.recent };
  return state.playlists.find((item) => item.id === id) || { id, name: "歌单", type: "歌单", tracks: [] };
}

function renderPlaylist() {
  const list = getPlaylist(state.playlistId || "liked");
  const heroArt = list.tracks[0]?.artwork || FALLBACK_ART;
  const query = String(state.playlistSearchQuery || "").trim().toLocaleLowerCase();
  const visibleTracks = list.tracks.map((track, index) => ({ track, index })).filter(({ track }) => !query || [track.title, track.artist, track.album, track.sourceName].filter(Boolean).join(" ").toLocaleLowerCase().includes(query));
  const searchControl = state.playlistSearchOpen
    ? `<form class="playlist-search-box" id="playlist-search-form">${icon("search")}<input id="playlist-search-input" value="${esc(state.playlistSearchQuery)}" placeholder="搜索歌单内歌曲" autocomplete="off" aria-label="搜索歌单内歌曲" autofocus>${state.playlistSearchQuery ? '<button type="button" class="playlist-search-clear" data-action="clear-playlist-search" aria-label="清空搜索">×</button>' : ""}<button type="button" class="playlist-search-cancel" data-action="close-playlist-search">取消</button></form>`
    : `<button class="icon-button" data-action="playlist-search" aria-label="搜索歌单">${icon("search")}</button>`;
  const countText = query ? `${visibleTracks.length} / ${list.tracks.length} 首歌` : `${list.tracks.length} 首歌`;
  const rows = visibleTracks.length
    ? visibleTracks.map(({ track, index }) => resultRow(track, index, `playlist:${list.id}`)).join("")
    : `<div class="playlist-search-empty">${query ? `没有找到“${esc(state.playlistSearchQuery.trim())}”相关歌曲` : "暂无歌曲"}</div>`;
  return shell(`<main class="screen"><header class="playlist-hero" style="background-image:url('${art(heroArt)}')"><div class="hero-actions"><button class="icon-button" data-action="back" aria-label="返回">${icon("back")}</button>${searchControl}</div><div><div class="playlist-type">${esc(list.type || "歌单")}</div><h1 class="playlist-name">${esc(list.name)}</h1><div class="playlist-count">${countText}</div></div></header><button class="primary-button play-all" data-action="play-all" data-id="${list.id}">${icon("play", "fill")} 播放全部</button><div class="track-list playlist-track-list">${rows}</div></main>`);
}

function renderPlayer() {
  const track = state.current;
  if (!track) { state.route = "discover"; return renderDiscover(); }
  const current = Math.floor($audio.currentTime || 0);
  const duration = Math.floor($audio.duration || track.duration || 0);
  const visible = state.componentVisibility;
  const artwork = state.playerLayout === "cover"
    ? `<div class="cover-stage" data-action="toggle-lyrics" role="button" aria-label="查看歌词"><img class="cover-large" src="${art(track.artwork)}" alt="${esc(track.title)}" onerror="this.src='${FALLBACK_ART}'"></div>`
    : `<div class="disc-stage" data-action="toggle-lyrics" role="button" aria-label="查看歌词"><div class="disc ${state.playing ? "playing" : ""} ${state.rhythmArtwork ? "rhythm" : ""}"><img src="${art(track.artwork)}" alt="${esc(track.title)}" onerror="this.src='${FALLBACK_ART}'"></div></div>`;
  const playerVisual = state.playerSurface === "lyrics" ? `<div class="player-lyrics-surface" data-action="toggle-lyrics" role="button" aria-label="返回封面">${renderLyrics(track)}</div>` : artwork;
  return shell(`<main class="player-screen effect-${state.playerEffect} ${state.playerSurface === "lyrics" ? "showing-lyrics" : ""}"><header class="player-mobile-top"><div class="player-top-side"><button class="icon-button" data-action="back">${icon("down")}</button><button class="icon-button" data-action="panel" data-panel="appearance">${icon("tune")}</button></div>${visible.songInfo ? `<div class="player-heading"><strong>${esc(track.title)}</strong><span>${esc(track.artist)}</span></div>` : "<div></div>"}<div class="player-top-side right">${visible.favorite ? `<button class="icon-button" data-action="like-current" aria-label="收藏" style="color:${isLiked(track) ? "#ff6b68" : "inherit"}">${icon("heart", isLiked(track) ? "fill" : "")}</button>` : ""}<button class="icon-button" data-action="add-current" aria-label="添加到歌单">${icon("plus")}</button></div></header><div class="player-layout">${visible.artwork ? `<section class="player-artwork">${playerVisual}</section>` : ""}<section class="player-detail"><div class="player-desktop-actions"><div class="player-top-side"><button class="icon-button" data-action="back" aria-label="返回">${icon("down")}</button><button class="icon-button" data-action="panel" data-panel="appearance" aria-label="个性化">${icon("tune")}</button></div><div class="player-top-side right">${visible.favorite ? `<button class="icon-button" data-action="like-current" aria-label="收藏" style="color:${isLiked(track) ? "#ff6b68" : "inherit"}">${icon("heart", isLiked(track) ? "fill" : "")}</button>` : ""}<button class="icon-button" data-action="add-current" aria-label="添加到歌单">${icon("plus")}</button></div></div>${visible.songInfo ? `<div class="player-copy"><h1>${esc(track.title)}</h1><p>${esc(track.artist)}</p></div>` : ""}<div class="player-controls">${visible.progress ? `<div class="progress-wrap"><span id="current-time">${fmt(current)}</span><input id="progress" class="progress" type="range" min="0" max="${Math.max(duration, 1)}" value="${current}"><span id="duration-time">${fmt(duration)}</span></div>` : ""}${visible.transport ? `<div class="transport"><button class="icon-button" data-action="prev">${icon("prev")}</button><button class="icon-button main-play" data-action="play-toggle">${icon(state.playing ? "pause" : "play")}</button><button class="icon-button" data-action="next">${icon("next")}</button></div>` : ""}${visible.extras ? `<div class="player-extras"><button data-action="panel" data-panel="queue">${icon("list")}<span>播放列表</span></button><button data-action="panel" data-panel="sleep"><span class="clock-icon"></span><span>定时关闭</span></button></div>` : ""}</div></section></div></main>`, { nav: false });
}

function renderPanel() {
  if (!state.panel) return "";
  if (state.panel === "settings") return `<div class="sheet-backdrop" data-action="close-panel"><section class="sheet" data-stop><div class="sheet-handle"></div><h2>设置</h2>${[["background","背景设置",state.background ? "已选择" : "跟随主题"],["theme","主题设置",themeName()],["source","在线音乐来源","连接聆澜 · 单选来源"],["disclaimer","免责声明","第三方服务与推广说明"]].map(([panel,label,value]) => `<div class="setting-row" data-action="panel" data-panel="${panel}"><strong>${label}</strong><span>${value}</span>${icon("chevron")}</div>`).join("")}</section></div>`;
  if (state.panel === "theme") return `<div class="sheet-backdrop" data-action="close-panel"><section class="sheet" data-stop><div class="sheet-handle"></div><h2>主题设置</h2><div class="choice-list">${[["green","翡翠青"],["deep","深海蓝"],["purple","暮光紫"],["orange","琥珀橙"],["red","酒红玫瑰"]].map(([theme,label]) => `<button class="choice-row" data-action="theme" data-theme="${theme}"><span class="theme-swatch theme-${theme}"></span><strong>${label}</strong>${state.theme === theme ? '<span class="selected-label">当前</span>' : ""}</button>`).join("")}</div></section></div>`;
  if (state.panel === "background") return `<div class="sheet-backdrop" data-action="close-panel"><section class="sheet" data-stop><div class="sheet-handle"></div><h2>背景设置</h2><p class="muted">选择照片或视频作为播放页背景，也可以手动扩展到整个应用。</p><input id="background-file" type="file" accept="image/*,video/*" class="hidden"><div class="background-card"><div><strong>${state.background ? (state.backgroundType === "video" ? "静音循环视频" : "相册照片") : "尚未选择背景"}</strong><span>${state.background ? "已选择，刷新前继续使用" : "支持照片与视频"}</span></div><button class="primary-button" data-action="choose-background">${state.background ? "更换照片或视频" : "从相册选择"}</button>${state.background ? '<button class="secondary-button" data-action="clear-background">移除自定义背景</button>' : ""}</div><label class="toggle-row ${state.background ? "" : "disabled"}"><div><strong>应用到全局背景</strong><span>${state.background ? (state.backgroundGlobal ? "发现、搜索和我的页面都会使用" : "关闭时仅应用到播放页") : "选择背景后可开启"}</span></div><input type="checkbox" name="background-global" ${state.backgroundGlobal ? "checked" : ""} ${state.background ? "" : "disabled"}></label></section></div>`;
  if (state.panel === "source") return `<div class="sheet-backdrop" data-action="close-panel"><section class="sheet source-sheet" data-stop><div class="sheet-handle"></div><h2>在线音乐来源</h2><a href="https://sumnera.shop.shiqianjiang.cn/" target="_blank" rel="noopener"><button class="primary-button">前往购买“聆澜音源”</button></a>${state.maskedKey ? `<p>当前密钥：${esc(state.maskedKey)}（仅显示末四位）</p><p>需要更换时直接粘贴新卡密，验证通过后才会启用。</p>` : '<p>粘贴购买后获得的卡密，验证通过后选择在线来源。未连接时不会请求或显示在线音乐数据。</p>'}<div class="key-editor"><input id="source-key" type="password" placeholder="输入新卡密"><button class="secondary-button" data-action="paste-key">快速粘贴</button></div><button class="secondary-button" style="width:100%" data-action="validate-key">验证并连接</button><p>${state.maskedKey ? "已连接 · 四个音源均可切换" : "未连接"}</p>${Object.entries(SOURCE_META).map(([id,meta]) => `<label class="source-option ${state.key ? "" : "disabled"}"><input type="radio" name="source" value="${id}" ${state.sourceId === id ? "checked" : ""} ${state.key ? "" : "disabled"}><span>${meta.label}</span></label>`).join("")}</section></div>`;
  if (state.panel === "disclaimer") return `<div class="sheet-backdrop" data-action="close-panel"><section class="sheet" data-stop><div class="sheet-handle"></div><h2>免责声明</h2><p>本软件用于个人学习与音乐管理。在线搜索、播放及第三方音源由相应服务商独立提供，稳定性、版权限制与售后责任以服务商说明为准。</p><p>用户可自行决定是否购买和配置第三方音源卡密。网页不会预置开发者个人卡密，只有用户主动输入后才会保存到当前浏览器。</p><p>部分歌曲可能因版权、会员、地区或接口限制无法播放。</p></section></div>`;
  if (state.panel === "appearance") return `<div class="sheet-backdrop" data-action="close-panel"><section class="sheet appearance-sheet" data-stop><div class="sheet-handle"></div><h2>播放页个性化</h2><h3>界面组件</h3>${[["songInfo","歌曲信息"],["artwork","封面"],["progress","播放进度"],["transport","播放控制"],["extras","更多功能"],["favorite","收藏按钮"]].map(([key,label]) => `<label class="toggle-row"><strong>${label}</strong><input type="checkbox" name="player-component" value="${key}" ${state.componentVisibility[key] ? "checked" : ""}></label>`).join("")}<div class="button-pair"><button class="primary-button" data-action="immersive">一键沉浸</button><button class="secondary-button" data-action="reset-components">恢复默认</button></div><h3>封面版式</h3><div class="button-pair">${[["disc","唱片"],["cover","大封面"]].map(([value,label]) => `<button class="secondary-button ${state.playerLayout === value ? "active" : ""}" data-action="player-layout" data-value="${value}">${label}</button>`).join("")}</div><label class="toggle-row"><div><strong>节奏律动</strong><span>封面旋转并保留取色光晕</span></div><input type="checkbox" name="rhythm-artwork" ${state.rhythmArtwork ? "checked" : ""}></label><h3>氛围动效</h3><div class="effect-grid">${[["none","关闭动效"],["snow","飘雪"],["stardust","星尘"],["rain","雨幕"]].map(([value,label]) => `<button class="secondary-button ${state.playerEffect === value ? "active" : ""}" data-action="player-effect" data-value="${value}">${label}</button>`).join("")}</div><div class="setting-row" data-action="panel" data-panel="background"><strong>自定义背景</strong><span>${state.background ? "已选择" : "跟随主题"}</span>${icon("chevron")}</div></section></div>`;
  if (state.panel === "queue") { const tracks = state.queuePanelTab === "history" ? state.recent : state.queue; return `<div class="sheet-backdrop" data-action="close-panel"><section class="sheet queue-sheet" data-stop><div class="sheet-handle"></div><div class="sheet-tabs"><button class="${state.queuePanelTab !== "history" ? "active" : ""}" data-action="queue-tab" data-value="queue">播放列表 ${state.queue.length}</button><button class="${state.queuePanelTab === "history" ? "active" : ""}" data-action="queue-tab" data-value="history">播放历史</button>${state.queuePanelTab !== "history" && state.queue.length > 1 ? '<button data-action="clear-queue">清空</button>' : ""}</div><div>${tracks.length ? tracks.map((track,index) => `<button class="queue-row ${state.current && trackKey(track) === trackKey(state.current) ? "current" : ""}" data-action="queue-play" data-index="${index}"><span><strong>${esc(track.title)}</strong><small>${esc(track.artist)}</small></span>${state.queuePanelTab !== "history" && state.current && trackKey(track) !== trackKey(state.current) ? `<i data-action="remove-queue" data-index="${index}">移除</i>` : ""}</button>`).join("") : '<div class="loading dim">暂无记录</div>'}</div></section></div>`; }
  if (state.panel === "sleep") { const remaining = state.sleepUntil ? Math.max(0, Math.ceil((state.sleepUntil - Date.now()) / 60000)) : null; return `<div class="sheet-backdrop" data-action="close-panel"><section class="sheet" data-stop><div class="sheet-handle"></div><h2>睡眠定时</h2><div class="effect-grid">${[15,30,60].map((minutes) => `<button class="secondary-button" data-action="set-sleep" data-minutes="${minutes}">${minutes}分</button>`).join("")}</div>${remaining ? `<p class="muted">约 ${remaining} 分钟后停止播放</p><button class="secondary-button full" data-action="sleep-off">关闭定时</button>` : ""}</section></div>`; }
  if (state.panel === "track-menu") return `<div class="sheet-backdrop" data-action="close-panel"><section class="sheet" data-stop><div class="sheet-handle"></div><h2>歌曲操作</h2><div class="setting-row" data-action="open-picker-from-menu"><strong>添加到歌单</strong>${icon("chevron")}</div><div class="setting-row danger-row" data-action="remove-track"><strong>从当前歌单中移除</strong></div></section></div>`;
  if (state.panel === "playlist-menu") return `<div class="sheet-backdrop" data-action="close-panel"><section class="sheet" data-stop><div class="sheet-handle"></div><h2>歌单操作</h2><div class="setting-row danger-row" data-action="confirm-playlist-delete"><strong>删除歌单</strong></div></section></div>`;
  if (state.panel === "confirm-delete") { const list = state.playlists.find((item) => item.id === state.pendingPlaylistId); return `<div class="modal"><section class="dialog"><h2>删除歌单</h2><p>确定删除“${esc(list?.name || "该歌单")}”吗？歌单内的歌曲不会从收藏中删除。</p><div class="dialog-actions"><button class="secondary-button" data-action="close-panel">取消</button><button class="primary-button danger-button" data-action="delete-playlist">删除</button></div></section></div>`; }
  if (state.panel === "playlist-picker") return `<div class="sheet-backdrop" data-action="close-panel"><section class="sheet" data-stop><div class="sheet-handle"></div><h2>添加到歌单</h2><div class="setting-row" data-action="new-playlist"><strong>新建歌单</strong><span>＋</span></div>${state.playlists.map((list) => `<div class="setting-row" data-action="select-playlist" data-id="${list.id}"><strong>${esc(list.name)}</strong><span>${list.tracks.length} 首</span>${icon("chevron")}</div>`).join("")}</section></div>`;
  if (state.panel === "new-playlist") return `<div class="modal"><section class="dialog"><h2>新建歌单</h2><input id="playlist-name" placeholder="输入歌单名称" maxlength="30" autofocus><div class="dialog-actions"><button class="secondary-button" data-action="close-panel">取消</button><button class="primary-button" style="width:auto" data-action="create-playlist">创建</button></div></section></div>`;
  return "";
}

function themeName() { return ({ deep: "深海蓝", green: "翡翠青", purple: "暮光紫", orange: "琥珀橙", red: "酒红玫瑰" })[state.theme] || "深海蓝"; }
function applyTheme() {
  const colors = { deep: ["#0b1119", "#111a27", "#78a9f8"], green: ["#071313", "#17342a", "#72d69b"], purple: ["#130d1a", "#2b1d38", "#b29aef"], orange: ["#160f0b", "#352118", "#e7a566"], red: ["#170d11", "#351923", "#e27b8b"] }[state.theme] || ["#0b1119", "#111a27", "#78a9f8"];
  document.documentElement.style.setProperty("--bg", colors[0]);
  document.documentElement.style.setProperty("--bg-2", colors[1]);
  document.documentElement.style.setProperty("--accent", colors[2]);
  const showImage = state.background && state.backgroundType === "image" && (state.route === "player" || state.backgroundGlobal);
  document.documentElement.style.setProperty("--bg-image", showImage ? `url("${state.background}")` : "none");
}

const carouselScrollPositions = new Map();
const routeScrollPositions = new Map();

function render() {
  const renderedRoute = $app.dataset.renderedRoute;
  const renderedPanel = $app.dataset.renderedPanel || "";
  const currentScreen = $app.querySelector(".screen");
  const currentSheet = $app.querySelector(".sheet");
  const sheetScrollTop = renderedPanel && renderedPanel === (state.panel || "") && currentSheet ? currentSheet.scrollTop : null;
  if (renderedRoute && currentScreen) routeScrollPositions.set(renderedRoute, currentScreen.scrollTop);
  for (const scroller of $app.querySelectorAll(".horizontal-scroller[data-carousel]")) carouselScrollPositions.set(scroller.dataset.carousel, scroller.scrollLeft);
  applyTheme();
  $app.innerHTML = ({ discover: renderDiscover, search: renderSearch, my: renderMy, playlist: renderPlaylist, player: renderPlayer })[state.route]?.() || renderDiscover();
  $app.dataset.renderedRoute = state.route;
  $app.dataset.renderedPanel = state.panel || "";
  $app.classList.toggle("has-mini-player", Boolean(state.current && state.route !== "player"));
  const nextScreen = $app.querySelector(".screen");
  if (nextScreen) nextScreen.scrollTop = routeScrollPositions.get(state.route) || 0;
  const nextSheet = $app.querySelector(".sheet");
  if (nextSheet && sheetScrollTop !== null) nextSheet.scrollTop = sheetScrollTop;
  for (const [name, scrollLeft] of carouselScrollPositions) {
    const scroller = [...$app.querySelectorAll(".horizontal-scroller[data-carousel]")].find((item) => item.dataset.carousel === name);
    if (scroller) {
      scroller.style.scrollBehavior = "auto";
      scroller.scrollLeft = scrollLeft;
      scroller.style.removeProperty("scroll-behavior");
    }
  }
  if (state.route === "search") requestAnimationFrame(() => document.querySelector("#search-input")?.setSelectionRange(state.searchQuery.length, state.searchQuery.length));
  if (state.route === "playlist" && state.playlistSearchOpen) requestAnimationFrame(() => {
    const input = document.querySelector("#playlist-search-input");
    input?.focus({ preventScroll: true });
    input?.setSelectionRange(state.playlistSearchQuery.length, state.playlistSearchQuery.length);
  });
}

function listFromName(name) {
  if (name === "results") return state.results;
  if (name === "discovery") return discoveryTracks;
  if (name?.startsWith("playlist:")) return getPlaylist(name.slice(9)).tracks;
  return [];
}

async function search(query) {
  if (!state.key) { toast("请先连接在线音乐来源"); return; }
  query = query.trim();
  if (!query) return;
  state.searchQuery = query;
  state.searchHistory = [query, ...state.searchHistory.filter((item) => item !== query)].slice(0, 10);
  state.loading = true; state.results = []; render(); persist();
  try {
    await ensureConnected();
    state.results = await searchSource(query);
    if (!state.results.length) toast("没有找到相关歌曲");
  } catch (error) { toast(error.message || "搜索失败"); }
  state.loading = false; render();
}

async function loadDiscover() {
  if (!state.key) { discoveryPlaylists = []; discoveryTracks = []; state.discoverLoading = false; render(); return; }
  state.discoverLoading = true; render();
  try {
    await ensureConnected();
    const [playlists, tracks] = await Promise.allSettled([loadRecommendedPlaylists(), searchSource("热门歌曲", state.sourceId)]);
    const loadedPlaylists = playlists.status === "fulfilled" ? playlists.value : [];
    discoveryTracks = tracks.status === "fulfilled" ? tracks.value.slice(0, 18) : [];
    const fillers = buildSmartPlaylists(discoveryTracks, state.sourceId, 8, `${sourceMeta().code}-smart`);
    discoveryPlaylists = [...loadedPlaylists, ...fillers].filter((item, index, all) => item?.id && all.findIndex((candidate) => String(candidate?.id) === String(item.id)) === index).slice(0, 8);
    if (!discoveryPlaylists.length && !discoveryTracks.length) throw new Error("当前音源推荐内容暂不可用");
  } catch (error) { discoveryPlaylists = []; discoveryTracks = []; toast(error.message || "推荐内容加载失败"); }
  state.discoverLoading = false; render();
}

function playbackRequestIsCurrent(requestId, track) {
  return requestId === playbackRequestId && state.current && trackKey(state.current) === trackKey(track);
}

async function resolveAndPlay(track, requestId = playbackRequestId, signal = playbackAbortController?.signal) {
  if (track.url) {
    if (!playbackRequestIsCurrent(requestId, track)) return false;
    $audio.src = track.url;
    await $audio.play();
    return true;
  }
  await ensureConnected();
  if (!playbackRequestIsCurrent(requestId, track)) return false;
  const source = sourceMeta(track.source).code;
  const songId = String(track.hash || track.songmid || track.remoteId || track.id || "").trim();
  if (!songId) throw new Error("歌曲标识无效");
  const endpoint = new URL(`${SOURCE_API}/api/music/url`);
  endpoint.searchParams.set("source", source);
  endpoint.searchParams.set("songId", songId);
  endpoint.searchParams.set("quality", "128k");
  const body = await fetchJson(endpoint, { headers: { "X-API-Key": state.key, "Content-Type": "application/json" }, signal });
  const url = body.url || body.data?.url;
  if (Number(body.code) !== 200 || !url) throw new Error(body.message || "暂时无法获取播放地址");
  if (!playbackRequestIsCurrent(requestId, track)) return false;
  $audio.src = url;
  await $audio.play();
  return true;
}

async function playTrack(track, list) {
  const requestId = ++playbackRequestId;
  playbackAbortController?.abort();
  playbackAbortController = new AbortController();
  $audio.pause();
  $audio.removeAttribute("src");
  $audio.load();
  state.current = track;
  state.playerSurface = "artwork";
  state.queue = list.length ? list : [track];
  state.recent = [track, ...state.recent.filter((item) => trackKey(item) !== trackKey(track))].slice(0, 100);
  state.playing = false; persist();
  render();
  try {
    const started = await resolveAndPlay(track, requestId, playbackAbortController.signal);
    if (!started || !playbackRequestIsCurrent(requestId, track)) return;
    state.playing = true;
    render();
    updateMediaSession();
  } catch (error) {
    if (!playbackRequestIsCurrent(requestId, track)) return;
    state.playing = false;
    render();
    toast(error.message);
  }
}

function moveQueue(direction) {
  if (!state.current || !state.queue.length) return;
  const current = state.queue.findIndex((item) => trackKey(item) === trackKey(state.current));
  const next = (current + direction + state.queue.length) % state.queue.length;
  playTrack(state.queue[next], state.queue);
}

function updateMediaSession() {
  if (!("mediaSession" in navigator) || !state.current) return;
  navigator.mediaSession.metadata = new MediaMetadata({ title: state.current.title, artist: state.current.artist, album: state.current.album || "简云音乐", artwork: [{ src: state.current.artwork || new URL(FALLBACK_ART, location.href).href, sizes: "512x512" }] });
}

function openPicker(track) { state.pendingTrack = track; state.panel = "playlist-picker"; render(); }

$app.addEventListener("submit", (event) => {
  if (event.target.id === "playlist-search-form") { event.preventDefault(); return; }
  if (event.target.id !== "search-form") return;
  event.preventDefault();
  search(document.querySelector("#search-input")?.value || "");
});

$app.addEventListener("input", (event) => {
  if (event.target.id === "playlist-search-input") { state.playlistSearchQuery = event.target.value; render(); return; }
  if (event.target.id === "progress") $audio.currentTime = Number(event.target.value);
  if (event.target.name === "source") { if (!state.key) { event.preventDefault(); render(); toast("请先验证并连接卡密"); return; } state.sourceId = event.target.value; state.results = []; persist(); toast(`已选择${sourceMeta().name}音乐`); loadDiscover(); }
});

$app.addEventListener("click", async (event) => {
  const target = event.target.closest("[data-action]");
  if (!target) return;
  if (target.closest(".horizontal-scroller")?.dataset.dragged === "1") return;
  const action = target.dataset.action;
  if (action === "open-source-settings") { state.returnRoute = state.route; state.route = "my"; state.panel = "source"; render(); return; }
  if (action === "route") { if (target.dataset.route === "player" && state.route !== "player") state.returnRoute = state.route; if (state.route === "playlist" && target.dataset.route !== "playlist") { state.playlistSearchOpen = false; state.playlistSearchQuery = ""; } state.route = target.dataset.route; state.panel = null; render(); return; }
  if (action === "back") {
    state.panel = null;
    if (state.route === "player") state.route = ["discover", "search", "my", "playlist"].includes(state.returnRoute) && state.returnRoute !== "player" ? state.returnRoute : "discover";
    else if (state.route === "playlist") { state.route = ["discover", "search", "my"].includes(state.playlistReturnRoute) ? state.playlistReturnRoute : "my"; state.playlistSearchOpen = false; state.playlistSearchQuery = ""; }
    else state.route = "my";
    render();
    return;
  }
  if (action === "toggle-lyrics") { if (!state.current) return; state.playerSurface = state.playerSurface === "lyrics" ? "artwork" : "lyrics"; render(); if (state.playerSurface === "lyrics") { updateLyricsPosition(); loadTrackLyric(state.current); } return; }
  if (action === "panel") { event.stopPropagation(); state.panel = target.dataset.panel; render(); return; }
  if (action === "close-panel") { if (event.target.closest("[data-stop]") && !target.matches("button")) return; state.panel = null; render(); return; }
  if (action === "history-search") { if (event.target.closest("[data-action='remove-history']")) return; search(target.dataset.query); return; }
  if (action === "remove-history") { event.stopPropagation(); state.searchHistory.splice(Number(target.dataset.index), 1); persist(); render(); return; }
  if (action === "clear-history") { state.searchHistory = []; persist(); render(); return; }
  if (action === "clear-search") { state.searchQuery = ""; state.results = []; render(); return; }
  if (action === "scroll-carousel") { const scroller = target.parentElement?.querySelector(".horizontal-scroller"); scroller?.scrollBy({ left: Number(target.dataset.direction) * Math.max(scroller.clientWidth * .78, 260), behavior: "smooth" }); return; }
  if (action === "play-track") { const list = listFromName(target.dataset.list); const track = list[Number(target.dataset.index)]; if (track) playTrack(track, list); return; }
  if (action === "play-all") { const list = getPlaylist(target.dataset.id).tracks; if (list[0]) playTrack(list[0], list); return; }
  if (action === "play-toggle") { if (!state.current) return; if ($audio.paused) { try { if (!$audio.src) await resolveAndPlay(state.current); else await $audio.play(); state.playing = true; } catch (error) { toast(error.message); } } else { $audio.pause(); state.playing = false; } render(); return; }
  if (action === "next") { moveQueue(1); return; }
  if (action === "prev") { moveQueue(-1); return; }
  if (action === "like-current") { const key = trackKey(state.current); state.liked = isLiked(state.current) ? state.liked.filter((item) => trackKey(item) !== key) : [state.current, ...state.liked]; persist(); render(); toast(isLiked(state.current) ? "已收藏" : "已取消收藏"); return; }
  if (action === "add-track") { event.stopPropagation(); const list = listFromName(target.dataset.list); openPicker(list[Number(target.dataset.index)]); return; }
  if (action === "track-menu") { event.stopPropagation(); const list = listFromName(target.dataset.list); state.pendingTrack = list[Number(target.dataset.index)]; state.pendingList = target.dataset.list; state.panel = "track-menu"; render(); return; }
  if (action === "open-picker-from-menu") { state.panel = "playlist-picker"; render(); return; }
  if (action === "remove-track") { const listId = state.pendingList?.slice(9); const key = state.pendingTrack && trackKey(state.pendingTrack); if (listId === "liked") state.liked = state.liked.filter((item) => trackKey(item) !== key); else if (listId !== "recent") { const list = state.playlists.find((item) => item.id === listId); if (list) list.tracks = list.tracks.filter((item) => trackKey(item) !== key); } state.pendingTrack = null; state.pendingList = null; state.panel = null; persist(); render(); toast(listId === "recent" ? "最近播放不支持手动移除" : "已从歌单移除"); return; }
  if (action === "add-current") { openPicker(state.current); return; }
  if (action === "open-playlist") { state.playlistReturnRoute = ["discover", "search", "my"].includes(state.route) ? state.route : "my"; state.playlistSearchOpen = false; state.playlistSearchQuery = ""; state.playlistId = target.dataset.id; state.route = "playlist"; render(); return; }
  if (action === "open-discovery-playlist") { const selected = discoveryPlaylists.find((item) => String(item.id) === String(target.dataset.id)); if (!selected) return; const originRoute = ["discover", "search", "my"].includes(state.route) ? state.route : "discover"; target.style.pointerEvents = "none"; toast("正在加载歌单…"); try { const tracks = selected.tracks?.length ? selected.tracks : await loadPlaylistTracks(selected); state.playlistReturnRoute = originRoute; state.playlistSearchOpen = false; state.playlistSearchQuery = ""; state.playlistId = "discover"; state.playlists = state.playlists.filter((list) => list.id !== "discover"); state.playlists.push({ id: "discover", name: selected.name || "推荐歌单", type: sourceMeta(selected.source).name, tracks: tracks.length ? tracks : discoveryTracks }); state.route = "playlist"; render(); } catch (error) { if (discoveryTracks.length) { state.playlistReturnRoute = originRoute; state.playlistSearchOpen = false; state.playlistSearchQuery = ""; state.playlistId = "discover"; state.playlists = state.playlists.filter((list) => list.id !== "discover"); state.playlists.push({ id: "discover", name: selected.name || "推荐歌单", type: sourceMeta(selected.source).name, tracks: discoveryTracks }); state.route = "playlist"; render(); } else { target.style.pointerEvents = ""; toast(error.message || "歌单加载失败"); } } return; }
  if (action === "playlist-search") { state.playlistSearchOpen = true; state.playlistSearchQuery = ""; render(); return; }
  if (action === "clear-playlist-search") { state.playlistSearchQuery = ""; render(); return; }
  if (action === "close-playlist-search") { state.playlistSearchOpen = false; state.playlistSearchQuery = ""; render(); return; }
  if (action === "new-playlist") { event.stopPropagation(); state.panel = "new-playlist"; render(); setTimeout(() => document.querySelector("#playlist-name")?.focus(), 0); return; }
  if (action === "create-playlist") { const name = document.querySelector("#playlist-name")?.value.trim(); if (!name) return toast("请输入歌单名称"); const list = { id: `user-${Date.now()}`, name, type: "歌单", tracks: [] }; state.playlists.push(list); if (state.pendingTrack) list.tracks.push(state.pendingTrack); state.pendingTrack = null; state.panel = null; persist(); render(); toast("歌单已创建"); return; }
  if (action === "select-playlist") { const list = state.playlists.find((item) => item.id === target.dataset.id); if (list && state.pendingTrack && !list.tracks.some((item) => trackKey(item) === trackKey(state.pendingTrack))) list.tracks.push(state.pendingTrack); state.pendingTrack = null; state.panel = null; persist(); render(); toast("已添加到歌单"); return; }
  if (action === "playlist-menu") { event.stopPropagation(); state.pendingPlaylistId = target.dataset.id; state.panel = "playlist-menu"; render(); return; }
  if (action === "confirm-playlist-delete") { state.panel = "confirm-delete"; render(); return; }
  if (action === "delete-playlist") { state.playlists = state.playlists.filter((item) => item.id !== state.pendingPlaylistId); state.pendingPlaylistId = null; state.panel = null; persist(); render(); toast("歌单已删除"); return; }
  if (action === "theme") { state.theme = target.dataset.theme; persist(); render(); return; }
  if (action === "choose-background") { document.querySelector("#background-file")?.click(); return; }
  if (action === "clear-background") { state.background = ""; state.backgroundType = "image"; state.backgroundGlobal = false; persist(); render(); return; }
  if (action === "immersive") { state.componentVisibility = { songInfo: false, artwork: false, progress: false, transport: true, extras: false, favorite: false }; persist(); render(); return; }
  if (action === "reset-components") { state.componentVisibility = { ...defaults.componentVisibility }; persist(); render(); return; }
  if (action === "player-layout") { state.playerLayout = target.dataset.value; persist(); render(); return; }
  if (action === "player-effect") { state.playerEffect = target.dataset.value; persist(); render(); return; }
  if (action === "queue-tab") { state.queuePanelTab = target.dataset.value; render(); return; }
  if (action === "queue-play") { if (event.target.closest("[data-action='remove-queue']")) return; const list = state.queuePanelTab === "history" ? state.recent : state.queue; const track = list[Number(target.dataset.index)]; state.panel = null; if (track) playTrack(track, list); return; }
  if (action === "remove-queue") { event.stopPropagation(); state.queue.splice(Number(target.dataset.index), 1); persist(); render(); return; }
  if (action === "clear-queue") { state.queue = state.current ? [state.current] : []; persist(); render(); return; }
  if (action === "set-sleep") { state.sleepUntil = Date.now() + Number(target.dataset.minutes) * 60000; persist(); state.panel = null; render(); toast(`将在 ${target.dataset.minutes} 分钟后停止播放`); return; }
  if (action === "sleep-off") { state.sleepUntil = null; persist(); state.panel = null; render(); toast("已关闭睡眠定时"); return; }
  if (action === "paste-key") { try { document.querySelector("#source-key").value = await navigator.clipboard.readText(); } catch { toast("浏览器未授权读取剪贴板，请手动粘贴"); } return; }
  if (action === "validate-key") { const key = document.querySelector("#source-key")?.value.trim(); if (!key) return toast("请输入卡密"); target.disabled = true; target.textContent = "验证中…"; try { await validateSourceKey(key); keyValidated = true; lastKeyValidation = Date.now(); state.key = key; state.maskedKey = `••••${key.slice(-4).toUpperCase()}`; state.results = []; persist(); state.panel = "source"; render(); toast("验证成功"); loadDiscover(); } catch (error) { target.disabled = false; target.textContent = "验证并连接"; toast(error.message || "卡密验证失败"); } return; }
});

let dragScroll = null;
$app.addEventListener("pointerdown", (event) => {
  const scroller = event.target.closest(".horizontal-scroller");
  if (!scroller || event.pointerType === "touch" || event.button !== 0) return;
  dragScroll = { scroller, pointerId: event.pointerId, startX: event.clientX, scrollLeft: scroller.scrollLeft, moved: false };
});
$app.addEventListener("pointermove", (event) => {
  if (!dragScroll || event.pointerId !== dragScroll.pointerId) return;
  const delta = event.clientX - dragScroll.startX;
  if (!dragScroll.moved && Math.abs(delta) <= 6) return;
  if (!dragScroll.moved) {
    dragScroll.moved = true;
    dragScroll.scroller.classList.add("dragging");
    dragScroll.scroller.setPointerCapture?.(event.pointerId);
  }
  dragScroll.scroller.scrollLeft = dragScroll.scrollLeft - delta;
  event.preventDefault();
});
function finishCarouselDrag(event) {
  if (!dragScroll || event.pointerId !== dragScroll.pointerId) return;
  const { scroller, moved, pointerId } = dragScroll;
  scroller.classList.remove("dragging");
  if (scroller.hasPointerCapture?.(pointerId)) scroller.releasePointerCapture(pointerId);
  if (moved) {
    scroller.dataset.dragged = "1";
    setTimeout(() => delete scroller.dataset.dragged, 0);
  }
  dragScroll = null;
}
$app.addEventListener("pointerup", finishCarouselDrag);
$app.addEventListener("pointercancel", finishCarouselDrag);
$app.addEventListener("dragstart", (event) => {
  if (event.target.closest(".horizontal-scroller")) event.preventDefault();
});
$app.addEventListener("wheel", (event) => {
  const scroller = event.target.closest(".horizontal-scroller");
  if (!scroller || Math.abs(event.deltaY) <= Math.abs(event.deltaX)) return;
  scroller.scrollLeft += event.deltaY;
  event.preventDefault();
}, { passive: false });

$app.addEventListener("change", (event) => {
  if (event.target.name === "background-global") { state.backgroundGlobal = event.target.checked; persist(); render(); return; }
  if (event.target.name === "player-component") { state.componentVisibility[event.target.value] = event.target.checked; persist(); render(); return; }
  if (event.target.name === "rhythm-artwork") { state.rhythmArtwork = event.target.checked; persist(); render(); return; }
  if (event.target.id === "background-file") {
    const file = event.target.files?.[0];
    if (!file) return;
    if (file.size > 3 * 1024 * 1024) { toast("背景文件请控制在 3MB 以内"); return; }
    const reader = new FileReader();
    reader.onload = () => { state.background = reader.result; state.backgroundType = file.type.startsWith("video/") ? "video" : "image"; persist(); render(); };
    reader.readAsDataURL(file);
  }
});

$audio.addEventListener("play", () => { state.playing = true; render(); });
$audio.addEventListener("pause", () => { state.playing = false; render(); });
$audio.addEventListener("ended", () => moveQueue(1));
$audio.addEventListener("timeupdate", () => {
  if (state.sleepUntil && Date.now() >= state.sleepUntil) { $audio.pause(); state.sleepUntil = null; persist(); toast("睡眠定时已结束"); }
  const progress = document.querySelector("#progress");
  if (progress && !progress.matches(":active")) { progress.max = Math.max(Math.floor($audio.duration || state.current?.duration || 1), 1); progress.value = Math.floor($audio.currentTime || 0); }
  const current = document.querySelector("#current-time");
  const duration = document.querySelector("#duration-time");
  if (current) current.textContent = fmt($audio.currentTime);
  if (duration) duration.textContent = fmt($audio.duration || state.current?.duration);
  const mini = document.querySelector(".mini-player");
  if (mini) {
    const total = Number($audio.duration || state.current?.duration || 0);
    mini.style.setProperty("--mini-progress", `${total > 0 ? Math.min(100, Math.max(0, $audio.currentTime / total * 100)) : 0}%`);
  }
  updateLyricsPosition($audio.currentTime);
});
$audio.addEventListener("error", () => { state.playing = false; render(); toast("当前歌曲播放失败，请尝试其他歌曲"); });

if ("mediaSession" in navigator) {
  navigator.mediaSession.setActionHandler("play", () => $audio.play());
  navigator.mediaSession.setActionHandler("pause", () => $audio.pause());
  navigator.mediaSession.setActionHandler("previoustrack", () => moveQueue(-1));
  navigator.mediaSession.setActionHandler("nexttrack", () => moveQueue(1));
}

window.addEventListener("popstate", () => { state.route = "discover"; state.panel = null; render(); });
if (location.protocol !== "file:") {
  const manifest = document.createElement("link");
  manifest.rel = "manifest";
  manifest.href = "./site.webmanifest";
  document.head.append(manifest);
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("./sw.js").catch(() => {});
}
render();
if (state.key) loadDiscover();
Object.defineProperty(window, "__JianYunTest", { value: { searchSource, validateSourceKey }, configurable: true });
