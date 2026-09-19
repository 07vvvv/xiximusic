'use strict';

/**
 * src/qq/api.js
 * ---------------------------------------------------------------------------
 * QQ 音乐真实接口封装。
 *
 * 设计原则（重要，决定了每个接口的“主路径”）：
 *   搜索 / 推荐 / 歌词 / 播放直链 / 详情  => 优先使用 **免签名** 的 legacy 接口
 *        (c.y.qq.com / u.y.qq.com 的老 CGI)，它们不需要 sign，最稳。
 *   用户信息 / 部分详情                     => 走 u.y.qq.com/cgi-bin/musicu.fcg，
 *        使用 sign.js 里的 zzc_sign（POST 表单 data= + query sign=）。
 *        若签名调用失败（签名算法变更 / 风控），自动回退到 legacy 接口，
 *        保证 APP 永远不会因为签名问题完全不可用。
 *
 * 所有上游调用都有 try/catch，绝不产生未捕获的 Promise rejection。
 * ---------------------------------------------------------------------------
 */

const http = require('./http');
const sign = require('./sign');
const normalize = require('./normalize');
const { decodeLyricText, toUtf8String, ptqrtoken, unwrapQrcImage } = require('./crypto');

/* -------------------------------------------------------------------------- */
/* 常量                                                                        */
/* -------------------------------------------------------------------------- */

/**
 * 上游接口地址。
 *
 * 支持同名环境变量覆盖（可选）：QQ_SEARCH_URL / QQ_TOPLIST_URL / QQ_LYRIC_NEW_URL /
 * QQ_LYRIC_OLD_URL / QQ_VKEY_URL / QQ_VKEY_LEGACY_URL / QQ_MUSICU_URL /
 * QQ_USERINFO_LEGACY_URL / QQ_QR_SHOW_URL / QQ_QR_LOGIN_URL。
 * 官方接口变更时可以在不改代码的情况下临时切换（也便于离线自测）。
 */
const URLS = {
  search: process.env.QQ_SEARCH_URL || 'https://c.y.qq.com/soso/fcgi-bin/client_search_cp',
  toplist: process.env.QQ_TOPLIST_URL || 'https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg',
  lyricNew: process.env.QQ_LYRIC_NEW_URL || 'https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg',
  lyricOld: process.env.QQ_LYRIC_OLD_URL || 'https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric.fcg',
  vkey: process.env.QQ_VKEY_URL || 'https://u.y.qq.com/cgi-bin/musicu.fcg',
  vkeyLegacy: process.env.QQ_VKEY_LEGACY_URL || 'https://c.y.qq.com/base/fcgi-bin/fcg_music_express_mobile3.fcg',
  musicu: process.env.QQ_MUSICU_URL || 'https://u.y.qq.com/cgi-bin/musicu.fcg',
  userInfoLegacy: process.env.QQ_USERINFO_LEGACY_URL || 'https://c.y.qq.com/rsc/fcgi-bin/fcg_get_user_info.fcg',
  qrShow: process.env.QQ_QR_SHOW_URL || 'https://ssl.ptlogin2.qq.com/ptqrshow',
  qrLogin: process.env.QQ_QR_LOGIN_URL || 'https://ssl.ptlogin2.qq.com/ptqrlogin',
};

/** 音质阶梯：必须严格按 flac -> ape -> 320 -> 128 顺序尝试 */
const QUALITY_LADDER = ['flac', 'ape', '320', '128'];

/** 音质对应的中文标签 */
const QUALITY_LABEL = {
  flac: '无损FLAC',
  ape: '无损APE',
  '320': '高品质320K',
  '128': '标准128K',
};

/** 音质 -> 直链文件名前缀（QQ 约定：F000=flac, A000=ape, M800=320k, M500=128k） */
const QUALITY_PREFIX = {
  flac: 'F000',
  ape: 'A000',
  '320': 'M800',
  '128': 'M500',
};

/** 音质 -> 备用扩展名（128k 也可能是 m4a） */
const QUALITY_FALLBACK_EXT = {
  '128': 'm4a',
};

/** QQ 直链默认 3 分钟过期（接口没给 expiration 时使用，单位秒） */
const DEFAULT_URL_TTL_SECONDS = 180;

/**
 * 直链有效期上限（秒）。
 * 客户端只缓存 3 分钟，这里把服务端也钳到同一量级，
 * 绝不向客户端声明一个「几十年后过期」的直链。
 */
const MAX_URL_TTL_SECONDS = 180;

/** 直链有效期下限（秒）：太短的值视为异常，用默认值兜底 */
const MIN_URL_TTL_SECONDS = 30;

/**
 * 归一化 QQ 返回的 expiration 字段。
 *
 * 关键点：QQ 的 vkey 接口里 expiration 可能是
 *   - 绝对 Unix 时间戳（秒），例如 1767225600
 *   - 也可能缺省 / 为 0
 * 早期实现直接把它当「时长」使用，会把 expiredAt 算到几十年后，
 * 导致客户端误以为直链长期有效。这里做语义识别 + 区间钳制。
 *
 * @param {number|string} raw
 * @returns {number} 归一化后的有效秒数，范围 [MIN_URL_TTL_SECONDS, MAX_URL_TTL_SECONDS]
 */
function normalizeExpiration(raw) {
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) return DEFAULT_URL_TTL_SECONDS;

  // 看起来像绝对时间戳（大于 2001-09-09 的秒级时间戳约 1e9）时换算成剩余时长
  if (value > 1e9) {
    const remaining = Math.floor(value - Date.now() / 1000);
    if (remaining <= 0) return MIN_URL_TTL_SECONDS;
    return Math.max(MIN_URL_TTL_SECONDS, Math.min(MAX_URL_TTL_SECONDS, remaining));
  }

  // 否则当作「时长秒数」
  return Math.max(MIN_URL_TTL_SECONDS, Math.min(MAX_URL_TTL_SECONDS, Math.floor(value)));
}

/* -------------------------------------------------------------------------- */
/* 小工具                                                                      */
/* -------------------------------------------------------------------------- */

/** 安全 JSON.parse，失败返回 null */
function safeJson(text) {
  if (text === null || text === undefined) return null;
  if (typeof text === 'object') return text;
  const raw = toUtf8String(text).trim();
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch (err) {
    const s = raw.indexOf('{');
    const e = raw.lastIndexOf('}');
    if (s >= 0 && e > s) {
      try {
        return JSON.parse(raw.slice(s, e + 1));
      } catch (err2) {
        return null;
      }
    }
    return null;
  }
}

/** 清理 cookie 字符串（去换行 / 去引号 / 去多余分号） */
function cleanCookie(cookie) {
  if (!cookie) return '';
  return toUtf8String(cookie)
    .replace(/[\r\n]+/g, '')
    .replace(/^["']|["']$/g, '')
    .trim();
}

/**
 * 解析 axios 响应里的 set-cookie（可能是数组或单个字符串）。
 * @param {object} headers
 * @returns {Array<{name: string, value: string, raw: string}>}
 */
function parseSetCookie(headers) {
  if (!headers) return [];
  let raw = headers['set-cookie'] || headers['Set-Cookie'];
  if (!raw) return [];
  if (!Array.isArray(raw)) raw = [raw];
  const out = [];
  for (const line of raw) {
    if (!line || typeof line !== 'string') continue;
    const first = line.split(';')[0];
    const eq = first.indexOf('=');
    if (eq <= 0) continue;
    const name = first.slice(0, eq).trim();
    const value = first.slice(eq + 1).trim();
    if (!name) continue;
    out.push({ name, value, raw: line });
  }
  return out;
}

/** 把 set-cookie 数组转成 cookie 字符串（过滤无用域 cookie） */
function cookiesToString(pairs) {
  const seen = new Map();
  for (const p of pairs || []) {
    if (!p || !p.name) continue;
    if (/^(RK|ptcz|ptui_loginuin|pac_uid|_qpsvr_localtk)$/i.test(p.name)) continue;
    seen.set(p.name, p.value);
  }
  return Array.from(seen.entries())
    .map(([k, v]) => `${k}=${v}`)
    .join('; ');
}

/**
 * 合并 cookie 字符串（后面的覆盖前面的同名项）。
 * @param {...string} parts
 * @returns {string}
 */
function mergeCookies() {
  const map = new Map();
  for (let i = 0; i < arguments.length; i += 1) {
    const str = cleanCookie(arguments[i]);
    if (!str) continue;
    str.split(';').forEach((piece) => {
      const p = piece.trim();
      if (!p) return;
      const eq = p.indexOf('=');
      if (eq <= 0) return;
      map.set(p.slice(0, eq).trim(), p.slice(eq + 1).trim());
    });
  }
  return Array.from(map.entries())
    .map(([k, v]) => `${k}=${v}`)
    .join('; ');
}

/** 从 cookie 里取某个字段 */
function cookieValue(cookie, name) {
  const str = cleanCookie(cookie);
  if (!str) return '';
  const m = str.match(new RegExp(`(?:^|;\\s*)${name}=([^;\\s]*)`, 'i'));
  return m ? m[1] : '';
}

/** 是否具备登录态（有 skey 或 music key 才算真登录） */
function hasLoginCookie(cookie) {
  const str = cleanCookie(cookie);
  if (!str) return false;
  if (cookieValue(str, 'skey')) return true;
  if (cookieValue(str, 'qqmusic_key') || cookieValue(str, 'qm_keyst')) return true;
  if (cookieValue(str, 'musickey')) return true;
  return false;
}

/** QQ Music 的 g_tk（由 skey 派生），musicu 里需要 */
function calcGtk(skey) {
  if (!skey) return 5381;
  let hash = 5381;
  for (let i = 0; i < skey.length; i += 1) {
    hash += (hash << 5) + skey.charCodeAt(i);
    hash &= 0x7fffffff;
  }
  return hash;
}

/**
 * 带签名的 musicu.fcg 调用（POST 表单 data= + query sign=）。
 * 失败时抛错，由调用方决定是否回退。
 * @param {object} body 完整请求体对象
 * @param {object} [opts]
 * @returns {Promise<any>}
 */
async function callMusicu(body, opts = {}) {
  const bodyString = JSON.stringify(body);
  const { sign: signValue, token } = sign.zzcSign(bodyString);
  const params = Object.assign(
    {
      format: 'json',
      sign: signValue,
      token,
      platform: 'yqq.json',
      needNewCode: '0',
    },
    opts.extraParams || {}
  );
  const referer = opts.referer || 'https://y.qq.com/portal/profile.html';
  return http.cached(
    opts.cacheKey || null,
    opts.ttl || http.TTL.detail,
    () => http.postForm(`${URLS.musicu}?${require('qs').stringify(params)}`, { data: bodyString }, {
      cookie: opts.cookie,
      referer,
      headers: { Accept: 'application/json' },
    })
  );
}

/* -------------------------------------------------------------------------- */
/* 1. 搜索                                                                     */
/* -------------------------------------------------------------------------- */

/**
 * 歌曲搜索（主路径：免签名 client_search_cp）。
 * @param {string} keywords
 * @param {number} [page=1]
 * @param {number} [limit=20]
 * @param {object} [opts]
 * @param {string} [opts.cookie]
 * @returns {Promise<{list: object[], total: number, page: number, limit: number, hasMore: boolean}>}
 */
async function search(keywords, page = 1, limit = 20, opts = {}) {
  const kw = toUtf8String(keywords || '').trim();
  const p = Math.max(1, normalize.toInt(page, 1));
  const n = Math.min(50, Math.max(1, normalize.toInt(limit, 20)));
  if (!kw) {
    return { list: [], total: 0, page: p, limit: n, hasMore: false };
  }

  const key = `search:${kw}:${p}:${n}:${hasLoginCookie(opts.cookie) ? 'v' : 'g'}`;
  return http.cached(key, http.TTL.search, async () => {
    const params = {
      format: 'json',
      inCharset: 'utf-8',
      outCharset: 'utf-8',
      notice: 0,
      platform: 'yqq.json',
      needNewCode: 0,
      uin: 0,
      g_tk: 5381,
      t: 0,
      p,
      n,
      w: kw,
      cr: 1,
      catZhida: 1,
      remoteplace: 'txt.mqq.all',
      new_json: 1,
    };
    let raw = null;
    try {
      raw = await http.getJson(URLS.search, {
        params,
        cookie: opts.cookie,
        referer: 'https://y.qq.com/portal/search.html',
      });
    } catch (err) {
      console.warn('[qq] search 上游失败:', err && err.message);
      raw = null;
    }

    const data = (raw && raw.data) || {};
    const songBlock = data.song || {};
    let list = songBlock.list || songBlock.itemlist || [];
    // 老格式（未带 new_json）会把结果放在 data.song.list，字段为 songmid/songname
    if (!Array.isArray(list) || list.length === 0) {
      const alt = data.list || (Array.isArray(data) ? data : null);
      if (Array.isArray(alt)) list = alt;
    }
    const songs = normalize.normalizeSongList(list);
    const total = normalize.toInt(
      normalize.pick(songBlock.totalnum, songBlock.total, data.totalnum, data.total, songs.length),
      songs.length
    );
    const hasMore = p * n < total;
    return { list: songs, total, page: p, limit: n, hasMore };
  });
}

/* -------------------------------------------------------------------------- */
/* 2. 推荐（热歌榜 topid=26）                                                   */
/* -------------------------------------------------------------------------- */

/**
 * 从某个榜单拉歌（默认 topid=26 热歌榜）。
 * @param {number|string} topid
 * @param {object} [opts]
 * @param {string} [opts.cookie]
 * @returns {Promise<object[]>} 标准 Song 列表
 */
async function fetchTopList(topid, opts = {}) {
  const id = String(topid || 26);
  const key = `toplist:${id}`;
  return http.cached(key, http.TTL.topList, async () => {
    const params = {
      format: 'json',
      inCharset: 'utf-8',
      outCharset: 'utf-8',
      notice: 0,
      platform: 'yqq.json',
      needNewCode: 0,
      uin: 0,
      tpl: 3,
      page: 'detail',
      type: 'top',
      topid: id,
      song_begin: 0,
      song_num: 100,
    };
    let raw = null;
    try {
      raw = await http.getJson(URLS.toplist, {
        params,
        cookie: opts.cookie,
        referer: `https://y.qq.com/n/ryqq/toplist/${id}`,
      });
    } catch (err) {
      console.warn(`[qq] toplist(${id}) 上游失败:`, err && err.message);
      raw = null;
    }
    const data = (raw && raw.data) || raw || {};
    const list = data.songlist || data.songList || data.list || [];
    return normalize.normalizeSongList(list);
  });
}

/**
 * 推荐歌曲：热歌榜 topid=26（完整歌曲对象，含 mid/singer），不足 30 首时用其它榜单补齐。
 * @param {object} [opts]
 * @param {string} [opts.cookie]
 * @returns {Promise<{list: object[]}>}
 */
async function recommendSongs(opts = {}) {
  const key = 'recommend:songs';
  return http.cached(key, http.TTL.recommend, async () => {
    const merged = [];
    const seen = new Set();
    const push = (songs) => {
      for (const s of songs || []) {
        const k = s.mid || s.id;
        if (!k || seen.has(k)) continue;
        seen.add(k);
        merged.push(s);
      }
    };
    // 主榜：热歌榜
    push(await fetchTopList(26, opts));
    // 兜底：飙升榜 4 / 新歌榜 27 / 流行指数 62，保证至少 30 首
    const fallbacks = [4, 27, 62, 6];
    for (const id of fallbacks) {
      if (merged.length >= 60) break;
      try {
        push(await fetchTopList(id, opts));
      } catch (err) {
        console.warn(`[qq] toplist(${id}) 兜底失败:`, err && err.message);
      }
    }
    return { list: merged };
  });
}

/* -------------------------------------------------------------------------- */
/* 3. 播放直链（音质阶梯 flac -> ape -> 320 -> 128）                            */
/* -------------------------------------------------------------------------- */

/** 构造某个音质的候选文件名列表 */
function buildFilenames(mid, quality) {
  const prefix = QUALITY_PREFIX[quality] || 'M500';
  const names = [`${prefix}${mid}.mp3`];
  const alt = QUALITY_FALLBACK_EXT[quality];
  if (alt) names.push(`${prefix}${mid}.${alt}`);
  if (quality === 'flac') names.push(`F000${mid}.flac`);
  if (quality === 'ape') names.push(`A000${mid}.ape`);
  return names;
}

/** 生成一次性的 guid（vkey 接口要求 10 位数字） */
function makeGuid() {
  return String(Math.floor(Math.random() * 9e9) + 1e9).slice(0, 10);
}

/**
 * 向 musicu.fcg 请求某个文件名的 vkey（主路径，需签名，失败自动回退）。
 * @param {string} mid
 * @param {string} filename
 * @param {object} [opts]
 * @returns {Promise<{purl: string, size: number, bitrate: number, expiration: number}|null>}
 */
async function requestVkey(mid, filename, opts = {}) {
  const uin = sign.parseUinFromCookie(opts.cookie);
  const guid = makeGuid();
  const body = {
    req_0: {
      module: 'vkey.GetVkeyServer',
      method: 'CgiGetVkey',
      param: {
        guid,
        songmid: [mid],
        songtype: [0],
        uin: uin === '0' ? '0' : uin,
        loginflag: hasLoginCookie(opts.cookie) ? 1 : 0,
        platform: '20',
        filename: [filename],
      },
    },
    comm: sign.buildComm({ cookie: opts.cookie }),
  };

  // ---- 主路径：musicu.fcg（签名表单）----
  try {
    const raw = await callMusicu(body, {
      cookie: opts.cookie,
      referer: 'https://y.qq.com/',
    });
    const parsed = typeof raw === 'string' ? safeJson(raw) : raw;
    const req0 = parsed && parsed.req_0 ? parsed.req_0 : null;
    const inner = req0 && req0.data ? (typeof req0.data === 'string' ? safeJson(req0.data) : req0.data) : null;
    const entry = inner && Array.isArray(inner.midurlinfo) ? inner.midurlinfo[0] : null;
    if (entry && entry.purl) {
      return {
        purl: entry.purl,
        size: normalize.toInt(normalize.pick(entry.filesize, entry.fileSize, 0), 0),
        bitrate: normalize.toInt(normalize.pick(entry.bitrate, 0), 0),
        expiration: normalize.toInt(normalize.pick(entry.expiration, 0), 0),
      };
    }
    const code = entry && entry.result !== undefined ? entry.result : inner && inner.code;
    if (code !== undefined && code !== null && normalize.toInt(code, 0) !== 0) {
      console.log(`[qq] vkey(${filename}) musicu 返回不可用 code=${code} (${entry && entry.errtype ? entry.errtype : ''})`);
    }
  } catch (err) {
    console.warn('[qq] vkey musicu 签名调用失败，回退 legacy:', err && err.message);
  }

  // ---- 回退：老 c.y.qq.com 免签名接口 ----
  try {
    const params = {
      format: 'json',
      platform: 'yqq.json',
      needNewCode: 0,
      cid: 205361747,
      songmid: mid,
      filename,
      guid,
      uin: uin === '0' ? '0' : uin,
    };
    const raw = await http.getJson(URLS.vkeyLegacy, {
      params,
      cookie: opts.cookie,
      referer: 'https://y.qq.com/',
    });
    const parsed = typeof raw === 'string' ? safeJson(raw) : raw;
    const items = (parsed && parsed.data && parsed.data.items) || [];
    const entry = Array.isArray(items) ? items[0] : null;
    if (entry && entry.purl) {
      return {
        purl: entry.purl,
        size: normalize.toInt(normalize.pick(entry.size, entry.filesize, 0), 0),
        bitrate: normalize.toInt(normalize.pick(entry.bitrate, 0), 0),
        expiration: 0,
      };
    }
  } catch (err) {
    console.warn('[qq] vkey legacy 回退也失败:', err && err.message);
  }

  return null;
}

/**
 * 解析一个音质的可用直链。
 * @param {string} mid
 * @param {string} quality
 * @param {object} [opts]
 * @returns {Promise<{url: string, quality: string, size: number, ttlSeconds: number}|null>}
 */
async function resolveOneQuality(mid, quality, opts = {}) {
  const filenames = buildFilenames(mid, quality);
  for (const filename of filenames) {
    const cacheKey = `vkey:${mid}:${filename}`;
    let vkey = null;
    try {
      vkey = await http.cached(cacheKey, 45 * 1000, () => requestVkey(mid, filename, opts));
    } catch (err) {
      console.warn('[qq] resolveOneQuality 异常:', err && err.message);
      vkey = null;
    }
    if (!vkey || !vkey.purl) continue;
    const purl = toUtf8String(vkey.purl).trim();
    if (!purl) continue;

    // purl 有时是完整 URL（sip 列表），有时只是路径
    let url = '';
    if (/^https?:\/\//i.test(purl)) {
      url = purl;
    } else if (purl.startsWith('http')) {
      url = purl;
    } else {
      const base = 'https://isure.stream.qqmusic.qq.com/';
      url = `${base}${purl.replace(/^\//, '')}`;
    }

    // 实测音质校验：请求 flac 却拿到 128k 时不冒充 flac
    const realQuality = normalize.resolveRealQuality(quality, filename, url);
    const ttlSeconds = normalizeExpiration(vkey.expiration);
    return { url, quality: realQuality, size: vkey.size, ttlSeconds };
  }
  return null;
}

/**
 * 按音质阶梯解析播放直链，返回第一个可用的。
 *
 * 缓存策略：TTL = 100s（<=120s），并且返回的 expiredAt 一定是基于
 * 真实铸造时间计算的剩余有效期，避免把过期直链丢给客户端。
 *
 * @param {string} mid
 * @param {string} [preferredQuality] 用户偏好的音质，会排在阶梯最前面
 * @param {object} [opts]
 * @param {string} [opts.cookie]
 * @returns {Promise<{url, quality, qualityLabel, size, expiredAt}|null>}
 */
async function songUrls(mid, preferredQuality, opts = {}) {
  const songMid = toUtf8String(mid || '').trim();
  if (!songMid) return null;

  // 构造阶梯：偏好音质优先，其余按标准顺序补齐，严格保持 flac->ape->320->128 的相对次序
  let ladder = QUALITY_LADDER.slice();
  const pref = toUtf8String(preferredQuality || '').trim().toLowerCase();
  if (pref && QUALITY_LADDER.includes(pref)) {
    ladder = [pref].concat(QUALITY_LADDER.filter((q) => q !== pref));
  }

  const cacheKey = `url:${songMid}:${ladder.join(',')}:${hasLoginCookie(opts.cookie) ? 'v' : 'g'}`;
  const hit = http.cache.getEntry(cacheKey);
  if (hit && hit.value) {
    const remaining = hit.value.mintedAt + hit.value.ttlSeconds * 1000;
    if (remaining - Date.now() > 5000) {
      // 用真实剩余寿命重算 expiredAt
      return Object.assign({}, hit.value.result, { expiredAt: remaining });
    }
    http.cache.del(cacheKey);
  }

  for (const quality of ladder) {
    let found = null;
    try {
      found = await resolveOneQuality(songMid, quality, opts);
    } catch (err) {
      console.warn(`[qq] 解析音质 ${quality} 失败:`, err && err.message);
      found = null;
    }
    if (!found || !found.url) continue;

    const mintedAt = Date.now();
    const expiredAt = mintedAt + Math.max(30, found.ttlSeconds) * 1000;
    const result = {
      url: found.url,
      quality: found.quality,
      qualityLabel: QUALITY_LABEL[found.quality] || QUALITY_LABEL['128'],
      size: found.size || 0,
      expiredAt,
    };
    // 缓存 TTL 取 min(100s, 真实寿命 - 10s 安全边界)
    const cacheTtl = Math.max(15 * 1000, Math.min(100 * 1000, found.ttlSeconds * 1000 - 10 * 1000));
    http.cache.set(cacheKey, { result, mintedAt, ttlSeconds: found.ttlSeconds }, cacheTtl);
    return result;
  }

  return null;
}

/* -------------------------------------------------------------------------- */
/* 4. 歌词                                                                     */
/* -------------------------------------------------------------------------- */

/**
 * 解析歌词接口的返回体。
 * @param {*} raw
 * @param {string} key 额外兼容的字段名（如 'lyric' / 'trans'）
 * @returns {string}
 */
function extractLyricField(raw, key) {
  const parsed = typeof raw === 'string' ? safeJson(raw) : raw;
  if (!parsed || typeof parsed !== 'object') return '';
  const value = parsed[key];
  if (value === undefined || value === null || value === '') return '';
  // 接口固定返回 base64，但也见过明文，decodeLyricText 两者都能吃
  return decodeLyricText(value);
}

/**
 * 歌词（原词 + 翻译）。主路径：免签名 fcg_query_lyric_new.fcg。
 * 没有歌词不是错误，返回空串。
 * @param {string} mid
 * @param {object} [opts]
 * @param {string} [opts.cookie]
 * @returns {Promise<{lyric: string, trans: string}>}
 */
async function lyric(mid, opts = {}) {
  const songMid = toUtf8String(mid || '').trim();
  if (!songMid) return { lyric: '', trans: '' };

  const key = `lyric:${songMid}`;
  return http.cached(key, http.TTL.lyric, async () => {
    const params = {
      format: 'json',
      nobase64: 0,
      songmid: songMid,
      g_tk: 5381,
      inCharset: 'utf-8',
      outCharset: 'utf-8',
      notice: 0,
      platform: 'yqq.json',
      needNewCode: 0,
      uin: 0,
    };
    let raw = null;
    try {
      raw = await http.getJson(URLS.lyricNew, {
        params,
        cookie: opts.cookie,
        referer: 'https://y.qq.com/portal/player.html',
      });
    } catch (err) {
      console.warn('[qq] lyric new 接口失败:', err && err.message);
      raw = null;
    }
    let text = extractLyricField(raw, 'lyric');
    let trans = extractLyricField(raw, 'trans');

    // 新接口拿不到时回退到老接口
    if (!text) {
      try {
        const legacy = await http.getJson(URLS.lyricOld, {
          params: Object.assign({}, params, { songmid: songMid, songtype: 0 }),
          cookie: opts.cookie,
          referer: 'https://y.qq.com/portal/player.html',
        });
        text = extractLyricField(legacy, 'lyric');
        if (!trans) trans = extractLyricField(legacy, 'trans');
      } catch (err) {
        console.warn('[qq] lyric old 接口回退失败:', err && err.message);
      }
    }

    return { lyric: text || '', trans: trans || '' };
  });
}

/* -------------------------------------------------------------------------- */
/* 5. 歌曲详情                                                                 */
/* -------------------------------------------------------------------------- */

/** 把 detail 接口的原始对象转成 Song（复用 normalize，再补 file 信息） */
function detailToSong(raw) {
  const song = normalize.normalizeSong(raw);
  if (!song) return null;
  return song;
}

/**
 * 歌曲详情。主路径：musicu.fcg（签名）music.search.SearchCgiService；
 * 失败则回退到 client_search_cp 按 mid 精确搜索（免签名）。
 * @param {string} mid
 * @param {object} [opts]
 * @param {string} [opts.cookie]
 * @returns {Promise<object|null>}
 */
async function detail(mid, opts = {}) {
  const songMid = toUtf8String(mid || '').trim();
  if (!songMid) return null;

  const key = `detail:${songMid}:${hasLoginCookie(opts.cookie) ? 'v' : 'g'}`;
  return http.cached(key, http.TTL.detail, async () => {
    // ---- 主路径：签名 musicu ----
    const body = {
      comm: sign.buildComm({ cookie: opts.cookie }),
      req_1: {
        module: 'music.pf_song_detail_svr',
        method: 'get_song_detail_yqq',
        param: { song_type: 0, song_mid: songMid },
      },
    };
    try {
      const raw = await callMusicu(body, {
        cookie: opts.cookie,
        referer: 'https://y.qq.com/n/ryqq/songDetail/' + songMid,
      });
      const parsed = typeof raw === 'string' ? safeJson(raw) : raw;
      const req1 = parsed && parsed.req_1 ? parsed.req_1 : null;
      const data = req1 && req1.data ? req1.data : null;
      const songInfo = data && (data.track_info || data.songInfo || data.songinfo);
      if (songInfo) {
        const normalized = detailToSong(songInfo);
        if (normalized && normalized.mid) return normalized;
      }
    } catch (err) {
      console.warn('[qq] detail musicu 签名调用失败，回退搜索:', err && err.message);
    }

    // ---- 回退：免签名搜索 ----
    try {
      const res = await search(songMid, 1, 10, opts);
      const exact = (res.list || []).find((s) => s.mid === songMid) || (res.list || [])[0];
      if (exact) return exact;
    } catch (err) {
      console.warn('[qq] detail 搜索回退失败:', err && err.message);
    }

    // ---- 最后兜底：老 song_detail 接口 ----
    try {
      const legacy = await http.getJson('https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg', {
        params: {
          format: 'json',
          songmid: songMid,
          platform: 'yqq.json',
          needNewCode: 0,
          inCharset: 'utf-8',
          outCharset: 'utf-8',
        },
        cookie: opts.cookie,
        referer: `https://y.qq.com/n/yqq/song/${songMid}.html`,
      });
      const parsed = typeof legacy === 'string' ? safeJson(legacy) : legacy;
      const list = (parsed && parsed.data) || [];
      const first = Array.isArray(list) ? list[0] : list;
      if (first) return detailToSong(first);
    } catch (err) {
      console.warn('[qq] detail legacy 回退失败:', err && err.message);
    }

    return null;
  });
}

/* -------------------------------------------------------------------------- */
/* 6. 用户信息                                                                 */
/* -------------------------------------------------------------------------- */

/**
 * 用户信息。主路径：签名 musicu（music.UserInfo.userInfoServer）；
 * 失败回退 c.y.qq.com/rsc/fcgi-bin/fcg_get_user_info.fcg。
 * @param {string} cookie
 * @returns {Promise<{nickname: string, avatar: string, vip: boolean, uin: string}|null>}
 */
async function userInfo(cookie) {
  const ck = cleanCookie(cookie);
  if (!ck || !hasLoginCookie(ck)) return null;

  const uin = sign.parseUinFromCookie(ck);
  const key = `user:${uin || 'anon'}`;
  return http.cached(key, http.TTL.user, async () => {
    // ---- 主路径：签名 musicu ----
    const skey = cookieValue(ck, 'skey') || cookieValue(ck, 'p_skey') || '';
    const comm = sign.buildComm({ cookie: ck, uin });
    comm.g_tk = calcGtk(skey);
    const body = {
      comm,
      req_0: {
        module: 'music.UserInfo.userInfoServer',
        method: 'GetLoginUserInfo',
        param: { userinfo: 1 },
      },
    };
    try {
      const raw = await callMusicu(body, {
        cookie: ck,
        referer: 'https://y.qq.com/n/ryqq/profile',
      });
      const parsed = typeof raw === 'string' ? safeJson(raw) : raw;
      const req0 = parsed && parsed.req_0 ? parsed.req_0 : null;
      const data = req0 && req0.data ? req0.data : null;
      const profile = data && (data.userInfo || data.profile || data.creator || data.visit || null);
      const base = (profile && (profile.base || profile.Base)) || (data && data.base) || profile;
      if (base && (base.nick || base.nickname || base.name)) {
        const vipInfo = (profile && (profile.vipInfo || profile.vip)) || (data && data.vipInfo) || {};
        return {
          nickname: toUtf8String(normalize.pick(base.nick, base.nickname, base.name, '')),
          avatar: toUtf8String(normalize.pick(base.headurl, base.avatar, base.headUrl, '')),
          vip: isVipUser(vipInfo),
          uin: toUtf8String(normalize.pick(base.uin, data && data.uin, uin, '')),
        };
      }
      const code = data && (data.code !== undefined ? data.code : data.result);
      if (code !== undefined && normalize.toInt(code, 0) !== 0) {
        console.log(`[qq] userInfo musicu 返回 code=${code}`);
      }
    } catch (err) {
      console.warn('[qq] userInfo musicu 签名调用失败，回退 legacy:', err && err.message);
    }

    // ---- 回退：legacy（只需 Cookie，无需签名）----
    try {
      const legacy = await http.getJson(URLS.userInfoLegacy, {
        params: {
          format: 'json',
          inCharset: 'utf-8',
          outCharset: 'utf-8',
          notice: 0,
          platform: 'yqq.json',
          needNewCode: 0,
          g_tk: calcGtk(skey),
        },
        cookie: ck,
        referer: 'https://y.qq.com/portal/profile.html',
      });
      const parsed = typeof legacy === 'string' ? safeJson(legacy) : legacy;
      const creator = (parsed && (parsed.data || parsed.creator)) || parsed || {};
      const base = creator.base || creator;
      const nickname = toUtf8String(normalize.pick(base.nick, base.nickname, base.name, ''));
      if (nickname) {
        return {
          nickname,
          avatar: toUtf8String(normalize.pick(base.headurl, base.avatar, base.headUrl, '')),
          vip: isVipUser(creator.vipInfo || creator.vip || {}),
          uin: toUtf8String(normalize.pick(base.uin, uin, '')),
        };
      }
    } catch (err) {
      console.warn('[qq] userInfo legacy 回退失败:', err && err.message);
    }

    return null;
  });
}

/** 判断 vipInfo 结构是否表示会员 */
function isVipUser(vipInfo) {
  if (!vipInfo || typeof vipInfo !== 'object') return false;
  const candidates = [
    vipInfo.vipType,
    vipInfo.VipType,
    vipInfo.vip_type,
    vipInfo.type,
    vipInfo.iVipType,
    vipInfo.vipLevel,
  ];
  for (const c of candidates) {
    if (normalize.toInt(c, 0) > 0) return true;
  }
  if (vipInfo.isVip === true || vipInfo.is_vip === true) return true;
  return false;
}

/* -------------------------------------------------------------------------- */
/* 7. 扫码登录                                                                 */
/* -------------------------------------------------------------------------- */

/**
 * 申请登录二维码。
 * @returns {Promise<{qrsig: string, ptqrtoken: string, cookies: string, qrImageBuffer: Buffer, expireSeconds: number}>}
 */
async function qrCreate() {
  const params = {
    appid: 716027609,
    e: 2,
    l: 'M',
    s: 3,
    d: 72,
    v: 4,
    t: Math.random(),
    daid: 383,
    pt_3rd_aid: 100497308,
  };
  const headers = {
    Referer: 'https://xui.ptlogin2.qq.com/',
    Origin: 'https://xui.ptlogin2.qq.com',
    Accept: 'image/avif,image/webp,image/apng,image/*,*/*;q=0.8',
    'Sec-Fetch-Dest': 'image',
    'Sec-Fetch-Mode': 'no-cors',
    'Sec-Fetch-Site': 'same-site',
  };
  const res = await http.getBinary(URLS.qrShow, {
    params,
    headers,
    referer: 'https://xui.ptlogin2.qq.com/',
    timeout: 10000,
  });
  const setCookies = parseSetCookie(res.headers);
  const qrsigPair = setCookies.find((c) => c.name === 'qrsig');
  const qrsig = qrsigPair ? qrsigPair.value : '';
  if (!qrsig) {
    throw new Error('ptqrshow 未返回 qrsig（可能被风控或网络异常）');
  }
  const cookies = cookiesToString(setCookies);
  const image = unwrapQrcImage(res.buffer);
  return {
    qrsig,
    ptqrtoken: ptqrtoken(qrsig),
    cookies,
    qrImageBuffer: image.data,
    qrImageNote: image.note,
    expireSeconds: 120,
  };
}

/**
 * 轮询二维码状态。
 * @param {string} qrsig
 * @param {string} ptqrtokenValue
 * @returns {Promise<{status: 'waiting'|'scanned'|'confirmed'|'refused'|'expired', cookie: string, redirectUrl: string, uin: string}>}
 */
async function qrCheck(qrsig, ptqrtokenValue) {
  const params = {
    uin: '',
    ptqrtoken: ptqrtokenValue,
    action: '0',
    t: Math.random(),
    daid: 383,
    pt_3rd_aid: 100497308,
  };
  const headers = {
    Referer: 'https://xui.ptlogin2.qq.com/',
    Origin: 'https://xui.ptlogin2.qq.com',
    Accept: 'application/json, text/plain, */*',
    'ptqrtoken': ptqrtokenValue,
  };
  const cookie = `qrsig=${qrsig}`;
  const referer = 'https://xui.ptlogin2.qq.com/';

  let res = null;
  let lastErr = null;
  try {
    res = await http.client.request({
      method: 'GET',
      url: URLS.qrLogin,
      params,
      headers: http.buildHeaders(headers, cookie, referer),
      timeout: 10000,
      validateStatus: null,
      responseType: 'text',
      transformResponse: [(d) => d],
    });
  } catch (err) {
    lastErr = err;
    console.warn('[qq] ptqrlogin 请求失败:', err && err.message);
  }
  if (!res) {
    return { status: 'waiting', cookie: '', redirectUrl: '', uin: '', error: lastErr ? lastErr.message : 'request failed' };
  }

  const text = toUtf8String(res.data || '');
  const codeMatch = text.match(/ptuiCB\(\s*'(\d+)'\s*,\s*'(\d+)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'/);
  if (!codeMatch) {
    console.warn('[qq] ptqrlogin 返回无法解析:', text.slice(0, 200));
    return { status: 'waiting', cookie: '', redirectUrl: '', uin: '', raw: text.slice(0, 200) };
  }

  const code = codeMatch[1];
  const subCode = codeMatch[2];
  const redirectUrl = codeMatch[3] || '';
  const message = codeMatch[5] || '';
  const nickname = codeMatch[6] || '';

  const setCookies = parseSetCookie(res.headers);
  let learnedCookie = cookiesToString(setCookies);

  // 66/67 属于“已扫码 / 已确认但需要继续”，65 过期，68 拒绝，0 成功
  if (code === '65') {
    return { status: 'expired', cookie: '', redirectUrl: '', uin: '', message };
  }
  if (code === '67' || code === '68') {
    // 67: 已扫码待确认；68: 用户拒绝（部分文档里 68 是取消）
    return { status: code === '67' ? 'scanned' : 'refused', cookie: '', redirectUrl, uin: '', message };
  }
  if (code !== '0') {
    // 其它非 0（如 66 未扫码、其它错误）统一视为等待
    if (code === '66' || subCode !== '0') {
      return { status: 'waiting', cookie: '', redirectUrl, uin: '', message };
    }
    return { status: 'waiting', cookie: '', redirectUrl, uin: '', message };
  }

  // 成功：需要再访问一次 redirectUrl 才能补齐 skey / qm_keyst 等关键 cookie。
  // 只跟随指向 qq.com 的地址，避免被带到第三方站点。
  let finalCookie = learnedCookie;
  if (redirectUrl && /^https?:\/\//i.test(redirectUrl) && /(^|\.)qq\.com(\/|:|$)/i.test(redirectUrl)) {
    try {
      const follow = await http.client.request({
        method: 'GET',
        url: redirectUrl,
        headers: http.buildHeaders({}, finalCookie, referer),
        timeout: 10000,
        maxRedirects: 0,
        validateStatus: null,
        responseType: 'text',
        transformResponse: [(d) => d],
      });
      const more = parseSetCookie(follow.headers);
      if (more.length) finalCookie = mergeCookies(finalCookie, cookiesToString(more));
    } catch (err) {
      console.warn('[qq] 跟随登录跳转失败（cookie 可能不完整）:', err && err.message);
    }
  }

  // 有时候 redirectUrl 里带着 uin，可以补进 cookie 串
  let uin = sign.parseUinFromCookie(finalCookie);
  if (uin === '0' && redirectUrl) {
    const m = redirectUrl.match(/[?&]uin=([^&]+)/);
    if (m) uin = decodeURIComponent(m[1]).replace(/^o/, '').replace(/\D/g, '') || '0';
  }
  if (uin && uin !== '0' && !cookieValue(finalCookie, 'uin')) {
    finalCookie = mergeCookies(finalCookie, `uin=o${uin}`);
  }

  return {
    status: 'confirmed',
    cookie: finalCookie,
    redirectUrl,
    uin,
    nickname,
    message,
  };
}

module.exports = {
  URLS,
  QUALITY_LADDER,
  QUALITY_LABEL,
  QUALITY_PREFIX,
  DEFAULT_URL_TTL_SECONDS,
  MAX_URL_TTL_SECONDS,
  MIN_URL_TTL_SECONDS,
  normalizeExpiration,
  safeJson,
  cleanCookie,
  parseSetCookie,
  cookiesToString,
  mergeCookies,
  cookieValue,
  hasLoginCookie,
  calcGtk,
  callMusicu,
  search,
  fetchTopList,
  recommendSongs,
  songUrls,
  lyric,
  detail,
  userInfo,
  qrCreate,
  qrCheck,
};
