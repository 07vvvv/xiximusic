# 嘻嘻音乐 XixiMusic —— 构建与使用文档

> 个人自用的 QQ 音乐播放器：Kotlin + Jetpack Compose + Media3(ExoPlayer)，音乐源为自建 Node.js 代理服务端。
> 不上架、不缓存音频、不下载、无音质选择、无歌单收藏。

- **应用名**：嘻嘻音乐
- **项目名**：XixiMusic
- **包名**：`com.xixi.music`
- **APK 产物**：`xixi-music-release.apk`
- **目标设备**：Android 15（API 35），最低支持 Android 8.0（API 26）

---

## 1. 版本锁定

| 组件 | 版本 |
| --- | --- |
| Android Gradle Plugin | 8.7.2 |
| Kotlin | 2.0.21 |
| Compose Compiler | `org.jetbrains.kotlin.plugin.compose`（Kotlin 2.0 官方插件） |
| Compose BOM | 2024.10.01 |
| Gradle | 8.9 |
| Media3 / ExoPlayer | 1.5.0 |
| Retrofit | 2.11.0 |
| OkHttp | 4.12.0 |
| Coil | 2.7.0 |
| DataStore | 1.1.1 |
| JDK | 17 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |

---

## 2. 目录结构

```
xiximusic/
├── .github/workflows/build-apk.yml      # GitHub Actions 自动打包
├── app/
│   ├── build.gradle.kts                 # 模块配置（版本锁定、R8、资源压缩）
│   ├── proguard-rules.pro               # R8 保留规则
│   └── src/main/
│       ├── AndroidManifest.xml          # 权限 / 前台服务 / 明文 HTTP
│       ├── java/com/xixi/music/
│       │   ├── MainActivity.kt          # edge-to-edge + 通知权限 + 电池白名单引导
│       │   ├── XixiApp.kt               # Application + 极简依赖容器 + Coil 配置
│       │   ├── data/
│       │   │   ├── local/               # SessionStore(Cookie) / Prefs
│       │   │   ├── model/Models.kt      # 数据模型 + Result 封装
│       │   │   ├── remote/              # ApiConfig / MusicApi / NetworkModule / Cookie 拦截器
│       │   │   └── repository/MusicRepository.kt
│       │   ├── player/
│       │   │   ├── MusicService.kt      # 全局唯一 ExoPlayer + MediaSessionService
│       │   │   ├── PlayerController.kt  # UI <-> Service（MediaController）
│       │   │   ├── PrefetchCache.kt     # 预取 1 首（直链 + 歌词，3 分钟）
│       │   │   ├── PlaybackMode.kt      # 顺序/列表循环/单曲循环/随机
│       │   │   ├── PlaybackCommands.kt  # 自定义命令
│       │   │   └── PlayerBus.kt         # 同进程状态总线
│       │   ├── ui/                      # XixiRoot / AppViewModel / 三个板块 / 组件 / 主题
│       │   └── util/                    # SmallCache / LrcParser
│       └── res/                         # 仅中文资源 + vector 图标（无多密度 PNG）
├── server/                              # Node.js 代理服务端
│   ├── package.json
│   ├── index.js
│   └── src/...
├── build_apk.sh                         # Linux/macOS 一键打包
├── build_apk.bat                        # Windows 一键打包
├── gradlew                              # Gradle 8.9 wrapper 脚本（Linux/macOS，LF）
├── gradlew.bat                          # Gradle 8.9 wrapper 脚本（Windows，CRLF）
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties   # 锁定 gradle-8.9-bin.zip
└── BUILD.md
```

---

## 3. 快速开始

### 3.1 启动服务端（必须先做）

```bash
cd server
npm install
npm start
```

启动后会打印本机局域网地址，例如：

```
嘻嘻音乐服务端已启动
  本机:   http://127.0.0.1:3200
  模拟器: http://10.0.2.2:3200
  局域网: http://192.168.1.23:3200   <-- 真机用这个
```

自定义端口：`PORT=4000 npm start`（Windows：`set PORT=4000 && npm start`）。

### 3.2 配置客户端服务端地址

打开 `app/src/main/java/com/xixi/music/data/remote/ApiConfig.kt`：

```kotlin
private const val LAN_HOST: String = "192.168.1.100"  // 改成你电脑的局域网 IP
private const val PORT: Int = 3200
private const val USE_EMULATOR_HOST: Boolean = true   // 真机改成 false
```

| 场景 | 设置 |
| --- | --- |
| Android 模拟器 | `USE_EMULATOR_HOST = true`（默认，指向 `10.0.2.2:3200`） |
| 真机 + 局域网 | `USE_EMULATOR_HOST = false`，`LAN_HOST` 填服务端打印的局域网 IP |
| 公网 / 域名 | 把 `CUSTOM_BASE_URL` 写成 `https://你的域名/`（优先级最高） |

> 手机与电脑必须在同一个 Wi-Fi 下；Windows 防火墙需放行 3200 端口。

### 3.3 打包 APK

**Windows：**

```bat
build_apk.bat
```

**Linux / macOS：**

```bash
chmod +x build_apk.sh
./build_apk.sh
```

常用参数：

| 参数 | 作用 |
| --- | --- |
| `--debug` | 构建 debug 版（体积大、便于看日志） |
| `--clean` | 先执行 `gradle clean` |
| `--install` | 构建完成后用 adb 直接安装到已连接设备 |
| `-h` / `--help` | 显示帮助 |

产物：项目根目录下的 `xixi-music-release.apk`（同时保留在 `app/build/outputs/apk/release/`）。

---

## 4. GitHub Actions 自动打包（网页端操作，全程不用命令行）

### 4.1 把项目上传到 GitHub（网页端）

1. 打开 <https://github.com/new>，创建仓库，例如 `xiximusic`。
   - **不要**勾选 “Add a README file”，保持空仓库。
   - 可见性可选 Private（私有仓库 Actions 同样免费可用）。
2. 创建后页面会显示仓库地址，例如 `https://github.com/你的用户名/xiximusic`。
3. 在仓库页面点击 **uploading an existing file**（或 `Add file` → `Upload files`）。
4. 把本地 `D:\deepseek\run\xiximusic\` **里面的内容**拖进上传区：
   - 包含：`app/`、`server/`、`.github/`、`gradle/`、`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`build_apk.sh`、`build_apk.bat`、`BUILD.md`、`.gitignore`
   - 不要上传：`local.properties`、`build/`、`.gradle/`、`server/node_modules/`、任何 `.apk`
   - ⚠️ `.github` 是隐藏文件夹：在资源管理器里先打开「查看 → 隐藏的项目」，否则工作流不会上传，Actions 也不会运行。
5. 在下方 Commit message 填 `init`，点 **Commit changes**。

> 上传超过 100 个文件时网页端会分批，耐心拖完即可；也可以用 GitHub Desktop 客户端一次性推送。

### 4.2 触发构建

- **自动**：推送到 `main`（或 `master`）分支即自动开始。
- **手动**：仓库页 → **Actions** → 左侧 **Build XixiMusic APK** → 右侧 **Run workflow** → 选择分支 → **Run workflow**。

### 4.3 下载 APK

1. Actions 页面点进正在运行/已完成的任务。
2. 等 `Build release APK` 出现绿色 ✔（首次约 5–10 分钟）。
3. 页面底部 **Artifacts** 区域下载：
   - `xixi-music-release`：重命名后的 `xixi-music-release.apk`（**推荐下载这个**）
   - `xixi-music-release-apk`：原始文件名 `app-release.apk`
4. 下载得到的是 zip，解压后得到 APK。

### 4.4 关于 Gradle Wrapper（重要说明）

仓库中**只有 `gradle-wrapper.properties` 与 `gradlew` / `gradlew.bat` 脚本**，
**不包含** `gradle-wrapper.jar`（二进制文件，不便用文本方式提交）。

工作流中会自动生成它，无需你手动准备：

```yaml
- name: Setup Gradle
  uses: gradle/gradle-build-action@v3
  with:
    gradle-version: 8.9
- name: Generate Wrapper
  run: gradle wrapper --gradle-version 8.9
```

| 文件 | 是否提交 | 说明 |
| --- | --- | --- |
| `gradle/wrapper/gradle-wrapper.properties` | ✅ 已提交 | 锁定 `gradle-8.9-bin.zip` |
| `gradlew`（Linux/macOS 脚本） | ✅ 已提交 | 标准 Gradle 8.9 wrapper 脚本，LF 行尾 |
| `gradlew.bat`（Windows 脚本） | ✅ 已提交 | 标准 Gradle 8.9 wrapper 脚本，CRLF 行尾 |
| `gradle/wrapper/gradle-wrapper.jar` | ❌ 不提交 | 二进制文件，由 CI 或本地脚本自动生成 |

本地打包脚本同样会自动处理：检测到缺少 `gradle-wrapper.jar` 时，
用系统 `gradle wrapper --gradle-version 8.9` 补齐（需要本机已装 Gradle 8.9，或直接用 Android Studio 打开项目，Studio 会自动补全 Wrapper）。

工作流**不提交** `local.properties`，CI 环境会自动写入 `sdk.dir=$ANDROID_HOME`。

---

## 5. 安装到 Android 15 手机

1. 把 `xixi-music-release.apk` 传到手机（微信文件传输 / USB / 网盘均可）。
2. 手机打开 **设置 → 应用 → 特殊应用权限 → 安装未知应用**，允许你的文件管理器安装。
3. 点击 APK 安装。若提示「应用未安装」，先卸载已装的旧版本 ——
   因为 **release 使用 debug 签名，每次 CI 构建的签名可能不同**，覆盖安装会失败。
4. 首次启动会：
   - 申请**通知权限**（必须允许，否则播放时通知栏控制不显示）
   - 弹出**电池优化**请求，选「允许 / 不受限制」

### 5.1 电池优化白名单（Android 15 必做）

Android 15 对后台服务管控更严。若不加入白名单，锁屏或切到后台后播放服务可能被系统杀掉，表现为**音乐自动停止、通知消失**。

开启方式（任选其一）：

**方式 A（App 内引导）**：首次启动时弹出的系统对话框选择「允许」，即可把「嘻嘻音乐」加入「不受限制」。

**方式 B（手动设置）**：

- 国产 ROM（MIUI / HyperOS、ColorOS、OriginOS、EMUI 等）：
  `设置 → 应用管理 → 嘻嘻音乐 → 省电策略 / 电池 → 选择「无限制」`
  部分机型还需要在 `设置 → 电池 → 应用耗电管理` 中允许「后台运行」「自启动」。
- 原生 / Pixel：
  `设置 → 应用 → 嘻嘻音乐 → 应用电池用量 → 选择「不受限制」`

**方式 C（命令行，可随时手动触发）**：

```bash
adb shell dumpsys deviceidle whitelist +com.xixi.music
```

**方式 D（跳转设置页）**：

```bash
adb shell am start -a android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -d package:com.xixi.music
```

排查是否已在白名单：

```bash
adb shell dumpsys deviceidle whitelist | findstr com.xixi.music
```

---

## 6. 使用说明

### 底部三个板块（仅三个）

| 板块 | 功能 |
| --- | --- |
| **首页** | 顶部搜索框（分页每页 20 条）+ 默认推荐列表。点击歌曲：实时请求直链，加入队列播放，**队列就是当前这个列表**。不保留搜索历史。 |
| **登录** | QQ 音乐二维码登录（每 2 秒轮询，成功后 Cookie 存本地 DataStore）；支持手动粘贴 Cookie 备用；退出登录清除 Cookie 并通知服务端。未登录可播非 VIP，VIP 会提示。 |
| **播放** | 封面、歌名、歌手、进度条、当前/总时长、播放/暂停/上一首/下一首、四种播放模式、歌词实时滚动（当前行高亮，可手动滚动，可点「回到当前」恢复跟随）。 |

底部**常驻迷你播放条**在三个页面都可见，点击可跳到播放页。

### 播放策略

- 每次播放/切歌都**实时调用** `/song/urls`，绝不复用旧直链；
- 服务端按 **flac → ape → 320 → 128** 自动降级，返回第一个可用音质；
- 客户端**不展示、不可选**音质；
- 低延迟切歌：**预取下一首**（直链 + 歌词，内存中只保留 1 首，3 分钟过期），ExoPlayer 队列预加载当前与下一首；预取失效时同步重新解析；
- VIP 无法播放时提示并**自动跳过**下一首；
- **不缓存音频文件、不下载、不保存离线内容**；内存里只存在直链字符串与歌词文本；
- 播完自动下一首。

---

## 7. Android 15 适配清单

| 项目 | 实现位置 |
| --- | --- |
| Edge-to-edge 强制 | `MainActivity.enableEdgeToEdge()` + 主题透明系统栏 |
| 系统栏内边距 | `Scaffold(contentWindowInsets = WindowInsets(0,0,0,0))` + 各界面 `statusBarsPadding()` / 底部 `navigationBarsPadding()` |
| 深色模式状态栏图标 | `XixiMusicTheme` 用 `WindowInsetsControllerCompat` 动态设置 `isAppearanceLightStatusBars/NavigationBars` |
| 键盘遮挡 | Manifest `android:windowSoftInputMode="adjustResize"` |
| 前台服务类型 | `android:foregroundServiceType="mediaPlayback"` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限 |
| 通知权限 | `MainActivity` 中 API ≥ 33 请求 `POST_NOTIFICATIONS` |
| 通知渠道 | Media3 `DefaultMediaNotificationProvider` 固定渠道 id，应用名为「嘻嘻音乐」 |
| PendingIntent | 全部使用 `FLAG_IMMUTABLE` |
| 明文 HTTP | `usesCleartextTraffic="true"` + `res/xml/network_security_config.xml` |
| 16KB 页大小 | `packaging { jniLibs { useLegacyPackaging = false } }`（本项目无 native 库） |
| 电池优化 | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 引导 + 本文档第 5.1 节 |

---

## 8. APK 体积与内存优化

- release 开启 R8 混淆 + 资源压缩（`isMinifyEnabled` / `isShrinkResources`）；
- 使用 `proguard-android-optimize.txt` + 自定义 `proguard-rules.pro`；
- 依赖精简：无 Hilt、Dagger、ZXing、Room、WorkManager、Navigation Compose、Material Icons Extended；
- 底部导航用 `when` 切换 Composable，不引入 navigation-compose；
- 去掉 `ui-tooling` 的 release 打包（仅 `debugImplementation`）；
- 资源仅保留中文（`resourceConfigurations += listOf("zh", "zh-rCN")`）；
- 图标全部为 vector drawable（自适应图标），**无多密度 mipmap PNG**；
- 打包时排除 `META-INF` 冗余、`kotlin/**`、`DebugProbesKt.bin`、`*.proto`；
- Coil 内存缓存限制为可用内存的 **25%**，**禁用磁盘缓存**；
- 歌词只解析当前歌曲，解析后立即释放；
- 全局仅一个 ExoPlayer 实例（在 Service 中），UI 通过 MediaController 连接；
- 目标：release APK **≤ 15MB**（CI 中会自动校验并在超出时给出 warning）。

---

## 9. 服务端接口一览

统一响应格式：`{ "code": 0, "message": "ok", "data": {...} }`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/search?keywords=周杰伦&page=1&limit=20` | 搜索（分页） |
| GET | `/recommend/songs` | 首页推荐 |
| GET | `/song/urls?id=<songmid>` | 解析直链（flac→ape→320→128 自动降级） |
| GET | `/song/lyric?id=<songmid>` | 歌词（原始 LRC + 翻译） |
| GET | `/song/detail?id=<songmid>` | 单曲详情 |
| POST | `/login/qr/create` | 创建登录二维码（服务端完成 QRC 解密） |
| GET | `/login/qr/check?identifier=<uuid>` | 轮询扫码状态（每 2 秒） |
| GET | `/user/info` | 当前登录用户（读取请求头 Cookie） |
| GET | `/login/logout` | 退出登录 |
| GET | `/health` | 健康检查 |

Cookie 传递优先级：`Cookie` 请求头 → `?cookie=` 查询参数 → `X-QQ-Cookie` 头 → JSON body 的 `cookie` 字段。

---

## 10. 已知限制

1. **release 使用 debug 签名**，每次 GitHub Actions 构建的签名可能不同，**更新 App 时需先卸载旧版**。
2. **Android 15 必须把 App 加入电池优化白名单**，否则后台播放可能被系统中断（见 5.1 节）。
3. QQ 音乐接口为第三方非公开接口，**可能随时变更或失效**。失效时界面会显示错误、不会自动切换音乐源。
4. 手机与电脑需在同一局域网；服务端**无鉴权**，请勿暴露到公网。
5. 本项目仅供个人学习自用，**不上架**，请自行承担版权风险。
6. 不提供音质选择、下载、缓存、歌单、收藏、播放历史 —— 这是刻意设计，不是缺失。

---

## 11. 常见问题

**Q：首页一直转圈 / 提示无法连接服务端？**
A：确认 `npm start` 已运行；确认 `ApiConfig.kt` 的地址与端口正确；模拟器用 `10.0.2.2`，真机用局域网 IP；Windows 防火墙放行 3200 端口；手机与电脑同一 Wi-Fi。

**Q：VIP 歌曲点播放没声音？**
A：先到「登录」页扫码登录，登录后即可播放 VIP 歌曲。未登录时 VIP 会自动提示并跳到下一首。

**Q：切歌还有一点停顿？**
A：首次播放某首歌需要实时解析直链（网络往返）。预取机制会在当前歌曲播放期间解析下一首，因此正常连续播放时切歌几乎瞬时。若当前歌曲播放不足几秒就手动切歌，可能仍有一次网络等待。

**Q：通知栏没有控制按钮？**
A：检查是否授予了通知权限（设置 → 应用 → 嘻嘻音乐 → 通知）。

**Q：构建报 `SDK location not found`？**
A：本地未设置 Android SDK。安装 Android Studio 后创建 `local.properties` 写入 `sdk.dir=C:/Users/你的用户名/AppData/Local/Android/Sdk`（用正斜杠），或设置环境变量 `ANDROID_HOME`。CI 环境会自动处理。

**Q：`gradlew` 报错 / 提示找不到 gradle-wrapper.jar？**
A：本仓库提交了 `gradlew` 与 `gradlew.bat` 脚本，但**不提交** `gradle-wrapper.jar`（二进制）。运行 `build_apk.bat`（或 `build_apk.sh`）会自动用系统 `gradle wrapper --gradle-version 8.9` 生成；也可以本机手动执行同一条命令；或用 Android Studio 打开项目由 Studio 自动补全。GitHub Actions 上无需任何操作，工作流里已包含该步骤。
