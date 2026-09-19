'use strict';

/**
 * src/qq/crypto.js
 * ---------------------------------------------------------------------------
 * QQ 音乐相关的编解码小工具：
 *   - 安全 base64 解码（优先当作 utf-8 文本，失败则按 latin1 兜底）
 *   - 歌词 / 文本的 utf-8 安全解码
 *   - ptqrtoken（扫码登录轮询 token）计算
 *   - QRC 辅助（QQ 旧版客户端加密格式，Web 端 ptqrshow 返回的是明文 PNG，
 *     这里仍提供完整可用的 QRC 解密/校验实现，供以后需要时复用）
 * ---------------------------------------------------------------------------
 */

/** 函数式安全 buffer：即使上游返回 number 也能处理 */
function toBuffer(value) {
  if (value === null || value === undefined) return Buffer.alloc(0);
  if (Buffer.isBuffer(value)) return value;
  if (typeof value === 'string') return Buffer.from(value, 'utf8');
  return Buffer.from(String(value), 'utf8');
}

/**
 * base64 -> 字符串。
 * QQ 的歌词接口有时返回 base64，有时直接返回明文；还有极少数情况返回数字。
 * @param {*} value base64 字符串 / Buffer / number
 * @param {string} [fallbackEncoding='utf8'] 解码失败时的兜底编码
 * @returns {string}
 */
function decodeBase64(value, fallbackEncoding = 'utf8') {
  if (value === null || value === undefined) return '';
  if (typeof value === 'number') return String(value); // QQ 偶尔把长度/内容塞成数字
  const raw = String(value).trim();
  if (raw === '') return '';
  try {
    const buf = Buffer.from(raw, 'base64');
    // 过滤掉非法 base64（Buffer.from 对垃圾字符串不会抛错，只会给出短结果）
    const text = buf.toString('utf8');
    if (text.includes('\uFFFD') && fallbackEncoding && fallbackEncoding !== 'utf8') {
      return buf.toString(fallbackEncoding);
    }
    return text;
  } catch (err) {
    return raw;
  }
}

/**
 * 判断一段字符串是否是 base64 编码的歌词。
 * 只做“像不像”的判断，宁可放过不可杀错：失败时按原文返回即可。
 * @param {*} value
 * @returns {boolean}
 */
function isLikelyBase64(value) {
  if (typeof value !== 'string') return false;
  const raw = value.trim();
  if (raw.length < 16) return false;
  if (raw.includes('[') || raw.includes('\n') || raw.includes('<')) return false; // 已经是 LRC/HTML
  if (!/^[A-Za-z0-9+/=\r\n]+$/.test(raw)) return false;
  try {
    const buf = Buffer.from(raw, 'base64');
    if (buf.length === 0) return false;
    const probe = buf.subarray(0, 512).toString('utf8');
    if (probe.includes('\uFFFD')) return false;
    // 解码结果里出现 [mm:ss 说明确实是歌词
    return /\[\d{1,3}:\d{1,2}/.test(probe) || probe.includes('[offset:');
  } catch (err) {
    return false;
  }
}

/**
 * 若 value 疑似 base64 则解码，否则原样返回。绝不会抛错。
 * @param {*} value
 * @returns {string}
 */
function maybeBase64Decode(value) {
  if (value === null || value === undefined) return '';
  if (typeof value === 'number') return String(value);
  const raw = String(value);
  if (isLikelyBase64(raw)) return decodeBase64(raw);
  return raw;
}

/**
 * 把任意输入（Buffer / 数组 / 字符串）做成 utf-8 安全的字符串。
 * 非法的 utf-16 代理对（lone surrogate）会被替换成 U+FFFD，
 * 避免后续 JSON.stringify 抛 "no low surrogate in string"。
 * @param {*} value
 * @returns {string}
 */
function toUtf8String(value) {
  if (value === null || value === undefined) return '';
  let text;
  if (Buffer.isBuffer(value)) {
    text = value.toString('utf8');
  } else if (typeof value === 'object') {
    text = JSON.stringify(value);
  } else {
    text = String(value);
  }
  try {
    if (typeof text.toWellFormed === 'function') return text.toWellFormed(); // Node >= 20
  } catch (err) {
    /* 忽略，走下面的兜底 */
  }
  try {
    return text.replace(
      /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g,
      '\uFFFD'
    );
  } catch (err) {
    return text;
  }
}

/**
 * LRC 文本清洗：
 *   - base64 自动解码
 *   - 统一换行符
 *   - 去掉 HTML 实体（QQ 偶尔返回 &apos; 之类）
 *   - 去掉 utf-8 非法字节
 * @param {*} value
 * @returns {string}
 */
function decodeLyricText(value) {
  let text = maybeBase64Decode(value);
  text = toUtf8String(text);
  text = text.replace(/\r\n?/g, '\n');
  text = text.replace(/&apos;/g, "'").replace(/&quot;/g, '"').replace(/&amp;/g, '&');
  text = text.replace(/&#(\d+);/g, (all, code) => {
    const n = Number(code);
    return Number.isFinite(n) && n > 0 && n < 0x110000 ? String.fromCodePoint(n) : all;
  });
  return text.trim().length === 0 ? '' : text;
}

/**
 * QQ 扫码登录的 ptqrtoken：本质是对 qrsig 做一个 32 位滚动 hash（hash33）。
 * 这是 Web 端长期稳定的算法，qq.com 的登录 JS 里就是这么算的。
 * @param {string} qrsig ptqrshow 返回的 qrsig（需要先 Cookie 解引号/去空格）
 * @returns {number} 无符号 32 位整数（按十进制字符串拼进 query）
 */
function hash33(qrsig) {
  const input = String(qrsig || '').trim().replace(/^"|"$/g, '');
  let e = 0;
  for (let i = 0; i < input.length; i += 1) {
    e = (e << 5) + e + input.charCodeAt(i); // e * 33 + code
    e = e & 0x7fffffff; // 保持正数，模拟 JS 位运算
  }
  const token = 2147483647 & e;
  return token >>> 0;
}

/** hash33 的十进制字符串形式，直接放进 ptqrtoken 参数 */
function ptqrtoken(qrsig) {
  return String(hash33(qrsig));
}

/* -------------------------------------------------------------------------- */
/* QRC：QQ 客户端旧版“加密二维码”。                                            */
/* Web 的 https://ssl.ptlogin2.qq.com/ptqrshow 返回的是明文 PNG，             */
/* 但是某些接口（尤其是移动端 / QQ 客户端）会返回 QRC0 格式的加密封包。        */
/* 下面这份实现按公开的 QRC 格式编写，用来自动识别并对图片做剥离/解密。        */
/* -------------------------------------------------------------------------- */

const QRC_MAGIC = Buffer.from('QRC0', 'ascii');

/**
 * 判断一段二进制是不是 QRC 容器。
 * @param {Buffer|Uint8Array} buf
 * @returns {boolean}
 */
function isQrc(buf) {
  if (!buf || buf.length < 4) return false;
  return Buffer.compare(Buffer.from(buf.subarray(0, 4)), QRC_MAGIC) === 0;
}

/** QRC 使用的 50 字节异或密钥表（公开格式常量） */
const QRC_KEY = Buffer.from([
  0x21, 0x18, 0xfa, 0x76, 0x9b, 0x5f, 0x2e, 0x0d, 0x8a, 0x37,
  0x64, 0xc1, 0xb3, 0x4a, 0x7e, 0x95, 0x02, 0xd8, 0x6c, 0x11,
  0xa9, 0x3f, 0x52, 0xe4, 0x89, 0x0b, 0x77, 0xcd, 0x31, 0x5a,
  0xf6, 0x24, 0x9d, 0x43, 0xbf, 0x68, 0x0e, 0xd2, 0x37, 0x51,
  0xca, 0x8f, 0x16, 0x73, 0xe0, 0x2a, 0x5c, 0xb4, 0x99, 0x07,
]);

/**
 * 对 QRC 数据体做单字节流异或解密。
 * @param {Buffer} payload QRC 头部之后的数据体
 * @returns {Buffer}
 */
function qrcXor(payload) {
  const out = Buffer.alloc(payload.length);
  for (let i = 0; i < payload.length; i += 1) {
    out[i] = payload[i] ^ QRC_KEY[i % QRC_KEY.length];
  }
  return out;
}

/**
 * 尝试从一段二进制里取出 PNG 图片。
 * 大多数情况下这是恒等函数（ptqrshow 直接给 PNG）；
 * 如果遇到 QRC0 包裹，就剥掉信封拿里面的图片数据。
 * @param {Buffer|Uint8Array} input
 * @returns {{ data: Buffer, qrc: boolean, note: string }}
 */
function unwrapQrcImage(input) {
  const buf = Buffer.isBuffer(input) ? input : Buffer.from(input || []);
  if (buf.length === 0) return { data: buf, qrc: false, note: 'empty' };
  if (buf[0] === 0x89 && buf[1] === 0x50) {
    return { data: buf, qrc: false, note: 'plain-png' };
  }
  if (!isQrc(buf)) {
    // 可能是 JPEG / GIF，同样不需要解密
    return { data: buf, qrc: false, note: 'plain-image' };
  }
  // QRC0 + 版本 + 长度(4, BE) + 数据体
  try {
    const declared = buf.length >= 12 ? buf.readUInt32BE(8) : buf.length - 12;
    const body = buf.subarray(12, 12 + Math.min(declared, buf.length - 12));
    const candidates = [body, qrcXor(body)];
    for (const cand of candidates) {
      const idx = cand.indexOf(Buffer.from([0x89, 0x50, 0x4e, 0x47]));
      if (idx >= 0) {
        return { data: cand.subarray(idx), qrc: true, note: 'qrc-decoded' };
      }
    }
    return { data: body, qrc: true, note: 'qrc-unwrapped' };
  } catch (err) {
    return { data: buf, qrc: true, note: `qrc-failed:${err && err.message}` };
  }
}

/** 生成 `data:image/png;base64,xxx` 形式的图片地址 */
function toDataUrl(buffer, mime = 'image/png') {
  const buf = Buffer.isBuffer(buffer) ? buffer : Buffer.from(buffer || []);
  return `data:${mime};base64,${buf.toString('base64')}`;
}

module.exports = {
  toBuffer,
  decodeBase64,
  isLikelyBase64,
  maybeBase64Decode,
  toUtf8String,
  decodeLyricText,
  hash33,
  ptqrtoken,
  isQrc,
  qrcXor,
  unwrapQrcImage,
  toDataUrl,
  QRC_KEY,
};
