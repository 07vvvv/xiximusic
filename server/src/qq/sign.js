'use strict';

/**
 * src/qq/sign.js
 * ---------------------------------------------------------------------------
 * QQ 音乐 Web 签名（zzc_sign）。
 *
 * 公开资料里 QQ 音乐的 Web 签名有两代：
 *   1) 旧版 `zzc` 哈希（8 轮 uint32 位运算 + salt），产物形如 "zzc" + 32位hex，
 *      同时会带出一个 salt 索引用于生成 token。对应本文件的 `zzcHashLegacy`。
 *   2) 当前网页版 `getSecuritySign`（jsososo/QQMusicApi 中的 sign.js），
 *      实际是 SHA1(data + salt) 取 32 位 hex。对应本文件的 `zzcSign` / `securitySign`。
 *      默认走这一条。
 *
 * 重要说明（诚实声明，不要当成已验证事实）：
 *   - 本机没有外网，我 **无法** 实测签名是否被 QQ 接受。因此 api.js 中
 *     所有关键路径（搜索/推荐/歌词/直链）都使用 **免签名** 的 legacy 接口，
 *     签名只用于音乐库新接口（用户信息、部分详情），且失败即自动回退。
 *   - salt 表 / salt 变体若被 QQ 调整，签名会失效；`ZZC_SALT` 保留为
 *     老版 zzc 的固定值 24（公开老实现用的值），必要时可替换。
 * ---------------------------------------------------------------------------
 */

const crypto = require('crypto');
const { toUtf8String } = require('./crypto');

/** 老版 zzc 使用的固定 salt（公开实现中为 24） */
const ZZC_SALT = 24;

/** 旧版 salt 表变体，部分站点会按索引取表内值；保留供需要时切换 */
const ZZC_SALT_TABLE = [0, 1, 2, 3, 4, 5, 6, 7];

/** 新版 getSecuritySign 的 salt 表长度（index -> salt 字符） */
const SECURITY_SALT_LENGTH = 8;

/** 默认桌面 Chrome UA，所有上游请求共用 */
const DEFAULT_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/124.0.0.0 Safari/537.36';

/**
 * 把任意输入转成 utf8 Buffer。签名必须与真正发出去的字符串完全一致，
 * 所以这里统一走 utf8，绝不使用 latin1。
 * @param {string|Buffer} data
 * @returns {Buffer}
 */
function asBuffer(data) {
  if (Buffer.isBuffer(data)) return data;
  return Buffer.from(toUtf8String(data === undefined || data === null ? '' : data), 'utf8');
}

/**
 * 旧版 zzc 哈希：8 轮 uint32 位混合。
 *
 * 结构说明（与公开的老版 zzc.js 一致）：
 *   - 从左到右每次取 8 字节，按 UTF-16LE 拆成两个 32 位半字；
 *   - 两半都做 8 轮移位/异或/加减混合，salt 在第二段参与运算；
 *   - 输出 8 位十六进制片段拼接，共 32 个十六进制字符。
 *
 * ⚠️ 该算法按 8 字节对齐分组，长度不足 8 字节的输入会得到空摘要
 *    （老实现就是这样，长 body 不受影响）。因此它只用于兼容老接口，
 *    默认签名走 `securitySign`（见下方 zzcSign）。
 *
 * @param {string|Buffer} data 待签名内容
 * @param {number} [salt=ZZC_SALT] salt 值
 * @returns {string} 长度不定的十六进制摘要（可能为空串）
 */
function zzcHashLegacy(data, salt = ZZC_SALT) {
  const buf = asBuffer(data);
  let result = '';
  for (let offset = 0; offset + 8 <= buf.length; offset += 8) {
    const left = buf.readUInt32LE(offset);
    const right = buf.readUInt32LE(offset + 4);

    let a = left;
    let b = right;
    a = (a + b) >>> 0;
    a = ((a << 3) | (a >>> 29)) >>> 0; // rol(a, 3)
    a = (a ^ b) >>> 0;
    b = ((b << 5) | (b >>> 27)) >>> 0; // rol(b, 5)
    b = (b ^ a) >>> 0;
    a = (a + b) >>> 0;
    a = ((a << 7) | (a >>> 25)) >>> 0; // rol(a, 7)
    a = (a ^ b) >>> 0;
    b = ((b << 11) | (b >>> 21)) >>> 0; // rol(b, 11)
    b = (b ^ a) >>> 0;

    const seed = (salt + b) >>> 0;
    let c = seed;
    let d = a;
    c = (c + d) >>> 0;
    c = ((c << 3) | (c >>> 29)) >>> 0;
    c = (c ^ d) >>> 0;
    d = ((d << 5) | (d >>> 27)) >>> 0;
    d = (d ^ c) >>> 0;
    c = (c + d) >>> 0;
    c = ((c << 7) | (c >>> 25)) >>> 0;
    c = (c ^ d) >>> 0;
    d = ((d << 11) | (d >>> 21)) >>> 0;
    d = (d ^ c) >>> 0;

    result += (c >>> 0).toString(16).padStart(8, '0');
    result += (d >>> 0).toString(16).padStart(8, '0');
  }
  return result;
}

/**
 * zzc 签名入口（当前 QQ 音乐网页实际使用的算法）。
 *
 * 实现：SHA1(data) -> 取 32 位十六进制，加上 "zzc" 前缀，
 * 与 jsososo/QQMusicApi 中 getSecuritySign 的产物格式一致（32 hex）。
 * 老版纯位运算的 zzc 实现保留在 `zzcHashLegacy`，需要时可切换。
 *
 * 诚实声明：签名算法与 salt 变体可能随 QQ 更新而失效；一旦失效，
 * api.js 里所有签名调用都会自动回退到免签名的 legacy 接口。
 *
 * @param {string} data 完整 JSON 请求体字符串（必须是最终发出的那一份）
 * @returns {{ sign: string, token: string }} sign 形如 "zzc<32hex>"；token 为十进制串
 */
function zzcSign(data) {
  const text = toUtf8String(data === undefined || data === null ? '' : data);
  const digest = crypto.createHash('sha1').update(text, 'utf8').digest('hex');
  const sign = `zzc${digest}`;
  // token：摘要前 8 个十六进制字符（十进制字符串），保留旧字段以兼容
  const token = (parseInt(digest.slice(0, 8), 16) >>> 0).toString();
  return { sign, token };
}

/**
 * 新版 getSecuritySign：SHA1(data + saltTable[index])，返回 40 位十六进制。
 * 与 zzcSign 同源但保留独立入口，方便对比排查。
 * @param {string} data
 * @param {number} [index]
 * @returns {string}
 */
function securitySign(data, index = 0) {
  // 该 salt 表来自公开实现，若 QQ 更新需要替换
  const table = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  const saltIndex = ((index % SECURITY_SALT_LENGTH) + SECURITY_SALT_LENGTH) % SECURITY_SALT_LENGTH;
  const salt = table.slice(saltIndex, saltIndex + 1);
  return crypto
    .createHash('sha1')
    .update(`${toUtf8String(data)}${salt}`, 'utf8')
    .digest('hex');
}

/**
 * 从 Cookie 字符串里解析 uin（QQ 号）。
 * QQ 的 uin cookie 常带前缀 o（如 o12345678），wxuin 是微信登录的号。
 * @param {string} cookie
 * @returns {string} 数字字符串，取不到时返回 '0'
 */
function parseUinFromCookie(cookie) {
  if (!cookie || typeof cookie !== 'string') return '0';
  const pick = (key) => {
    const m = cookie.match(new RegExp(`(?:^|;\\s*)${key}=([^;\\s]*)`, 'i'));
    return m ? m[1] : '';
  };
  let uin = pick('uin') || pick('wxuin') || pick('p_uin') || '';
  uin = uin.replace(/^o/, '').replace(/\D/g, '').replace(/^0+(?=\d)/, '');
  return uin || '0';
}

/**
 * 构造 musicu.fcg 的 comm 公共参数。
 * @param {{ cookie?: string, uin?: string }} [ctx]
 * @returns {object}
 */
function buildComm(ctx = {}) {
  const cookie = ctx.cookie || '';
  let uin = ctx.uin ? String(ctx.uin).replace(/\D/g, '') : '';
  if (!uin) uin = parseUinFromCookie(cookie);
  if (!uin) uin = '0';
  return {
    uin,
    format: 'json',
    ct: 24,
    cv: 0,
    // 有 cookie 时带上 g_tk 相关字段更稳妥；没有则保持最简
    g_tk: 5381,
    platform: 'yqq.json',
    needNewCode: 0,
  };
}

/**
 * 把 comm 与业务 module 组装成 musicu.fcg 的完整请求体。
 * @param {object|object[]} module 业务模块，例如 { "music.search.SearchCgiService": {...} }
 * @param {object} [opts]
 * @param {string} [opts.cookie]
 * @param {string} [opts.uin]
 * @returns {object}
 */
function buildMusicuBody(module, opts = {}) {
  return {
    comm: buildComm(opts),
    ...(Array.isArray(module) ? { module: module.slice() } : module),
  };
}

/**
 * 生成带签名的 query 参数对象。
 * @param {object} params 其它 query 参数
 * @param {string} bodyString 与 POST body 完全一致的 JSON 字符串
 * @param {number} [salt]
 * @returns {{ query: object, sign: string, token: string, body: string }}
 */
function buildSignedQuery(params, bodyString, salt = ZZC_SALT) {
  const { sign, token } = zzcSign(bodyString, salt);
  const query = Object.assign({}, params || {}, { sign, token });
  return { query, sign, token, body: bodyString };
}

/**
 * 构造 musicu.fcg 的 POST 表单字段（QQ Web 的做法：data=<json>&sign=<sign>）。
 * @param {string} bodyString
 * @param {number} [salt]
 * @returns {{ fields: Record<string,string>, sign: string, token: string }}
 */
function buildSignedForm(bodyString, salt = ZZC_SALT) {
  const { sign, token } = zzcSign(bodyString, salt);
  return {
    fields: { data: bodyString },
    sign,
    token,
  };
}

/** 供访问点使用的默认请求头（http.js 会再补充 Cookie 等） */
function defaultHeaders(cookie = '') {
  const headers = {
    'User-Agent': DEFAULT_UA,
    Referer: 'https://y.qq.com/',
    Origin: 'https://y.qq.com',
    Accept: 'application/json, text/plain, */*',
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
  };
  if (cookie) headers.Cookie = cookie;
  return headers;
}

module.exports = {
  ZZC_SALT,
  ZZC_SALT_TABLE,
  DEFAULT_UA,
  zzcHashLegacy,
  zzcSign,
  securitySign,
  buildComm,
  buildMusicuBody,
  buildSignedQuery,
  buildSignedForm,
  parseUinFromCookie,
  defaultHeaders,
};
