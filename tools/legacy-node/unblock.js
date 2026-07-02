/**
 * Legacy local fallback-source experiment kept outside the Android app path.
 *
 * UnblockNeteaseMusic 音源替代模块
 *
 * 参考 https://github.com/nondanee/UnblockNeteaseMusic
 * 为受限/灰化的网易云歌曲从其他平台寻找替代播放源
 *
 * 当前可用提供商：
 *   - kugou: ✅ 完全正常（搜索 + 播放链接）
 *   - qq:    ⚠️ 搜索可用，播放接口已封锁（保留作后备）
 *   - migu:  ❌ 播放接口已失效
 *   - kuwo:  ❌ API 完全封锁
 */

const crypto = require('crypto');
const http = require('http');
const https = require('https');

// ==================== HTTP 工具 ====================

function httpRequest(method, url, headers = {}, body = null, maxRedirects = 5) {
  return new Promise((resolve, reject) => {
    if (maxRedirects < 0) return reject(new Error('Too many redirects'));
    const urlObj = new URL(url);
    const mod = urlObj.protocol === 'https:' ? https : http;
    const options = {
      method,
      hostname: urlObj.hostname,
      port: urlObj.port || (urlObj.protocol === 'https:' ? 443 : 80),
      path: urlObj.pathname + urlObj.search,
      headers: {
        'Accept': 'application/json, text/plain, */*',
        'Accept-Language': 'zh-CN,zh;q=0.9',
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        ...headers,
      },
      timeout: 15000,
    };
    const req = mod.request(options, (res) => {
      if ([301, 302, 307, 308].includes(res.statusCode) && res.headers.location) {
        const redirectUrl = res.headers.location.startsWith('http')
          ? res.headers.location
          : new URL(res.headers.location, url).href;
        resolve(httpRequest(method, redirectUrl, headers, body, maxRedirects - 1));
        return;
      }
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => {
        let buffer = Buffer.concat(chunks);
        const ce = res.headers['content-encoding'];
        if (ce && (ce.includes('gzip') || ce.includes('deflate'))) {
          try {
            const zlib = require('zlib');
            buffer = ce.includes('gzip') ? zlib.gunzipSync(buffer) : zlib.inflateSync(buffer);
          } catch (e) { /* ignore */ }
        }
        resolve({ statusCode: res.statusCode, headers: res.headers, body: buffer.toString('utf8'), raw: buffer });
      });
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('Request timeout')); });
    if (body) req.write(body);
    req.end();
  });
}

function httpGet(url, headers = {}) { return httpRequest('GET', url, headers); }

// ==================== 歌曲匹配引擎 ====================

function normalize(str) {
  if (!str) return '';
  return str.toLowerCase()
    .replace(/[（(]\s*(cover|翻自|翻唱)\s*[:：][^）)]*[）)]/gi, '')
    .replace(/[\s　]+/g, ' ')
    .replace(/[，。！？、；：""''【】《》\-–—·～&@#$%^*+=\\|<>?/]+/g, ' ')
    .replace(/\s+/g, ' ').trim();
}

function similarity(a, b) {
  a = normalize(a || '');
  b = normalize(b || '');
  if (!a || !b) return 0;
  if (a === b) return 1;
  if (a.includes(b) || b.includes(a)) return 0.85;
  const setA = new Set(a.replace(/\s/g, ''));
  const setB = new Set(b.replace(/\s/g, ''));
  if (setA.size === 0 || setB.size === 0) return 0;
  const inter = new Set([...setA].filter(x => setB.has(x)));
  return inter.size / Math.min(setA.size, setB.size);
}

function matchSong(neteaseInfo, candidates) {
  if (!candidates || candidates.length === 0) return null;
  const scored = candidates.map(c => {
    let score = similarity(neteaseInfo.name, c.name) * 50;
    const na = (neteaseInfo.artists || []).map(a => (typeof a === 'string' ? a : a.name).toLowerCase());
    const ca = (c.artists || []).map(a => (typeof a === 'string' ? a : a.name).toLowerCase());
    if (na.length > 0 && ca.length > 0) {
      const mc = na.filter(n => ca.some(ca2 => ca2.includes(n) || n.includes(ca2))).length;
      score += (mc / Math.max(na.length, ca.length)) * 30;
    }
    if (neteaseInfo.duration && c.duration && neteaseInfo.duration > 0 && c.duration > 0) {
      const ratio = Math.min(neteaseInfo.duration, c.duration) / Math.max(neteaseInfo.duration, c.duration);
      if (ratio > 0.7) score += 20 * ratio;
    }
    return { candidate: c, score };
  });
  scored.sort((a, b) => b.score - a.score);
  return scored[0] && scored[0].score > 35 ? scored[0].candidate : null;
}

// ==================== 酷狗音乐（主要提供商，工作正常）====================

const kugou = {
  async search(info) {
    const keyword = `${info.name} ${(info.artists || []).map(a => a.name || a).join(' ')}`;
    const r = await httpGet(`http://songsearch.kugou.com/song_search_v2?keyword=${encodeURIComponent(keyword)}&page=1&pagesize=10`);
    if (r.statusCode !== 200) throw new Error('Kugou search failed');
    const body = JSON.parse(r.body);
    const lists = body.data?.lists || [];
    if (lists.length === 0) throw new Error('Kugou no results');
    const candidates = lists.map(s => ({
      id: s.FileHash || s.hash,
      name: s.SongName || '',
      duration: (s.Duration || 0) * 1000,
      album: { id: s.AlbumID, name: s.AlbumName },
      artists: (s.SingerName || '').split(/[、,，/]/).filter(Boolean).map(x => ({ name: x.trim() })),
    }));
    const matched = matchSong(info, candidates);
    if (!matched) throw new Error('Kugou no match');
    return matched;
  },

  async track(matched) {
    const hash = matched.id;
    if (!hash) throw new Error('Kugou no hash');
    const key = crypto.createHash('md5').update(`${hash}kgcloudv2`).digest('hex');
    const r = await httpGet(`http://trackercdn.kugou.com/i/v2/?key=${key}&hash=${hash}&br=hq&appid=1005&pid=2&cmd=25&behavior=play`);
    if (r.statusCode !== 200) throw new Error('Kugou track failed');
    const body = JSON.parse(r.body);
    const urls = body.url || [];
    if (urls.length > 0) return urls[0];
    throw new Error('Kugou no URL');
  },

  async check(info) {
    const matched = await this.search(info);
    return await this.track(matched);
  }
};

// ==================== QQ 音乐（搜索可用，Vkey 接口已封锁）====================

const qq = {
  async search(info) {
    const keyword = `${info.name} ${(info.artists || []).map(a => a.name || a).join(' ')}`;
    const url = `https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${encodeURIComponent(keyword)}&format=json&inCharset=utf8&outCharset=utf-8&platform=yqq&p=1&n=5`;
    const res = await httpGet(url, { 'Referer': 'http://y.qq.com/' });
    if (res.statusCode !== 200) throw new Error('QQ search failed');
    const body = JSON.parse(res.body);
    const list = (body.data?.song?.list) || [];
    if (list.length === 0) throw new Error('QQ no results');
    const candidates = list.map(s => ({
      id: s.songmid, mid: s.songmid,
      name: s.songname || '',
      duration: (s.interval || 0) * 1000,
      artists: (s.singer || []).map(x => ({ name: x.name })),
    }));
    const matched = matchSong(info, candidates);
    if (!matched) throw new Error('QQ no match');
    return matched;
  },

  async track(matched) {
    const songmid = matched.mid || matched.id;
    if (!songmid) throw new Error('QQ no mid');
    // QQ 的 vkey 接口现已封锁，后续可能变化，保持此逻辑供未来修复
    for (const fmt of ['M500', 'M800', 'F000']) {
      try {
        const ext = fmt === 'F000' ? '.flac' : '.mp3';
        const filename = `${fmt}${songmid}${ext}`;
        const guid = String(Math.floor(Math.random() * 9000000000) + 1000000000);
        const payload = { req_0: { module: 'vkey.GetVkeyServer', method: 'CgiGetVkey', param: { guid, loginflag: 0, filename: [filename], songmid: [songmid], songtype: [0], uin: '0', platform: '20' } } };
        const r = await httpGet(`https://u.y.qq.com/cgi-bin/musicu.fcg?data=${encodeURIComponent(JSON.stringify(payload))}`, {
          'Origin': 'http://y.qq.com', 'Referer': 'http://y.qq.com/',
        });
        if (r.statusCode !== 200) continue;
        const j = JSON.parse(r.body);
        const sip = j.req_0?.data?.sip || [];
        const purl = j.req_0?.data?.midurlinfo?.[0]?.purl;
        if (purl && sip.length > 0) return sip[0] + purl;
      } catch (e) { /* try next */ }
    }
    throw new Error('QQ no playable URL');
  },

  async check(info) {
    const matched = await this.search(info);
    return await this.track(matched);
  }
};

// ==================== URL 验证 ====================

async function verifyUrl(url) {
  try {
    const res = await httpRequest('GET', url, { 'Range': 'bytes=0-8191' });
    if (!res.statusCode.toString().startsWith('2')) return false;
    const cr = res.headers['content-range'];
    if (cr) {
      const total = parseInt(cr.split('/').pop() || '0');
      if (total > 0) return true;
    }
    const cl = parseInt(res.headers['content-length'] || '0');
    if (cl === 0 && !cr) return false;
    // 检查音频文件头
    const raw = res.raw || Buffer.alloc(0);
    if (raw.length >= 4) {
      const h = raw.slice(0, 4).toString();
      if (h === 'fLaC' || h === 'OggS' || h.startsWith('ID3') || h.startsWith('RIFF')) return true;
      if (raw[0] === 0xFF && (raw[1] & 0xE0) === 0xE0) return true; // MP3 同步标志
    }
    return true;
  } catch (e) {
    return false;
  }
}

// ==================== 主入口 ====================

const PROVIDERS = { qq, kugou };

async function unblock(songInfo, sourcePriority = null) {
  // 默认优先使用 Kugou，QQ 作为后备（目前 Vkey 已封锁，保留代码以供未来修复）
  const sources = sourcePriority || ['kugou', 'qq'];
  const errors = [];
  const info = {
    ...songInfo,
    keyword: songInfo.keyword || `${songInfo.name} - ${(songInfo.artists || []).map(a => a.name || a).join(' / ')}`,
  };

  for (const name of sources) {
    const provider = PROVIDERS[name];
    if (!provider) { errors.push(`${name}: unknown provider`); continue; }
    try {
      const url = await provider.check(info);
      if (url) {
        const valid = await verifyUrl(url);
        if (valid) return { url, source: name, verified: true };
        errors.push(`${name}: URL validation failed`);
      }
    } catch (e) {
      errors.push(`${name}: ${e.message}`);
    }
  }
  throw new Error(`All sources failed: ${errors.join('; ')}`);
}

module.exports = { unblock, PROVIDERS, verifyUrl };
