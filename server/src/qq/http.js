'use strict';

/**
 * src/qq/http.js
 * ---------------------------------------------------------------------------
 * 上游 HTTP 层：
 *   - 单例 axios 实例（8s 超时、桌面 Chrome UA、y.qq.com Referer/Origin）
 *   - Cookie 透传（把客户端登录态原样带给 QQ，VIP 歌曲才有 vkey）
 *   - 网络错误 / 5xx 自动重试一次（300ms 退避）
 *   - 进程内 LRU 缓存（Map，容量上限 300，逐条 TTL）
 * ---------------------------------------------------------------------------
 */

const axios = require('axios');
const qs = require('qs');
const { DEFAULT_UA } = require('./sign');
const { toUtf8String } = require('./crypto');

/** 默认超时 8 秒 */
const DEFAULT_TIMEOUT = 8000;
/** 重试次数（1 次重试 = 最多 2 次请求） */
const RETRY_TIMES = 1;
/** 重试退避毫秒 */
const RETRY_BACKOFF = 300;
/** 缓存容量上限 */
const CACHE_MAX = 300;
/** 缓存 TTL（毫秒） */
const TTL = {
  search: 60 * 1000, // 搜索 60s
  lyric: 60 * 1000, // 歌词 60s
  detail: 60 * 1000, // 详情 60s
  recommend: 60 * 1000, // 推荐 60s
  url: 100 * 1000, // 播放地址 100s（必须 <= 120s，防止返回过期直链）
  topList: 10 * 60 * 1000, // 榜单 10 分钟
  user: 2 * 60 * 1000, // 用户信息 2 分钟
};

/* -------------------------------------------------------------------------- */
/* LRU + TTL 缓存                                                              */
/* -------------------------------------------------------------------------- */

class TtlCache {
  constructor(max = CACHE_MAX) {
    this.max = max;
    this.map = new Map();
  }

  get(key) {
    if (!key) return undefined;
    const hit = this.map.get(key);
    if (!hit) return undefined;
    if (hit.expireAt <= Date.now()) {
      this.map.delete(key);
      return undefined;
    }
    // 触摸：重新插入以维护 LRU 顺序
    this.map.delete(key);
    this.map.set(key, hit);
    return hit.value;
  }

  /** 取缓存条目（含 expireAt），供 /song/urls 计算真实剩余有效期 */
  getEntry(key) {
    if (!key) return undefined;
    const hit = this.map.get(key);
    if (!hit) return undefined;
    if (hit.expireAt <= Date.now()) {
      this.map.delete(key);
      return undefined;
    }
    this.map.delete(key);
    this.map.set(key, hit);
    return hit;
  }

  set(key, value, ttlMs) {
    if (!key) return value;
    const ttl = Number.isFinite(ttlMs) && ttlMs > 0 ? ttlMs : TTL.detail;
    if (this.map.has(key)) this.map.delete(key);
    this.map.set(key, { value, expireAt: Date.now() + ttl, storedAt: Date.now() });
    while (this.map.size > this.max) {
      const oldest = this.map.keys().next();
      if (oldest.done) break;
      this.map.delete(oldest.value);
    }
    return value;
  }

  del(key) {
    this.map.delete(key);
  }

  clear() {
    this.map.clear();
  }

  get size() {
    return this.map.size;
  }
}

const cache = new TtlCache(CACHE_MAX);

/** 并发去重：同一 key 的请求共用一次上游调用 */
const inflight = new Map();

/* -------------------------------------------------------------------------- */
/* axios 实例                                                                  */
/* -------------------------------------------------------------------------- */

const client = axios.create({
  timeout: DEFAULT_TIMEOUT,
  maxRedirects: 5,
  // 自己判断状态码，便于重试逻辑
  validateStatus: null,
  // QQ 有时返回 text/html 里嵌 JSON，统一按文本拿回来自己解析
  responseType: 'text',
  transformResponse: [(d) => d],
  decompress: true,
});

/** 判断是否值得重试：网络错误、超时、5xx、429 */
function isRetryable(err) {
  if (!err) return false;
  if (err.response) {
    const s = err.response.status;
    return s >= 500 || s === 429;
  }
  // 无 response => 网络层错误（DNS / ECONNRESET / 超时）
  return true;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 带重试的请求。
 * @param {object} config axios 配置
 * @returns {Promise<import('axios').AxiosResponse>}
 */
async function requestWithRetry(config) {
  let lastErr;
  for (let attempt = 0; attempt <= RETRY_TIMES; attempt += 1) {
    try {
      const res = await client.request(config);
      if (res && res.status >= 500 && attempt < RETRY_TIMES) {
        await sleep(RETRY_BACKOFF);
        continue;
      }
      return res;
    } catch (err) {
      lastErr = err;
      if (attempt >= RETRY_TIMES || !isRetryable(err)) break;
      await sleep(RETRY_BACKOFF);
    }
  }
  throw lastErr || new Error('upstream request failed');
}

/**
 * 组装请求头。
 * @param {object} extra 额外/覆盖头
 * @param {string} cookie 客户端 Cookie
 * @param {string} referer 覆盖 Referer
 * @returns {object}
 */
function buildHeaders(extra = {}, cookie = '', referer = 'https://y.qq.com/') {
  const headers = {
    'User-Agent': DEFAULT_UA,
    Referer: referer,
    Origin: 'https://y.qq.com',
    Accept: 'application/json, text/plain, */*',
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
  };
  // 关键：把客户端 Cookie 原样转发给 QQ 音乐，VIP 歌曲才拿得到 vkey
  if (cookie && String(cookie).trim()) headers.Cookie = String(cookie).trim();
  return Object.assign(headers, extra || {});
}

/**
 * 解析响应体：优先 JSON.parse，失败则返回原始文本。
 * @param {import('axios').AxiosResponse} res
 * @returns {*}
 */
function parseBody(res) {
  const raw = res && res.data;
  if (raw === null || raw === undefined) return null;
  if (typeof raw === 'object') return raw;
  const text = toUtf8String(raw).trim();
  if (text === '') return null;
  // 去掉 JSONP 包裹：callback({...}) / jsonp123({...})
  const jsonp = text.match(/^[A-Za-z_$][\w$.]*\s*\(\s*([\s\S]*?)\s*\)\s*;?$/);
  const candidate = jsonp ? jsonp[1] : text;
  try {
    return JSON.parse(candidate);
  } catch (err) {
    // 有些接口返回 callback({...});\n 或多段，尝试截取第一个 {...}
    const start = candidate.indexOf('{');
    const end = candidate.lastIndexOf('}');
    if (start >= 0 && end > start) {
      try {
        return JSON.parse(candidate.slice(start, end + 1));
      } catch (err2) {
        return text;
      }
    }
    return text;
  }
}

/**
 * GET 并解析 JSON。
 * @param {string} url
 * @param {object} [opts]
 * @param {object} [opts.params] query 参数
 * @param {string} [opts.cookie] Cookie
 * @param {object} [opts.headers] 额外头
 * @param {number} [opts.timeout]
 * @param {string} [opts.referer]
 * @returns {Promise<*>}
 */
async function getJson(url, opts = {}) {
  const res = await requestWithRetry({
    method: 'GET',
    url,
    params: opts.params || undefined,
    // 自己用 qs 序列化，避免 axios 把数组打成 a[]=1 的形式
    paramsSerializer: (p) => qs.stringify(p, { arrayFormat: 'repeat' }),
    headers: buildHeaders(opts.headers, opts.cookie, opts.referer),
    timeout: opts.timeout || DEFAULT_TIMEOUT,
  });
  if (!res || res.status >= 400) {
    const err = new Error(`GET ${url} -> HTTP ${res ? res.status : 'no-response'}`);
    err.status = res ? res.status : 0;
    err.response = res;
    throw err;
  }
  return parseBody(res);
}

/**
 * GET 原始文本（不 JSON 解析）。
 * @param {string} url
 * @param {object} [opts]
 * @returns {Promise<string>}
 */
async function getText(url, opts = {}) {
  const res = await requestWithRetry({
    method: 'GET',
    url,
    params: opts.params || undefined,
    paramsSerializer: (p) => qs.stringify(p, { arrayFormat: 'repeat' }),
    headers: buildHeaders(opts.headers, opts.cookie, opts.referer),
    timeout: opts.timeout || DEFAULT_TIMEOUT,
  });
  if (!res || res.status >= 400) {
    const err = new Error(`GET ${url} -> HTTP ${res ? res.status : 'no-response'}`);
    err.status = res ? res.status : 0;
    err.response = res;
    throw err;
  }
  return toUtf8String(res.data);
}

/**
 * POST 表单（application/x-www-form-urlencoded）。
 * @param {string} url
 * @param {object} fields 表单字段
 * @param {object} [opts]
 * @returns {Promise<*>}
 */
async function postForm(url, fields, opts = {}) {
  const res = await requestWithRetry({
    method: 'POST',
    url,
    data: qs.stringify(fields || {}),
    headers: buildHeaders(
      Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded' }, opts.headers || {}),
      opts.cookie,
      opts.referer
    ),
    timeout: opts.timeout || DEFAULT_TIMEOUT,
  });
  if (!res || res.status >= 400) {
    const err = new Error(`POST ${url} -> HTTP ${res ? res.status : 'no-response'}`);
    err.status = res ? res.status : 0;
    err.response = res;
    throw err;
  }
  return parseBody(res);
}

/**
 * POST 原始 JSON body。
 * @param {string} url
 * @param {object} body
 * @param {object} [opts]
 * @returns {Promise<*>}
 */
async function postJson(url, body, opts = {}) {
  const res = await requestWithRetry({
    method: 'POST',
    url,
    data: JSON.stringify(body || {}),
    headers: buildHeaders(
      Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {}),
      opts.cookie,
      opts.referer
    ),
    timeout: opts.timeout || DEFAULT_TIMEOUT,
  });
  if (!res || res.status >= 400) {
    const err = new Error(`POST ${url} -> HTTP ${res ? res.status : 'no-response'}`);
    err.status = res ? res.status : 0;
    err.response = res;
    throw err;
  }
  return parseBody(res);
}

/**
 * 下载二进制（二维码图片用）。
 * @param {string} url
 * @param {object} [opts]
 * @returns {Promise<{ buffer: Buffer, headers: object, status: number }>}
 */
async function getBinary(url, opts = {}) {
  const res = await requestWithRetry({
    method: 'GET',
    url,
    params: opts.params || undefined,
    paramsSerializer: (p) => qs.stringify(p, { arrayFormat: 'repeat' }),
    responseType: 'arraybuffer',
    headers: buildHeaders(opts.headers, opts.cookie, opts.referer),
    timeout: opts.timeout || 10000,
  });
  if (!res || res.status >= 400) {
    const err = new Error(`GET ${url} -> HTTP ${res ? res.status : 'no-response'}`);
    err.status = res ? res.status : 0;
    err.response = res;
    throw err;
  }
  return {
    buffer: Buffer.isBuffer(res.data) ? res.data : Buffer.from(res.data || []),
    headers: res.headers || {},
    status: res.status,
  };
}

/**
 * 统一的“缓存 + 去重”包装。
 * @template T
 * @param {string} key 缓存 key（null/空则不走缓存）
 * @param {number} ttl 毫秒
 * @param {() => Promise<T>} loader 真正的上游调用
 * @returns {Promise<T>}
 */
async function cached(key, ttl, loader) {
  if (!key) return loader();
  const hit = cache.get(key);
  if (hit !== undefined) return hit;
  if (inflight.has(key)) return inflight.get(key);
  const p = (async () => {
    try {
      const value = await loader();
      if (value !== undefined && value !== null) cache.set(key, value, ttl);
      return value;
    } finally {
      inflight.delete(key);
    }
  })();
  inflight.set(key, p);
  return p;
}

module.exports = {
  client,
  cache,
  TTL,
  CACHE_MAX,
  DEFAULT_TIMEOUT,
  requestWithRetry,
  buildHeaders,
  parseBody,
  getJson,
  getText,
  postForm,
  postJson,
  getBinary,
  cached,
  sleep,
};
