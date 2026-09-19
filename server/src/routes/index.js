'use strict';

/**
 * src/routes/index.js
 * ---------------------------------------------------------------------------
 * 全部路由（统一响应信封 { code, message, data }）：
 *
 *   GET  /search            搜索
 *   GET  /recommend/songs   推荐（热歌榜 topid=26）
 *   GET  /song/urls         播放直链（音质阶梯 flac->ape->320->128）
 *   GET  /song/lyric        歌词
 *   GET  /song/detail       歌曲详情
 *   POST /login/qr/create   申请扫码登录（同时支持 GET）
 *   GET  /login/qr/check    轮询扫码状态
 *   GET  /user/info         当前登录用户信息
 *   GET  /login/logout      登出（无状态）
 *   GET  /health            健康检查
 *   GET  /                  首页说明
 * ---------------------------------------------------------------------------
 */

const express = require('express');
const api = require('../qq/api');
const session = require('../session');
const xnfa = require('../optional/xnfa');
const { toUtf8String } = require('../qq/crypto');

const router = express.Router();

/* -------------------------------------------------------------------------- */
/* 响应工具                                                                    */
/* -------------------------------------------------------------------------- */

/** 成功响应 */
function ok(res, data, message = 'ok') {
  return res.status(200).json({ code: 0, message, data });
}

/**
 * 失败响应。
 * @param {import('express').Response} res
 * @param {number} code 业务错误码
 * @param {string} message 人类可读消息
 * @param {number} [httpStatus=200] HTTP 状态码（默认为 200，客户端只看 code）
 */
function fail(res, code, message, httpStatus = 200) {
  return res.status(httpStatus).json({
    code: Number.isFinite(Number(code)) ? Number(code) : 50000,
    message: message || 'error',
    data: null,
  });
}

/** 把 async 处理函数的异常交给 express 错误处理中间件 */
function wrap(handler) {
  return (req, res, next) => {
    Promise.resolve(handler(req, res, next)).catch(next);
  };
}

/** 取请求里的 cookie：优先 req.qqCookie（中间件已解析），再兜底各处 */
function reqCookie(req) {
  if (req.qqCookie) return req.qqCookie;
  const fromHeader = req.headers ? req.headers.cookie : '';
  const fromCustom = req.headers ? req.headers['x-qq-cookie'] : '';
  const fromQuery = req.query ? req.query.cookie : '';
  const fromBody = req.body ? req.body.cookie : '';
  return api.cleanCookie(fromBody || fromQuery || fromCustom || fromHeader || '');
}

/** 统一的“客户端 cookie 或服务端持久化 cookie”解析 */
function resolveCookie(req) {
  const client = reqCookie(req);
  if (client) return client;
  const persisted = session.loadPersistedCookie();
  return persisted && persisted.cookie ? persisted.cookie : '';
}

/** 取必需 query 参数，缺失时抛 400 */
function requireParam(req, name) {
  const value = req.query ? req.query[name] : '';
  const str = toUtf8String(value === undefined || value === null ? '' : value).trim();
  if (!str) {
    const err = new Error(`缺少必填参数 ${name}`);
    err.status = 400;
    err.code = 40001;
    throw err;
  }
  return str;
}

/* -------------------------------------------------------------------------- */
/* 1. 搜索                                                                     */
/* -------------------------------------------------------------------------- */

/**
 * GET /search?keywords=xxx&page=1&limit=20
 * 主路径：免签名 c.y.qq.com/soso/fcgi-bin/client_search_cp
 */
router.get(
  '/search',
  wrap(async (req, res) => {
    const keywords = toUtf8String(req.query.keywords || req.query.key || req.query.w || '').trim();
    const page = Number.parseInt(req.query.page, 10) || 1;
    const limit = Number.parseInt(req.query.limit, 10) || 20;
    if (!keywords) {
      return fail(res, 40001, '缺少搜索关键词 keywords', 400);
    }
    const data = await api.search(keywords, page, limit, { cookie: resolveCookie(req) });
    if (!data || !Array.isArray(data.list)) {
      return fail(res, 50002, '搜索失败：上游返回异常', 200);
    }
    return ok(res, data);
  })
);

/* -------------------------------------------------------------------------- */
/* 2. 推荐                                                                     */
/* -------------------------------------------------------------------------- */

/**
 * GET /recommend/songs
 * 主路径：免签名 fcg_v8_toplist_cp.fcg（topid=26 热歌榜），不足 30 首自动补榜
 */
router.get(
  '/recommend/songs',
  wrap(async (req, res) => {
    const data = await api.recommendSongs({ cookie: resolveCookie(req) });
    if (!data || !Array.isArray(data.list) || data.list.length === 0) {
      return fail(res, 50003, '推荐列表获取失败：上游无数据', 200);
    }
    return ok(res, { list: data.list });
  })
);

/* -------------------------------------------------------------------------- */
/* 3. 播放直链                                                                 */
/* -------------------------------------------------------------------------- */

/**
 * GET /song/urls?id=<mid>&quality=flac|ape|320|128
 *
 * 音质阶梯严格为 flac -> ape -> 320 -> 128，返回第一个可用的。
 *
 * 注意：当所有音质都拿不到直链时（VIP 限制 / 已下架 / 版权原因），
 * 返回 HTTP 200 + 业务码 40401，而不是 HTTP 404。
 * 客户端（Kotlin/Retrofit）靠解析 code 判断，用 200 可以避免 Retrofit
 * 把 404 直接抛成 HttpException 而丢掉了中文错误提示。
 */
router.get(
  '/song/urls',
  wrap(async (req, res) => {
    const id = requireParam(req, 'id');
    const quality = toUtf8String(req.query.quality || '').trim().toLowerCase();
    const data = await api.songUrls(id, quality, { cookie: resolveCookie(req) });
    if (!data || !data.url) {
      return fail(res, 40401, '该歌曲无法播放（可能需要VIP或已下架）', 200);
    }
    return ok(res, data);
  })
);

/* -------------------------------------------------------------------------- */
/* 4. 歌词                                                                     */
/* -------------------------------------------------------------------------- */

/**
 * GET /song/lyric?id=<mid>
 * 主路径：免签名 fcg_query_lyric_new.fcg；无歌词返回空串 + code 0
 */
router.get(
  '/song/lyric',
  wrap(async (req, res) => {
    const id = requireParam(req, 'id');
    const data = await api.lyric(id, { cookie: resolveCookie(req) });
    return ok(res, {
      lyric: (data && data.lyric) || '',
      trans: (data && data.trans) || '',
    });
  })
);

/* -------------------------------------------------------------------------- */
/* 5. 歌曲详情                                                                 */
/* -------------------------------------------------------------------------- */

/**
 * GET /song/detail?id=<mid>
 * 主路径：签名 musicu（music.pf_song_detail_svr）；失败自动回退免签名接口
 */
router.get(
  '/song/detail',
  wrap(async (req, res) => {
    const id = requireParam(req, 'id');
    const data = await api.detail(id, { cookie: resolveCookie(req) });
    if (!data) {
      return fail(res, 40402, '未找到该歌曲', 404);
    }
    return ok(res, data);
  })
);

/* -------------------------------------------------------------------------- */
/* 6. 扫码登录                                                                 */
/* -------------------------------------------------------------------------- */

/**
 * POST /login/qr/create （同时接受 GET，方便浏览器直接打开调试）
 * 返回 { qrsig, identifier, qrImage, expireSeconds }
 */
async function handleQrCreate(req, res) {
  const created = await api.qrCreate();
  const sess = session.createSession({
    qrsig: created.qrsig,
    ptqrtoken: created.ptqrtoken,
    cookies: created.cookies,
  });
  return ok(res, {
    qrsig: created.qrsig,
    identifier: sess.identifier,
    qrImage: `data:image/png;base64,${created.qrImageBuffer.toString('base64')}`,
    expireSeconds: created.expireSeconds,
  });
}

router.post('/login/qr/create', wrap(handleQrCreate));
router.get('/login/qr/create', wrap(handleQrCreate));

/**
 * GET /login/qr/check?identifier=<uuid>
 * 返回 { status, cookie?, nickname?, avatar? }
 */
router.get(
  '/login/qr/check',
  wrap(async (req, res) => {
    const identifier = requireParam(req, 'identifier');
    const sess = session.getSession(identifier);
    if (!sess) {
      return fail(res, 40403, '登录会话不存在或已过期，请重新获取二维码', 404);
    }

    // 已确认的会话：后续轮询返回同一份 cookie / 用户信息
    if (sess.status === 'confirmed' && sess.cookie) {
      return ok(res, {
        status: 'confirmed',
        cookie: sess.cookie,
        nickname: sess.nickname || '',
        avatar: sess.avatar || '',
      });
    }

    // 二维码自身已过期
    if (session.isExpired(sess)) {
      session.updateSession(identifier, { status: 'expired' });
      return ok(res, { status: 'expired' });
    }

    sess.polls += 1;
    let result = null;
    try {
      result = await api.qrCheck(sess.qrsig, sess.ptqrtoken);
    } catch (err) {
      console.warn('[qr] 轮询上游失败:', err && err.message);
      // 上游抖动不当作终态，客户端继续轮询即可
      return ok(res, { status: sess.status === 'scanned' ? 'scanned' : 'waiting' });
    }

    if (!result || result.status === 'waiting') {
      // 保持已扫码状态（68/67 之类可能来回变）
      if (sess.status !== 'scanned') session.updateSession(identifier, { status: 'waiting' });
      return ok(res, { status: sess.status === 'scanned' ? 'scanned' : 'waiting' });
    }

    if (result.status === 'scanned') {
      session.updateSession(identifier, { status: 'scanned' });
      return ok(res, { status: 'scanned' });
    }

    if (result.status === 'refused') {
      session.updateSession(identifier, { status: 'refused' });
      return ok(res, { status: 'refused' });
    }

    if (result.status === 'expired') {
      session.updateSession(identifier, { status: 'expired' });
      return ok(res, { status: 'expired' });
    }

    // ---- confirmed：补齐关键 cookie，抓用户资料，落盘 ----
    const cookie = api.mergeCookies(sess.cookies || '', result.cookie || '');
    let profile = null;
    try {
      profile = await api.userInfo(cookie);
    } catch (err) {
      console.warn('[qr] 获取用户资料失败（cookie 仍然有效）:', err && err.message);
      profile = null;
    }

    session.updateSession(identifier, {
      status: 'confirmed',
      cookie,
      uin: (profile && profile.uin) || result.uin || '',
      nickname: (profile && profile.nickname) || result.nickname || '',
      avatar: (profile && profile.avatar) || '',
      vip: Boolean(profile && profile.vip),
    });

    const saved = session.savePersistedCookie({
      cookie,
      uin: (profile && profile.uin) || result.uin || '',
      nickname: (profile && profile.nickname) || result.nickname || '',
      avatar: (profile && profile.avatar) || '',
    });
    if (!saved) {
      console.warn('[qr] cookie 未能写入 .cookie.json，但本次会话内仍可使用');
    }

    return ok(res, {
      status: 'confirmed',
      cookie,
      nickname: (profile && profile.nickname) || result.nickname || '',
      avatar: (profile && profile.avatar) || '',
    });
  })
);

/**
 * GET|POST /login/logout
 * 无状态：客户端自己清 DataStore 里的 cookie；服务端顺手清掉持久化文件。
 */
function handleLogout(req, res) {
  const cleared = session.clearPersistedCookie();
  return ok(res, { cleared }, cleared ? '已退出登录，服务端登录信息已清除' : '已退出登录（服务端无持久化登录信息）');
}

router.get('/login/logout', wrap(handleLogout));
router.post('/login/logout', wrap(handleLogout));

/* -------------------------------------------------------------------------- */
/* 7. 用户信息                                                                 */
/* -------------------------------------------------------------------------- */

/**
 * GET /user/info
 * cookie 来源优先级：Cookie 头 > ?cookie= > X-QQ-Cookie 头 > body.cookie > .cookie.json
 */
router.get(
  '/user/info',
  wrap(async (req, res) => {
    const cookie = resolveCookie(req);
    if (!cookie) {
      return fail(res, 40101, '未登录或登录已失效', 401);
    }
    const info = await api.userInfo(cookie);
    if (!info || !info.nickname) {
      return fail(res, 40101, '未登录或登录已失效', 401);
    }
    return ok(res, {
      nickname: info.nickname || '',
      avatar: info.avatar || '',
      vip: Boolean(info.vip),
      uin: info.uin || '',
    });
  })
);

/* -------------------------------------------------------------------------- */
/* 8. 健康检查                                                                 */
/* -------------------------------------------------------------------------- */

router.get('/health', (req, res) => {
  const pkg = require('../../package.json');
  return ok(res, {
    name: pkg.name,
    version: pkg.version,
    uptime: Math.floor(process.uptime()),
    qqApiPackage: xnfa.isAvailable(),
  });
});

/* -------------------------------------------------------------------------- */
/* 9. 首页说明                                                                 */
/* -------------------------------------------------------------------------- */

const INDEX_ENDPOINTS = [
  ['GET', '/health', '健康检查'],
  ['GET', '/search?keywords=周杰伦&page=1&limit=20', '搜索歌曲'],
  ['GET', '/recommend/songs', '推荐歌曲（热歌榜 topid=26）'],
  ['GET', '/song/urls?id=0039MnYb0qxYhV&quality=flac', '播放直链（音质阶梯）'],
  ['GET', '/song/lyric?id=0039MnYb0qxYhV', '歌词（含翻译）'],
  ['GET', '/song/detail?id=0039MnYb0qxYhV', '歌曲详情'],
  ['POST/GET', '/login/qr/create', '申请扫码登录'],
  ['GET', '/login/qr/check?identifier=<uuid>', '轮询扫码状态'],
  ['GET', '/user/info', '当前用户信息（需 cookie）'],
  ['GET/POST', '/login/logout', '登出（无状态）'],
];

/** 首页：极简 HTML，方便用浏览器打开确认服务在跑 */
router.get('/', (req, res) => {
  const pkg = require('../../package.json');
  const rows = INDEX_ENDPOINTS.map(
    ([method, path, desc]) =>
      `      <tr><td class="m">${method}</td><td class="p">${path}</td><td>${desc}</td></tr>`
  ).join('\n');
  const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${pkg.name}</title>
<style>
  body{font-family:system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;margin:24px;background:#12121a;color:#e8e8f0}
  h1{font-size:20px;margin:0 0 4px}
  p.sub{color:#9a9ab0;margin:0 0 18px;font-size:13px}
  table{border-collapse:collapse;width:100%;max-width:900px;font-size:13px}
  th,td{text-align:left;padding:7px 10px;border-bottom:1px solid #2a2a3a;vertical-align:top}
  th{color:#9a9ab0;font-weight:600}
  td.m{color:#6cc6a8;white-space:nowrap}
  td.p{color:#8ab4f8;font-family:ui-monospace,Consolas,monospace;word-break:break-all}
  code{background:#1c1c28;padding:1px 5px;border-radius:4px}
  .note{margin-top:18px;color:#9a9ab0;font-size:12px;line-height:1.7;max-width:900px}
</style>
</head>
<body>
  <h1>${pkg.name} v${pkg.version}</h1>
  <p class="sub">XixiMusic 本地代理服务（QQ 音乐）· 所有接口统一返回 <code>{ code, message, data }</code></p>
  <table>
    <thead><tr><th>方法</th><th>路径</th><th>说明</th></tr></thead>
    <tbody>
${rows}
    </tbody>
  </table>
  <div class="note">
    音质阶梯：<code>flac</code> &rarr; <code>ape</code> &rarr; <code>320</code> &rarr; <code>128</code>，返回第一个可用直链。<br>
    登录：先 <code>/login/qr/create</code> 拿二维码，再轮询 <code>/login/qr/check</code>；登录成功后 cookie 会写入服务端 <code>.cookie.json</code>。<br>
    需要会员的歌曲请把同一份 cookie 通过 <code>Cookie</code> 头或 <code>?cookie=</code> 传给本服务。
  </div>
</body>
</html>`;
  return res.status(200).type('html').send(html);
});

module.exports = router;
module.exports.ok = ok;
module.exports.fail = fail;
module.exports.wrap = wrap;
module.exports.resolveCookie = resolveCookie;
