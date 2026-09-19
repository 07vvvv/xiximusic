'use strict';

/**
 * src/qq/normalize.js
 * ---------------------------------------------------------------------------
 * 把 QQ 音乐各种形态的原始对象（搜索 / 榜单 / musicu 模块 / vkey 接口）
 * 统一转换成客户端固定的 Song 结构：
 *
 * {
 *   id, mid, name, singer, singers: [{id, mid, name}],
 *   album, albumMid, cover, duration, vip,
 *   pay: { play, download }, quality: ["128","320","flac"], interval
 * }
 * ---------------------------------------------------------------------------
 */

const { toUtf8String } = require('./crypto');

/** 兜底封面（没有 albumMid 时用，客户端自己会处理加载失败） */
const PLACEHOLDER_COVER =
  'https://y.qq.com/mediastyle/global/img/album_300.png?max_age=31536000';

/** 取第一个有值的字段 */
function pick() {
  for (let i = 0; i < arguments.length; i += 1) {
    const v = arguments[i];
    if (v !== undefined && v !== null && v !== '') return v;
  }
  return undefined;
}

/** 数字安全转换 */
function toInt(value, fallback = 0) {
  const n = Number(value);
  return Number.isFinite(n) ? Math.trunc(n) : fallback;
}

/**
 * 根据 albumMid 生成 500x500 封面地址。
 * @param {string} albumMid
 * @returns {string}
 */
function coverUrl(albumMid) {
  const mid = albumMid ? String(albumMid).trim() : '';
  if (!mid) return PLACEHOLDER_COVER;
  return `https://y.qq.com/music/photo_new/T002R500x500M000${mid}.jpg`;
}

/**
 * 把 QQ 的多种 singer 结构拍平成 [{id, mid, name}]。
 * 支持：
 *   - [{ id, mid, name, title }]
 *   - [{ id, mid, name }]
 *   - ["歌手A", "歌手B"]
 *   - 单个对象 / 单个字符串
 * @param {*} raw
 * @returns {Array<{id: (string|number), mid: string, name: string}>}
 */
function normalizeSingers(raw) {
  let arr = raw;
  if (arr && !Array.isArray(arr)) arr = [arr];
  if (!Array.isArray(arr)) return [];
  const out = [];
  for (const item of arr) {
    if (!item) continue;
    if (typeof item === 'string') {
      const name = toUtf8String(item).trim();
      if (name) out.push({ id: '', mid: '', name });
      continue;
    }
    if (typeof item !== 'object') continue;
    const name = toUtf8String(pick(item.name, item.title, item.singer_name, '')).trim();
    const mid = toUtf8String(pick(item.mid, item.singer_mid, item.singerMid, '')).trim();
    const id = pick(item.id, item.singer_id, item.singerId, 0);
    if (!name && !mid) continue;
    out.push({ id: id === undefined ? '' : id, mid, name });
  }
  return out;
}

/** singers -> "歌手A/歌手B" */
function joinSinger(singers) {
  if (!Array.isArray(singers) || singers.length === 0) return '';
  return singers
    .map((s) => (s && s.name ? String(s.name).trim() : ''))
    .filter((n) => n.length > 0)
    .join('/');
}

/**
 * 从 sizeXXX 字段推断可用音质列表。
 * @param {object} rawFile
 * @returns {string[]}
 */
function inferQuality(rawFile) {
  const out = [];
  if (!rawFile || typeof rawFile !== 'object') return out;
  const has = (v) => Number(v) > 0;
  if (has(pick(rawFile.size128, rawFile.size_128))) out.push('128');
  if (has(pick(rawFile.size320, rawFile.size_320))) out.push('320');
  if (has(pick(rawFile.sizeflac, rawFile.size_flac))) out.push('flac');
  if (has(pick(rawFile.sizeape, rawFile.size_ape))) out.push('ape');
  if (out.length) return out;
  // 有些接口只给 sizeNew / size，无法判断音质，给最常见的组合
  const anySize = has(pick(rawFile.size, rawFile.sizeNew));
  return anySize ? ['128', '320'] : out;
}

/**
 * 主转换函数：raw QQ song -> 标准 Song。
 * 兼容的输入形态：
 *   1. 搜索接口 data.song.list[]
 *   2. 榜单接口 data.songlist[]（带 data.albummid / data.singer / data.songmid）
 *   3. musicu 模块 songlist[] / v_singer[] 等
 * @param {object} raw
 * @returns {object|null} 无法识别时返回 null
 */
function normalizeSong(raw) {
  if (!raw || typeof raw !== 'object') return null;

  // 部分接口把真正的歌曲对象塞在 data / musicData 里
  const base = raw.data && typeof raw.data === 'object' && (raw.data.songmid || raw.data.mid)
    ? raw.data
    : raw;

  const file = base.file || base.size || base.fileSize || raw.file || {};

  // 有些接口（未开启 new_json 的搜索、部分老 CGI）把 size128/size320 直接挂在
  // 歌曲对象根上，而不是嵌在 file 里 —— 这里做兼容，否则 advertised quality 会丢。
  const sizeSource = Object.assign({}, file);
  for (const k of ['size128', 'size320', 'sizeflac', 'sizeape', 'size', 'sizeNew', 'size_128', 'size_320', 'size_flac', 'size_ape']) {
    if (sizeSource[k] === undefined && base[k] !== undefined) sizeSource[k] = base[k];
    if (sizeSource[k] === undefined && raw[k] !== undefined) sizeSource[k] = raw[k];
  }

  const mid = toUtf8String(
    pick(base.mid, base.songmid, base.songMid, base.song_mid, raw.mid, raw.songmid, '')
  ).trim();

  const name = toUtf8String(
    pick(base.name, base.songname, base.songName, base.title, base.songorig, raw.name, raw.songname, '')
  ).trim();

  if (!mid && !name) return null;

  const singers = normalizeSingers(
    pick(base.singer, base.singers, base.singer_list, base.singerList, raw.singer, raw.singers, [])
  );
  const singer = joinSinger(singers);

  const albumMid = toUtf8String(
    pick(base.albummid, base.albumMid, base.album_mid, base.album?.mid, raw.albummid, raw.albumMid, '')
  ).trim();
  const album = toUtf8String(
    pick(base.albumname, base.albumName, base.album_name, base.album?.name, base.album?.title, raw.albumname, raw.albumName, '')
  ).trim();

  const interval = toInt(
    pick(base.interval, base.duration, base.songinterval, base.timelen, raw.interval, 0),
    0
  );

  const payInfo = pick(base.pay, base.payInfo, base.pay_info, raw.pay, {}) || {};
  const playFlag = toInt(pick(payInfo.play, payInfo.pay_play, payInfo.payplay, 0), 0);
  const downloadFlag = toInt(pick(payInfo.download, payInfo.pay_down, payInfo.paydownload, 0), 0);

  // 只有「播放」受限才算 VIP 歌曲：
  //   pay.play  > 0  -> 必须会员才能播放
  //   pay.play == 0  -> 非会员可播放（即使 download > 0 只是不能下载，
  //                     不能因此把免费歌标成 VIP，否则会误报「需要登录」）
  // 另兼容显式 vip 字段（部分接口返回 vip: 1 / 付费专辑标记）。
  const vip = playFlag > 0 || toInt(pick(base.vip, raw.vip, 0), 0) === 1;

  const quality = inferQuality(sizeSource);

  return {
    id: mid || toUtf8String(pick(base.songid, base.id, name)).trim(),
    mid,
    name: name || '未知歌曲',
    singer,
    singers,
    album,
    albumMid,
    cover: coverUrl(albumMid),
    duration: interval,
    vip,
    pay: { play: playFlag, download: downloadFlag },
    quality,
    interval,
  };
}

/**
 * 批量转换并过滤空值。
 * @param {*} list
 * @returns {object[]}
 */
function normalizeSongList(list) {
  if (!Array.isArray(list)) return [];
  const out = [];
  const seen = new Set();
  for (const item of list) {
    const song = normalizeSong(item);
    if (!song) continue;
    const key = song.mid || song.id;
    if (key && seen.has(key)) continue; // 去重（榜单里会有重复）
    if (key) seen.add(key);
    out.push(song);
  }
  return out;
}

/**
 * 把 vkey 接口返回的文件名（如 "M5000039MnYb0qxYhV.mp3"）翻译成音质标识。
 * @param {string} filename
 * @returns {{ quality: string|null, ext: string }}
 */
function qualityFromFilename(filename) {
  const name = toUtf8String(filename || '').trim();
  const ext = (name.split('.').pop() || '').toLowerCase();
  const prefix = name.slice(0, 4).toUpperCase();
  const map = {
    F000: 'flac',
    A000: 'ape',
    M800: '320',
    M500: '128',
    C400: '128', // m4a
    O600: '128',
    RS01: '128',
  };
  if (map[prefix]) return { quality: map[prefix], ext };
  if (ext === 'flac') return { quality: 'flac', ext };
  if (ext === 'ape') return { quality: 'ape', ext };
  if (ext === 'mp3') return { quality: '128', ext };
  if (ext === 'm4a') return { quality: '128', ext };
  return { quality: null, ext };
}

/**
 * 根据直链文件名 / 扩展名做“实际音质”的二次校验，
 * 避免请求 flac 却拿到 128k 的情况被当成 flac 返回。
 * @param {string} requested 请求的音质
 * @param {string} filename 返回的文件名
 * @param {string} url 返回的直链
 * @returns {string} 实际音质
 */
function resolveRealQuality(requested, filename, url) {
  const fromFile = qualityFromFilename(filename);
  if (fromFile.quality) return fromFile.quality;
  const lower = toUtf8String(url || '').toLowerCase();
  if (lower.includes('.flac')) return 'flac';
  if (lower.includes('.ape')) return 'ape';
  if (lower.includes('.m4a')) return '128';
  if (lower.includes('.mp3')) return requested === 'flac' || requested === 'ape' ? '320' : requested;
  return requested;
}

module.exports = {
  PLACEHOLDER_COVER,
  pick,
  toInt,
  coverUrl,
  normalizeSingers,
  joinSinger,
  inferQuality,
  normalizeSong,
  normalizeSongList,
  qualityFromFilename,
  resolveRealQuality,
};
