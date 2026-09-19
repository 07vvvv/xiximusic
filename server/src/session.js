'use strict';

/**
 * src/session.js
 * ---------------------------------------------------------------------------
 * 扫码登录会话存储 + cookie 落盘。
 *
 *   - 内存 Map 保存 { qrsig, ptqrtoken, cookies, createdAt, status, ... }，
 *     以服务端生成的随机 UUID 作为 identifier（客户端只认这个 id）。
 *   - 60 秒定时清扫过期会话（setInterval + unref，不会阻止进程退出）。
 *   - 登录成功后的 cookie 写入 server/.cookie.json，重启后 /user/info 仍可用。
 *     所有磁盘写操作都包 try/catch，权限尽量收紧到 0600。
 * ---------------------------------------------------------------------------
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT_DIR = path.resolve(__dirname, '..');
const COOKIE_FILE = path.join(ROOT_DIR, '.cookie.json');

/** 二维码有效期（秒） */
const QR_TTL_SECONDS = 120;
/** 会话最长保留时间（秒）：过期后再留 5 分钟便于客户端拿到 expired 状态 */
const SESSION_KEEP_SECONDS = QR_TTL_SECONDS + 300;
/** 清扫周期 */
const SWEEP_INTERVAL_MS = 60 * 1000;

/** identifier -> session */
const sessions = new Map();

/** 定时器句柄（防止重复启动） */
let sweepTimer = null;

/* -------------------------------------------------------------------------- */
/* cookie 落盘                                                                 */
/* -------------------------------------------------------------------------- */

/**
 * 读取持久化的 cookie。
 * @returns {{ cookie: string, uin: string, nickname: string, avatar: string, savedAt: number }|null}
 */
function loadPersistedCookie() {
  try {
    if (!fs.existsSync(COOKIE_FILE)) return null;
    const text = fs.readFileSync(COOKIE_FILE, 'utf8');
    if (!text || !text.trim()) return null;
    const parsed = JSON.parse(text);
    if (!parsed || typeof parsed !== 'object' || !parsed.cookie) return null;
    return {
      cookie: String(parsed.cookie),
      uin: parsed.uin ? String(parsed.uin) : '',
      nickname: parsed.nickname ? String(parsed.nickname) : '',
      avatar: parsed.avatar ? String(parsed.avatar) : '',
      savedAt: Number(parsed.savedAt) || 0,
    };
  } catch (err) {
    console.warn('[session] 读取 .cookie.json 失败:', err && err.message);
    return null;
  }
}

/**
 * 写入 cookie 到磁盘（尽力而为，失败只警告）。
 * @param {{ cookie: string, uin?: string, nickname?: string, avatar?: string }} payload
 * @returns {boolean} 是否写入成功
 */
function savePersistedCookie(payload) {
  const cookie = payload && payload.cookie ? String(payload.cookie) : '';
  if (!cookie) return false;
  const data = {
    cookie,
    uin: payload.uin ? String(payload.uin) : '',
    nickname: payload.nickname ? String(payload.nickname) : '',
    avatar: payload.avatar ? String(payload.avatar) : '',
    savedAt: Date.now(),
  };
  try {
    fs.writeFileSync(COOKIE_FILE, `${JSON.stringify(data, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
    try {
      fs.chmodSync(COOKIE_FILE, 0o600);
    } catch (err) {
      /* Windows 上 chmod 基本无效，忽略 */
    }
    console.log(`[session] 登录 cookie 已保存到 ${COOKIE_FILE}`);
    return true;
  } catch (err) {
    console.warn('[session] 写入 .cookie.json 失败（不影响本次运行）:', err && err.message);
    return false;
  }
}

/** 删除持久化的 cookie（登出时用，失败忽略） */
function clearPersistedCookie() {
  try {
    if (fs.existsSync(COOKIE_FILE)) fs.unlinkSync(COOKIE_FILE);
    return true;
  } catch (err) {
    console.warn('[session] 删除 .cookie.json 失败:', err && err.message);
    return false;
  }
}

/* -------------------------------------------------------------------------- */
/* 会话 CRUD                                                                   */
/* -------------------------------------------------------------------------- */

/**
 * 创建一个扫码会话。
 * @param {{ qrsig: string, ptqrtoken: string, cookies?: string }} payload
 * @returns {object} 会话对象（含 identifier / expireSeconds）
 */
function createSession(payload) {
  const now = Date.now();
  const identifier = crypto.randomUUID();
  const session = {
    identifier,
    qrsig: payload.qrsig || '',
    ptqrtoken: payload.ptqrtoken || '',
    cookies: payload.cookies || '',
    createdAt: now,
    updatedAt: now,
    expireAt: now + QR_TTL_SECONDS * 1000,
    expireSeconds: QR_TTL_SECONDS,
    status: 'waiting', // waiting | scanned | confirmed | expired | refused
    cookie: '',
    uin: '',
    nickname: '',
    avatar: '',
    vip: false,
    polls: 0,
  };
  sessions.set(identifier, session);
  return session;
}

/**
 * 取会话（不改状态）。
 * @param {string} identifier
 * @returns {object|null}
 */
function getSession(identifier) {
  if (!identifier) return null;
  const s = sessions.get(String(identifier));
  return s || null;
}

/**
 * 更新会话字段。
 * @param {string} identifier
 * @param {object} patch
 * @returns {object|null}
 */
function updateSession(identifier, patch) {
  const s = getSession(identifier);
  if (!s) return null;
  Object.assign(s, patch || {}, { updatedAt: Date.now() });
  return s;
}

/** 删除会话 */
function deleteSession(identifier) {
  if (!identifier) return false;
  return sessions.delete(String(identifier));
}

/** 当前会话数 */
function sessionCount() {
  return sessions.size;
}

/**
 * 判断会话是否已过期（二维码本身过期）。
 * @param {object} session
 * @returns {boolean}
 */
function isExpired(session) {
  if (!session) return true;
  if (session.status === 'confirmed') return false; // 已确认的会话不再过期
  return Date.now() > session.expireAt;
}

/**
 * 清扫：二维码过期 -> 标记 expired；超过保留期 -> 删除。
 * @returns {{marked: number, removed: number}}
 */
function sweep() {
  const now = Date.now();
  let marked = 0;
  let removed = 0;
  for (const [id, s] of Array.from(sessions.entries())) {
    if (!s) {
      sessions.delete(id);
      removed += 1;
      continue;
    }
    if (s.status !== 'confirmed' && s.status !== 'refused' && now > s.expireAt) {
      s.status = 'expired';
      marked += 1;
    }
    const keepUntil = (s.status === 'confirmed' ? s.updatedAt : s.createdAt) + SESSION_KEEP_SECONDS * 1000;
    if (now > keepUntil) {
      sessions.delete(id);
      removed += 1;
    }
  }
  return { marked, removed };
}

/** 启动定时清扫（幂等，unref 不阻塞退出） */
function startSweep() {
  if (sweepTimer) return sweepTimer;
  sweepTimer = setInterval(() => {
    try {
      const res = sweep();
      if (res.marked || res.removed) {
        console.log(`[session] 清扫完成：标记过期 ${res.marked} 个，移除 ${res.removed} 个，剩余 ${sessions.size} 个`);
      }
    } catch (err) {
      console.warn('[session] 清扫异常:', err && err.message);
    }
  }, SWEEP_INTERVAL_MS);
  if (typeof sweepTimer.unref === 'function') sweepTimer.unref();
  return sweepTimer;
}

/** 停止清扫（测试用） */
function stopSweep() {
  if (sweepTimer) {
    clearInterval(sweepTimer);
    sweepTimer = null;
  }
}

module.exports = {
  COOKIE_FILE,
  QR_TTL_SECONDS,
  SESSION_KEEP_SECONDS,
  SWEEP_INTERVAL_MS,
  sessions,
  loadPersistedCookie,
  savePersistedCookie,
  clearPersistedCookie,
  createSession,
  getSession,
  updateSession,
  deleteSession,
  sessionCount,
  isExpired,
  sweep,
  startSweep,
  stopSweep,
};
