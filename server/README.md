# xixi-music-server

XixiMusic（Android 包名 `com.xixi.music`）的本地代理服务。手机端只与本服务通信，本服务再去代理
QQ 音乐（`y.qq.com` / `u.y.qq.com` / `c.y.qq.com`）的 Web 接口，负责：请求签名、Cookie 透传、
扫码登录（含 QRC/ptqrtoken 处理）、搜索、推荐、播放直链（音质阶梯）、歌词、歌曲详情、用户信息。

- 纯 Node.js + Express，**无需构建步骤**，CommonJS。
- Node **>= 18**。
- 局域网使用，无鉴权、无需任何密钥。
- 所有接口统一返回信封：成功 `{ "code": 0, "message": "ok", "data": ... }`，
  失败 `{ "code": <非0>, "message": "...", "data": null }`。

---

## 安装与启动

```bash
cd server
npm install
npm start          # 等价于 node index.js
```

端口通过环境变量 `PORT` 指定（默认 `3200`），监听 `0.0.0.0`（手机才能从局域网访问）：

```bash
# Linux / macOS
PORT=3200 npm start
# Windows PowerShell
$env:PORT=3200; npm start
```

启动后会打印本机所有局域网 IPv4 地址，直接把它填进 Android 端的
`NetworkModule.kt`（`BASE_URL`），例如 `http://192.168.1.23:3200/`。
也可以打开 `http://<手机能访问的IP>:3200/` 查看接口说明页。

### 找不到局域网 IP？

启动日志里的 `局域网访问` 一行就是（服务用 `os.networkInterfaces()` 枚举非回环 IPv4）。
也可以手动查：

- Windows：`ipconfig` → 看无线网卡的 `IPv4 地址`
- Linux/macOS：`ip addr` / `ifconfig` → 找 `192.168.x.x` / `10.x.x.x`
- 手机和电脑必须在同一个 WiFi；Windows 防火墙首次会弹窗，需要放行 Node.js。

---

## 接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 健康检查，返回 name/version/uptime/qqApiPackage |
| GET | `/search?keywords=<kw>&page=1&limit=20` | 歌曲搜索 |
| GET | `/recommend/songs` | 推荐歌曲（热歌榜 `topid=26`，至少 30 首） |
| GET | `/song/urls?id=<mid>&quality=<可选>` | 播放直链（音质阶梯） |
| GET | `/song/lyric?id=<mid>` | 歌词（原文 + 翻译 LRC） |
| GET | `/song/detail?id=<mid>` | 歌曲详情 |
| POST/GET | `/login/qr/create` | 申请扫码登录，返回 `identifier` 与二维码 base64 |
| GET | `/login/qr/check?identifier=<uuid>` | 轮询扫码状态 |
| GET | `/user/info` | 当前登录用户信息（需要 cookie） |
| GET/POST | `/login/logout` | 登出（无状态，服务端顺手清掉持久化 cookie） |
| GET | `/` | 极简 HTML 说明页（浏览器可直接打开） |

### curl 示例

```bash
# 健康检查
curl "http://127.0.0.1:3200/health"

# 搜索（中文关键词记得 URL 编码，curl 会自动处理）
curl "http://127.0.0.1:3200/search?keywords=%E5%91%A8%E6%9D%B0%E4%BC%A6&page=1&limit=20"

# 推荐（热歌榜）
curl "http://127.0.0.1:3200/recommend/songs"

# 播放直链（默认按 flac -> ape -> 320 -> 128 逐级尝试）
curl "http://127.0.0.1:3200/song/urls?id=0039MnYb0qxYhV"

# 指定优先音质（拿不到会继续往下退）
curl "http://127.0.0.1:3200/song/urls?id=0039MnYb0qxYhV&quality=flac"

# 歌词 / 详情
curl "http://127.0.0.1:3200/song/lyric?id=0039MnYb0qxYhV"
curl "http://127.0.0.1:3200/song/detail?id=0039MnYb0qxYhV"

# 扫码登录：先拿二维码，再用返回的 identifier 轮询
curl -X POST "http://127.0.0.1:3200/login/qr/create"
curl "http://127.0.0.1:3200/login/qr/check?identifier=<上一步返回的 identifier>"

# 用户信息（cookie 三种传法任选）
curl -H "Cookie: uin=o12345678; skey=xxx; qm_keyst=yyy" "http://127.0.0.1:3200/user/info"
curl -H "X-QQ-Cookie: uin=o12345678; skey=xxx; qm_keyst=yyy" "http://127.0.0.1:3200/user/info"
curl "http://127.0.0.1:3200/user/info?cookie=uin%3Do12345678%3B%20skey%3Dxxx%3B%20qm_keyst%3Dyyy"

# 登出
curl "http://127.0.0.1:3200/login/logout"
```

---

## Cookie 处理

服务按以下优先级解析 cookie：`Cookie` 请求头 → `?cookie=` query → `X-QQ-Cookie` 头 →
POST body 的 `cookie` 字段 → 服务端持久化的 `.cookie.json`。
解析结果会放在 `req.qqCookie`，并在**每一次**上游 QQ 请求里原样转发，
这样会员歌曲才有 vkey（VIP 歌曲必须有登录态）。

扫码登录成功（`/login/qr/check` 返回 `status: "confirmed"`）后，服务会：

1. 解析 `ptqrlogin` 的 `Set-Cookie`，必要时跟随一次 `check_sig` 跳转，补齐
   `uin`、`skey`、`qm_keyst`（即 `qqmusic_key`）、`p_skey`、`psrf_qqunionid` 等；
2. 调用用户信息接口拿昵称/头像；
3. 把 cookie 写入 `server/.cookie.json`（已在 `.gitignore` 中忽略），
   所以**服务重启后 `/user/info` 依然可用**，也方便你手动复制这份 cookie。
   所有磁盘写入都包在 try/catch 里，写失败只告警、不影响本次运行。

扫码会话保存在内存里（key 是服务端生成的 UUID `identifier`），有效期 120 秒；
后台每 60 秒清扫一次过期会话（`setInterval` + `unref()`，不会阻止进程退出）。

---

## 音质阶梯

`/song/urls` 严格按 **`flac` → `ape` → `320` → `128`** 顺序尝试，返回**第一个可用**的直链：

| quality | qualityLabel | 直链文件名前缀 |
| --- | --- | --- |
| `flac` | 无损FLAC | `F000` |
| `ape` | 无损APE | `A000` |
| `320` | 高品质320K | `M800` |
| `128` | 标准128K | `M500`（有时是 `C400`，即 m4a） |

- 也可以传 `?quality=flac` 指定优先音质，其它音质仍按上面的相对顺序兜底。
- 返回的 `quality` 会根据真实文件名二次校验：请求 flac 却只拿到 128k 时不会冒充无损。
- `expiredAt` 是**epoch 毫秒**时间戳。QQ 直链很短命，接口不给过期时间时按铸造时刻 + 180 秒计算；
  缓存 TTL 也控制在 100 秒内（要求 <= 120 秒），并且命中缓存时会用真实剩余寿命重算 `expiredAt`，
  **绝不会把已过期的直链返回给客户端**。
- 四级都拿不到直链时返回 `{"code": 40401, "message": "该歌曲无法播放（可能需要VIP或已下架）", "data": null}`，
  **HTTP 状态是 200**。这是有意为之：客户端靠解析 `code` 判断，用 200 可以避免 Retrofit
  把 404 直接抛成 `HttpException` 而丢掉这段中文提示。

---

## 上游接口与签名（重要，务实说明）

| 功能 | 主路径 | 兜底 |
| --- | --- | --- |
| 搜索 | `c.y.qq.com/soso/fcgi-bin/client_search_cp`（**免签名**） | — |
| 推荐 | `c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg`（`topid=26` 热歌榜，**免签名**） | 不足 30 首时补 `topid=4/27/62` |
| 播放直链 | `u.y.qq.com/cgi-bin/musicu.fcg` 的 `vkey.GetVkeyServer`（**带 zzc 签名**） | `c.y.qq.com/base/fcgi-bin/fcg_music_express_mobile3.fcg`（免签名） |
| 歌词 | `c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg`（**免签名**） | `fcg_query_lyric.fcg` |
| 详情 | `musicu.fcg` 的 `music.pf_song_detail_svr`（**带签名**） | 免签名搜索精确匹配 → `fcg_play_single_song.fcg` |
| 用户信息 | `musicu.fcg` 的 `music.UserInfo.userInfoServer`（**带签名**） | `c.y.qq.com/rsc/fcgi-bin/fcg_get_user_info.fcg`（免签名） |

签名（`src/qq/sign.js`）：`zzc_sign` 把 JSON body 取 SHA1，产物形如 `zzc<40位hex>`，
通过 **POST 表单 `data=<json>` + query `sign=zzc...&token=...`** 发给 `musicu.fcg`，
并带上 `comm { uin, format:"json", ct:24, cv:0 }`、桌面 Chrome UA 与
`Referer/Origin: https://y.qq.com/`。老版纯位运算的 zzc 实现保留在 `zzcHashLegacy()` 里备用。

**诚实声明：** 本机开发环境没有外网，签名结果**没有在真实 QQ 服务器上验证过**，
salt 变体若被 QQ 调整签名就会失效。因此所有关键功能（搜索/推荐/歌词/直链）的主路径都走
**免签名**的 legacy 接口；签名只用于音乐库新接口，一旦失败会自动回退到免签名接口，
APP 不会因为签名问题整体不可用。如果哪天 `musicu` 系列接口返回鉴权错误，
优先检查 `sign.js` 里的签名实现即可。

---

## 可选依赖 `@xnfa/qq-music-api`

启动时会 `try { require('@xnfa/qq-music-api') }` 软探测这个开源封装包：

- 装了：日志提示“检测到可选依赖，将作为备用实现加载”（当前仍使用内置实现）；
- 没装/装不上：打印清晰的中文警告，然后 **100% 回退到内置实现**。

它**不在** `package.json` 的 `dependencies` 里，所以 `npm install` 在没有网络的情况下也一定成功，
内置实现本身功能完整、可以独立工作。

---

## 目录结构

```
server/
├── index.js                 # Express 应用、中间件、错误处理、启动横幅（含局域网 IP）
├── package.json
├── README.md
├── .gitignore
└── src/
    ├── session.js           # 扫码会话内存存储 + .cookie.json 持久化 + 60s 清扫
    ├── optional/xnfa.js     # @xnfa/qq-music-api 软探测
    ├── routes/index.js      # 全部 11 个接口（统一信封）
    └── qq/
        ├── sign.js          # zzc_sign / comm / 签名 query 辅助
        ├── http.js          # axios 实例、UA/Referer、Cookie 透传、重试、LRU 缓存
        ├── crypto.js        # base64 解码、LRC 安全解码、ptqrtoken、QRC 处理
        ├── api.js           # 真实 QQ 接口封装（含全部回退逻辑）
        └── normalize.js     # 原始对象 -> 统一 Song 结构
```

### Song 对象结构（所有接口一致）

```json
{
  "id": "0039MnYb0qxYhV",
  "mid": "0039MnYb0qxYhV",
  "name": "晴天",
  "singer": "周杰伦",
  "singers": [{ "id": 4558, "mid": "0025NhlN2yWrP4", "name": "周杰伦" }],
  "album": "叶惠美",
  "albumMid": "000MkMni19ClKG",
  "cover": "https://y.qq.com/music/photo_new/T002R500x500M000000MkMni19ClKG.jpg",
  "duration": 269,
  "vip": false,
  "pay": { "play": 0, "download": 0 },
  "quality": ["128", "320", "flac"],
  "interval": 269
}
```

`albumMid` 为空时 `cover` 会退化成安全占位图，不会返回 null。

---

## 常见问题

- **`该歌曲无法播放（可能需要VIP或已下架）`**：先确认已扫码登录并把 cookie 传给服务
  （登录成功后服务端 `.cookie.json` 会自动带上），再确认该歌确实有版权。
- **端口被占用**：换 `PORT`，例如 `PORT=3300 npm start`。
- **手机连不上**：检查是否同一 WiFi、Windows 防火墙是否放行 Node、`BASE_URL` 是否用了
  启动日志里打印的局域网 IP（不是 `127.0.0.1`）。
- **中文乱码**：所有文本响应都是 UTF-8；歌词以 LRC 纯文本返回，base64 会自动解码。
