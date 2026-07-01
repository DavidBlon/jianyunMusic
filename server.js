const {
  search,
  cloudsearch,
  song_detail,
  song_url,
  song_url_v1,
  login_qr_key,
  login_qr_create,
  login_qr_check,
  login_status,
  logout,
  user_account,
  user_playlist,
  like: like_song,
  likelist,
  song_like_check,
  playlist_detail,
  playlist_track_all,
  playlist_tracks,
  playlist_track_add,
  playlist_create,
  personalized,
  recommend_resource,
  recommend_songs,
  banner,
  lyric,
  lyric_new,
  artist_songs,
  artist_detail,
  personal_fm,
  fm_trash,
  check_music,
  top_playlist,
  toplist,
  toplist_detail,
} = require('NeteaseCloudMusicApi');

const { unblock } = require('./unblock');

const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || '0.0.0.0';
const COOKIE_FILE = path.join(__dirname, '.cookie');
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

let userCookie = '';
if (fs.existsSync(COOKIE_FILE)) {
  userCookie = fs.readFileSync(COOKIE_FILE, 'utf-8').trim();
}

function saveCookie(cookie) {
  if (!cookie) return;
  userCookie = cookie;
  try { fs.writeFileSync(COOKIE_FILE, cookie); } catch (e) { console.error('[Cookie] save failed:', e.message); }
}

function readRequestBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      try { resolve(JSON.parse(body)); } catch (e) { resolve({}); }
    });
    req.on('error', reject);
  });
}

function sendJSON(res, data, status = 200) {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
  });
  res.end(JSON.stringify(data));
}

function mapSongRecord(s) {
  if (!s) return null;
  return {
    id: s.id,
    name: s.name,
    artists: (s.ar || s.artists || []).map(a => ({ id: a.id, name: a.name })),
    album: s.al || s.album ? { id: (s.al || s.album).id, name: (s.al || s.album).name, picUrl: (s.al || s.album).picUrl } : null,
    dt: s.dt || s.duration || 0,
    fee: s.fee || 0,
    mv: s.mv || 0,
    pop: s.pop || 0,
  };
}

async function getLoginInfo() {
  if (!userCookie) return { loggedIn: false };
  try {
    const r = await user_account({ cookie: userCookie, timestamp: Date.now() });
    const body = r.body || r;
    const profile = body.profile || {};
    return {
      loggedIn: !!body.code || !!profile.userId,
      userId: profile.userId,
      nickname: profile.nickname,
      avatar: profile.avatarUrl,
      vipType: profile.vipType || 0,
    };
  } catch (e) {
    return { loggedIn: false };
  }
}

async function handleSongUrl(sid, quality) {
  const levels = quality ? [quality] : ['jymaster', 'hires', 'lossless', 'exhigh', 'standard'];
  let lastErr = null;
  for (const level of levels) {
    try {
      const r = await song_url_v1({ id: sid, level, cookie: userCookie, timestamp: Date.now() });
      const data = r.body && r.body.data ? r.body.data[0] : null;
      if (data && data.url) {
        return { url: data.url, br: data.br, size: data.size, type: data.type, encodeType: data.encodeType, level, freeTrialInfo: data.freeTrialInfo || null, code: 200 };
      }
    } catch (e) { lastErr = e; }
  }
  try {
    const r = await song_url({ id: sid, br: 128000, cookie: userCookie, timestamp: Date.now() });
    const data = r.body && r.body.data ? r.body.data[0] : null;
    if (data && data.url) return { url: data.url, br: 128000, size: data.size, type: data.type, level: 'standard', code: 200 };
  } catch (e) { lastErr = e; }
  return { url: null, playable: false, code: 404, error: lastErr ? lastErr.message : 'No playable source' };
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const pn = url.pathname;

  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Cookie',
    });
    res.end();
    return;
  }

  // ---- CORS proxy for cover images ----
  if (pn === '/api/cover') {
    const coverUrl = url.searchParams.get('url');
    if (!coverUrl) { res.writeHead(400); res.end('Missing url'); return; }
    try {
      const rp = await fetch(coverUrl, { headers: { 'User-Agent': UA, 'Referer': 'https://music.163.com/' } });
      const ct = rp.headers.get('content-type') || 'image/jpeg';
      res.writeHead(200, {
        'Content-Type': ct,
        'Access-Control-Allow-Origin': '*',
        'Cache-Control': 'public, max-age=86400',
      });
      for await (const chunk of rp.body) res.write(chunk);
      res.end();
    } catch (e) { res.writeHead(500); res.end(); }
    return;
  }

  // ---- Discover Home (combined: banner + personalized + recommend) ----
  if (pn === '/api/discover/home') {
    try {
      const [bannerRes, personalizedRes, recommendRes] = await Promise.allSettled([
        banner({ type: 0, timestamp: Date.now() }),
        personalized({ limit: 6, cookie: userCookie, timestamp: Date.now() }),
        recommend_songs({ cookie: userCookie, timestamp: Date.now() }),
      ]);
      const banners = bannerRes.value?.body?.banners?.map(b => ({ pic: b.pic, targetId: b.targetId || 0, targetType: b.targetType || 0, typeTitle: b.typeTitle })) || [];
      const playlists = personalizedRes.value?.body?.result?.map(p => ({ id: p.id, name: p.name, picUrl: p.picUrl, playCount: p.playCount || 0, trackCount: p.trackCount || 0, copywriter: p.copywriter })) || [];
      const dailySongs = recommendRes.value?.body?.data?.dailySongs?.map(mapSongRecord).filter(Boolean) || [];
      sendJSON(res, { banners, playlists, dailySongs });
    } catch (e) { sendJSON(res, { error: e.message, banners: [], playlists: [], dailySongs: [] }, 500); }
    return;
  }

  // ---- Search ----
  if (pn === '/api/search') {
    try {
      const kw = url.searchParams.get('keywords') || '';
      const limit = parseInt(url.searchParams.get('limit')) || 20;
      const offset = parseInt(url.searchParams.get('offset')) || 0;
      const type = parseInt(url.searchParams.get('type')) || 1;
      if (!kw) { sendJSON(res, { songs: [] }); return; }
      const r = await cloudsearch({ keywords: kw, type, limit, offset, cookie: userCookie, timestamp: Date.now() });
      const result = r.body?.result || {};
      const songs = (result.songs || []).map(mapSongRecord).filter(Boolean);
      sendJSON(res, { songs, songCount: result.songCount || 0 });
    } catch (e) { sendJSON(res, { error: e.message, songs: [] }, 500); }
    return;
  }

  // ---- Song URL (自动降级：直接获取失败则尝试替代音源) ----
  if (pn === '/api/song/url') {
    try {
      const id = url.searchParams.get('id');
      const quality = url.searchParams.get('quality') || '';
      if (!id) { sendJSON(res, { error: 'Missing id' }, 400); return; }
      const info = await handleSongUrl(id, quality);
      const login = await getLoginInfo();

      // 检查是否可播放；若不可播且用户需要，自动启用替代音源
      const needUnblock = url.searchParams.get('unblock') === 'true';
      if (needUnblock && (!info.url || info.code === 404)) {
        try {
          // 获取歌曲详情用于匹配
          let name = '', artists = [], duration = 0;
          try {
            const detail = await song_detail({ ids: `${id}`, cookie: userCookie, timestamp: Date.now() });
            const song = detail.body?.songs?.[0];
            if (song) {
              name = song.name || '';
              artists = (song.ar || song.artists || []).map(a => ({ name: a.name }));
              duration = song.dt || 0;
            }
          } catch (e) { /* use default */ }

          // 尝试替代音源
          const unblockResult = await unblock({
            id: parseInt(id),
            name,
            artists,
            duration,
          });

          if (unblockResult && unblockResult.url) {
            sendJSON(res, {
              url: unblockResult.url,
              br: 320000,
              source: unblockResult.source,
              code: 200,
              loggedIn: login.loggedIn,
              note: `通过${unblockResult.source}替代音源播放`,
            });
            return;
          }
        } catch (unblockErr) {
          // 替代音源也失败，返回原始结果
          sendJSON(res, { ...info, loggedIn: login.loggedIn, unblockError: unblockErr.message });
          return;
        }
      }

      sendJSON(res, { ...info, loggedIn: login.loggedIn });
    } catch (e) { sendJSON(res, { error: e.message }, 500); }
    return;
  }

  // ---- Unblock: 替代音源匹配 ----
  if (pn === '/api/song/unblock') {
    try {
      const id = parseInt(url.searchParams.get('id'));
      const sourceOrder = (url.searchParams.get('source') || 'kugou,qq').split(',');
      if (!id) { sendJSON(res, { error: 'Missing id' }, 400); return; }

      // 获取歌曲详情
      let name = '', artists = [], duration = 0;
      try {
        const detail = await song_detail({ ids: `${id}`, cookie: userCookie, timestamp: Date.now() });
        const song = detail.body?.songs?.[0];
        if (!song || !song.name) {
          sendJSON(res, { error: 'Song not found' }, 404);
          return;
        }
        name = song.name;
        artists = (song.ar || song.artists || []).map(a => ({ id: a.id, name: a.name }));
        duration = song.dt || 0;
      } catch (e) {
        sendJSON(res, { error: 'Failed to get song detail' }, 500);
        return;
      }

      // 尝试各替代音源
      const result = await unblock({
        id, name, artists, duration,
        keyword: `${name} - ${artists.map(a => a.name).join(' / ')}`,
      }, sourceOrder);

      sendJSON(res, {
        url: result.url,
        source: result.source,
        code: 200,
        name,
        artistText: artists.map(a => a.name).join(' / '),
      });
    } catch (e) {
      sendJSON(res, { error: e.message, code: 404, url: null });
    }
    return;
  }

  // ---- Song Detail ----
  if (pn === '/api/song/detail') {
    try {
      const ids = url.searchParams.get('ids') || '';
      if (!ids) { sendJSON(res, { songs: [] }); return; }
      const r = await song_detail({ ids, cookie: userCookie, timestamp: Date.now() });
      const songs = (r.body?.songs || []).map(mapSongRecord).filter(Boolean);
      sendJSON(res, { songs });
    } catch (e) { sendJSON(res, { error: e.message, songs: [] }, 500); }
    return;
  }

  // ---- Lyric ----
  if (pn === '/api/lyric') {
    try {
      const id = url.searchParams.get('id');
      if (!id) { sendJSON(res, { lyric: '' }, 400); return; }
      let body = {};
      try { const nr = await lyric_new({ id, cookie: userCookie, timestamp: Date.now() }); body = nr.body || {}; } catch (e) {}
      if (!body.lrc?.lyric) {
        const r = await lyric({ id, cookie: userCookie, timestamp: Date.now() });
        body = r.body || {};
      }
      sendJSON(res, {
        lyric: body.lrc?.lyric || '',
        tlyric: body.tlyric?.lyric || '',
        yrc: body.romalrc?.lyric || body.yrc?.lyric || '',
      });
    } catch (e) { sendJSON(res, { error: e.message, lyric: '' }, 500); }
    return;
  }

  // ---- Playlist Tracks ----
  if (pn === '/api/playlist/tracks') {
    try {
      const id = url.searchParams.get('id');
      if (!id) { sendJSON(res, { tracks: [] }, 400); return; }
      let rawTracks = [];
      let playlistMeta = { id, name: '', cover: '', trackCount: 0 };
      if (typeof playlist_track_all === 'function') {
        try {
          const all = await playlist_track_all({ id, limit: 500, offset: 0, cookie: userCookie, timestamp: Date.now() });
          rawTracks = (all.body?.songs || all.body?.tracks || []);
        } catch (e) {}
      }
      if (!rawTracks.length) {
        const detail = await playlist_detail({ id, cookie: userCookie, timestamp: Date.now() });
        const pl = detail.body?.playlist || {};
        playlistMeta = { id: pl.id || id, name: pl.name || '', cover: pl.coverImgUrl || '', trackCount: pl.trackCount || 0 };
        rawTracks = pl.tracks || [];
      }
      const tracks = rawTracks.map(mapSongRecord).filter(Boolean);
      sendJSON(res, { playlist: playlistMeta, tracks });
    } catch (e) { sendJSON(res, { error: e.message, tracks: [] }, 500); }
    return;
  }

  // ---- QR Login: get key ----
  if (pn === '/api/login/qr/key') {
    try {
      const r = await login_qr_key({ timestamp: Date.now() });
      const key = r.body?.data?.unikey;
      sendJSON(res, { key });
    } catch (e) { sendJSON(res, { error: e.message }, 500); }
    return;
  }

  // ---- QR Login: create QR ----
  if (pn === '/api/login/qr/create') {
    try {
      const key = url.searchParams.get('key');
      if (!key) { sendJSON(res, { error: 'Missing key' }, 400); return; }
      const r = await login_qr_create({ key, qrimg: true, timestamp: Date.now() });
      const d = r.body?.data || {};
      sendJSON(res, { img: d.qrimg, url: d.qrurl });
    } catch (e) { sendJSON(res, { error: e.message }, 500); }
    return;
  }

  // ---- QR Login: check ----
  if (pn === '/api/login/qr/check') {
    try {
      const key = url.searchParams.get('key');
      if (!key) { sendJSON(res, { error: 'Missing key' }, 400); return; }
      const r = await login_qr_check({ key, noCookie: true, timestamp: Date.now() });
      const body = r.body || {};
      const code = body.code || 0;
      if (code === 803 && r.headers) {
        const raw = r.headers['set-cookie'] || [];
        const ck = Array.isArray(raw) ? raw.join('; ') : raw;
        saveCookie(ck);
      }
      sendJSON(res, { code, message: body.message || '', nickname: body.nickname, avatar: body.avatarUrl });
    } catch (e) { sendJSON(res, { error: e.message }, 500); }
    return;
  }

  // ---- Login: inject cookie ----
  if (pn === '/api/login/cookie') {
    try {
      const body = await readRequestBody(req);
      const raw = body.cookie || body.data || '';
      if (!raw.includes('MUSIC_U')) { sendJSON(res, { loggedIn: false, error: 'INVALID_NETEASE_COOKIE' }, 400); return; }
      saveCookie(raw);
      const info = await getLoginInfo();
      sendJSON(res, { ...info, saved: true });
    } catch (e) { sendJSON(res, { error: e.message }, 500); }
    return;
  }

  // ---- Login status ----
  if (pn === '/api/login/status') {
    const info = await getLoginInfo();
    sendJSON(res, info);
    return;
  }

  // ---- Logout ----
  if (pn === '/api/logout') {
    try { await logout({ cookie: userCookie }); } catch (e) {}
    saveCookie('');
    sendJSON(res, { ok: true });
    return;
  }

  // ---- User playlists ----
  if (pn === '/api/user/playlist') {
    try {
      const info = await getLoginInfo();
      if (!info.loggedIn || !info.userId) { sendJSON(res, { loggedIn: false, playlists: [] }); return; }
      const limit = parseInt(url.searchParams.get('limit')) || 50;
      const r = await user_playlist({ uid: info.userId, limit, cookie: userCookie, timestamp: Date.now() });
      const list = (r.body?.playlist || []).map(pl => ({
        id: pl.id, name: pl.name, cover: pl.coverImgUrl || '', trackCount: pl.trackCount || 0, playCount: pl.playCount || 0,
        creator: pl.creator?.nickname || '', subscribed: !!pl.subscribed,
      }));
      sendJSON(res, { loggedIn: true, userId: info.userId, playlists: list });
    } catch (e) { sendJSON(res, { error: e.message, playlists: [] }, 500); }
    return;
  }

  // ---- Like song ----
  if (pn === '/api/song/like') {
    try {
      const info = await getLoginInfo();
      if (!info.loggedIn) { sendJSON(res, { loggedIn: false, error: 'Not logged in' }, 401); return; }
      const id = url.searchParams.get('id');
      const like = url.searchParams.get('like') !== 'false';
      if (!id) { sendJSON(res, { error: 'Missing id' }, 400); return; }
      const r = await like_song({ id, like: String(like), cookie: userCookie, timestamp: Date.now() });
      sendJSON(res, { loggedIn: true, id: parseInt(id), liked: like, code: (r.body?.code || r.code || 200) });
    } catch (e) { sendJSON(res, { error: e.message }, 500); }
    return;
  }

  // ---- Like check ----
  if (pn === '/api/song/like/check') {
    try {
      const info = await getLoginInfo();
      if (!info.loggedIn) { sendJSON(res, { loggedIn: false, liked: {} }); return; }
      const ids = (url.searchParams.get('ids') || '').split(',').map(s => s.trim()).filter(Boolean);
      if (!ids.length) { sendJSON(res, { loggedIn: true, liked: {} }); return; }
      const r = await likelist({ uid: info.userId, cookie: userCookie, timestamp: Date.now() });
      const likedIds = new Set((r.body?.ids || []).map(String));
      const liked = {};
      ids.forEach(id => { liked[id] = likedIds.has(String(id)); });
      sendJSON(res, { loggedIn: true, liked });
    } catch (e) { sendJSON(res, { error: e.message, liked: {} }, 500); }
    return;
  }

  // ---- 404 ----
  sendJSON(res, { error: 'Not found' }, 404);
});

server.listen(PORT, HOST, () => {
  console.log('=== NCM Proxy Server ===');
  console.log(`  http://${HOST}:${PORT}`);
  console.log(`  Login: ${userCookie ? 'cookies loaded' : 'not logged in'}`);
  console.log('========================');
});
