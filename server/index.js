'use strict';

/**
 * index.js — XixiMusic 本地代理服务入口
 * ---------------------------------------------------------------------------
 * Android 客户端 com.xixi.music 只与本服务通信，本服务再代理 QQ 音乐 Web API。
 *
 * 启动：
 *   npm install && npm start
 * 端口：
 *   process.env.PORT（默认 3200），绑定 0.0.0.0 便于手机在同一局域网访问。
 * ---------------------------------------------------------------------------
 */

const os = require('os');
const express = require('express');
const cors = require('cors');

const pkg = require('./package.json');
const routes = require('./src/routes');
const session = require('./src/session');
const xnfa = require('./src/optional/xnfa');
const api = require('./src/qq/api');
const { toUtf8String } = require('./src/qq/crypto');

const PORT = Number.parseInt(process.env.PORT, 10) || 3200;
const HOST = process.env.HOST || '0.0.0.0';

const app = express();
app.disable('x-powered-by');

/* -------------------------------------------------------------------------- */
/* 全局中间件                                                                  */
/* -------------------------------------------------------------------------- */

// 局域网内使用，直接放开跨域
app.use(
  cors({
    origin: true,
    credentials: true,
    methods: ['GET', 'POST', 'OPTIONS'],
    allowedHeaders: ['Content-Type', 'Cookie', 'X-QQ-Cookie', 'Authorization'],
  })
);

// 二维码是 base64 字符串，稍微放大 body 限制
app.use(express.json({ limit: '1mb' }));
app.use(express.urlencoded({ extended: false, limit: '1mb' }));

/**
 * 请求日志：METHOD path -> status (ms)
 */
app.use((req, res, next) => {
  const startedAt = process.hrtime.bigint();
  res.on('finish', () => {
    const ms = Number(process.hrtime.bigint() - startedAt) / 1e6;
    console.log(`${req.method} ${req.originalUrl} -> ${res.statusCode} (${ms.toFixed(1)}ms)`);
  });
  next();
});

/**
 * Cookie 解析中间件。
 * 优先级：Cookie 头 > ?cookie= > X-QQ-Cookie 头 > body.cookie(POST) > 服务端 .cookie.json
 * 结果统一放在 req.qqCookie，供上面所有 QQ 请求透传（VIP 歌曲依赖它）。
 */
app.use((req, res, next) => {
  try {
    const header = req.headers ? req.headers.cookie : '';
    const custom = req.headers ? req.headers['x-qq-cookie'] : '';
    const fromQuery = req.query ? req.query.cookie : '';
    const fromBody = req.body ? req.body.cookie : '';
    let cookie = api.cleanCookie(fromBody || '') ||
      api.cleanCookie(fromQuery || '') ||
      api.cleanCookie(custom || '') ||
      api.cleanCookie(header || '');

    let source = cookie ? 'client' : '';
    if (!cookie) {
      const persisted = session.loadPersistedCookie();
      if (persisted && persisted.cookie) {
        cookie = persisted.cookie;
        source = 'disk';
      }
    }
    req.qqCookie = cookie;
    req.qqCookieSource = source || 'none';
  } catch (err) {
    console.warn('[cookie] 解析请求 cookie 失败:', err && err.message);
    req.qqCookie = '';
    req.qqCookieSource = 'none';
  }
  next();
});

/* -------------------------------------------------------------------------- */
/* 路由                                                                        */
/* -------------------------------------------------------------------------- */

app.use('/', routes);

/* -------------------------------------------------------------------------- */
/* 404 处理                                                                    */
/* -------------------------------------------------------------------------- */

app.use((req, res) => {
  res.status(404).json({
    code: 40400,
    message: `接口不存在: ${req.method} ${req.path}`,
    data: null,
  });
});

/* -------------------------------------------------------------------------- */
/* 统一错误处理（绝不把堆栈返回给客户端）                                        */
/* -------------------------------------------------------------------------- */

// eslint-disable-next-line no-unused-vars
app.use((err, req, res, next) => {
  const status = Number.isFinite(Number(err && err.status)) ? Number(err.status) : 500;
  const code = Number.isFinite(Number(err && err.code)) ? Number(err.code) : 50000;
  const httpStatus = status >= 400 && status < 600 ? status : 500;

  // 堆栈只打日志
  console.error(`[error] ${req.method} ${req.originalUrl}:`, err && err.stack ? err.stack : err);

  if (res.headersSent) return undefined;
  return res.status(httpStatus).json({
    code,
    message: (err && err.expose === false) || !err || !err.message
      ? '服务器内部错误'
      : toUtf8String(err.message),
    data: null,
  });
});

/* -------------------------------------------------------------------------- */
/* 进程级兜底                                                                  */
/* -------------------------------------------------------------------------- */

process.on('unhandledRejection', (reason) => {
  console.error('[fatal] 未处理的 Promise rejection:', reason && reason.stack ? reason.stack : reason);
});
process.on('uncaughtException', (err) => {
  console.error('[fatal] 未捕获异常（进程继续运行）:', err && err.stack ? err.stack : err);
});

/* -------------------------------------------------------------------------- */
/* 启动                                                                        */
/* -------------------------------------------------------------------------- */

/**
 * 收集本机所有非内网回环 IPv4 地址（供手机端填写 NetworkModule.kt 的 BASE_URL）。
 * @returns {string[]}
 */
function getLanIps() {
  const out = [];
  try {
    const ifaces = os.networkInterfaces();
    for (const name of Object.keys(ifaces)) {
      for (const info of ifaces[name] || []) {
        const family = typeof info.family === 'string' ? info.family : info.family === 4 ? 'IPv4' : '';
        if (family === 'IPv4' && !info.internal && info.address) out.push(info.address);
      }
    }
  } catch (err) {
    console.warn('[startup] 获取网卡信息失败:', err && err.message);
  }
  return out;
}

function printBanner() {
  const optional = xnfa.probe(); // 可选依赖软探测（失败只警告）
  const lines = [];
  lines.push('');
  lines.push('==============================================================');
  lines.push(`  ${pkg.name} v${pkg.version}  (XixiMusic 本地 QQ 音乐代理)`);
  lines.push('==============================================================');
  lines.push(`  监听地址   : http://${HOST}:${PORT}`);
  lines.push(`  本机访问   : http://127.0.0.1:${PORT}`);
  const ips = getLanIps();
  if (ips.length) {
    lines.push('  局域网访问（手机用这个填 NetworkModule.kt 的 BASE_URL）:');
    for (const ip of ips) lines.push(`               http://${ip}:${PORT}/`);
  } else {
    lines.push('  局域网访问 : 未检测到非回环 IPv4 地址（可能没连 WiFi）');
  }
  lines.push(`  可选依赖   : ${xnfa.PACKAGE_NAME} -> ${optional.available ? '可用（备用）' : '未安装（已回退内置实现）'}`);
  lines.push('');
  lines.push('  接口列表：');
  lines.push('    GET  /health                                     健康检查');
  lines.push('    GET  /search?keywords=xxx&page=1&limit=20        搜索歌曲');
  lines.push('    GET  /recommend/songs                            推荐歌曲（热歌榜）');
  lines.push('    GET  /song/urls?id=<mid>&quality=flac            播放直链（音质阶梯）');
  lines.push('    GET  /song/lyric?id=<mid>                        歌词 + 翻译');
  lines.push('    GET  /song/detail?id=<mid>                       歌曲详情');
  lines.push('    POST /login/qr/create  (GET 亦可)                申请扫码登录');
  lines.push('    GET  /login/qr/check?identifier=<uuid>           轮询扫码状态');
  lines.push('    GET  /user/info                                  当前用户信息');
  lines.push('    GET  /login/logout    (POST 亦可)                登出（无状态）');
  lines.push('    GET  /                                          本页说明（HTML）');
  lines.push('');
  lines.push('  提示：需要会员的歌曲，请把客户端 cookie 通过 Cookie 头');
  lines.push('        或 ?cookie= 传给本服务；扫码登录成功后会自动写入');
  lines.push('        .cookie.json 供重启后继续使用。');
  lines.push('==============================================================');
  lines.push('');
  console.log(lines.join('\n'));
}

const server = app.listen(PORT, HOST, () => {
  session.startSweep(); // 60s 清扫过期扫码会话（已 unref）
  printBanner();
});

server.on('error', (err) => {
  if (err && err.code === 'EADDRINUSE') {
    console.error(`[fatal] 端口 ${PORT} 已被占用，请设置环境变量 PORT 换一个端口后重试。`);
  } else {
    console.error('[fatal] HTTP 服务启动失败:', err && err.stack ? err.stack : err);
  }
  process.exitCode = 1;
});

/** 优雅退出：关服务、停清扫 */
function shutdown(signal) {
  console.log(`\n[exit] 收到 ${signal}，正在关闭服务...`);
  session.stopSweep();
  server.close(() => {
    console.log('[exit] 服务已关闭');
    process.exit(0);
  });
  // 3 秒内没关干净就强制退出，避免挂死
  setTimeout(() => process.exit(0), 3000).unref();
}

['SIGINT', 'SIGTERM'].forEach((sig) => {
  process.on(sig, () => shutdown(sig));
});

module.exports = { app, server, getLanIps, PORT, HOST };
