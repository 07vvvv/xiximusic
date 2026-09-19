@echo off
rem ===========================================================================
rem  嘻嘻音乐 XixiMusic -- APK 构建脚本 Windows 版
rem  用法: build_apk.bat [--debug] [--clean] [--install]
rem
rem  说明:
rem    - 默认构建 Release 版本 assembleRelease, 使用 debug 签名配置, 无需 keystore。
rem    - 仓库不提交 gradle-wrapper.jar, 缺失时用系统 gradle 生成 wrapper,
rem      绝不联网下载任何二进制文件。
rem    - 文件为 UTF-8 编码, 先切到 65001 代码页, 保证中文提示不乱码。
rem ===========================================================================

chcp 65001 >nul 2>nul
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "GRADLE_VERSION=8.9"
set "JDK_REQUIRED=17"

set "BUILD_TYPE=release"
set "DO_CLEAN=0"
set "DO_INSTALL=0"

rem ---------------------------------------------------------------------------
rem  1. 解析命令行参数
rem ---------------------------------------------------------------------------
:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="--debug" (
    set "BUILD_TYPE=debug"
    shift
    goto :parse_args
)
if /i "%~1"=="--clean" (
    set "DO_CLEAN=1"
    shift
    goto :parse_args
)
if /i "%~1"=="--install" (
    set "DO_INSTALL=1"
    shift
    goto :parse_args
)
if /i "%~1"=="-h" goto :show_help
if /i "%~1"=="--help" goto :show_help
echo [错误] 未知参数: %~1
call :usage
exit /b 2

:show_help
call :usage
exit /b 0

rem ---------------------------------------------------------------------------
rem  2. 目标与产物路径
rem ---------------------------------------------------------------------------
:args_done

set "GRADLE_TASK=assembleRelease"
set "APK_PATH=app\build\outputs\apk\release\app-release.apk"
set "OUT_NAME=xixi-music-release.apk"
if /i "!BUILD_TYPE!"=="debug" (
    set "GRADLE_TASK=assembleDebug"
    set "APK_PATH=app\build\outputs\apk\debug\app-debug.apk"
    set "OUT_NAME=xixi-music-debug.apk"
)

echo ============================================================
echo   嘻嘻音乐 XixiMusic 构建脚本
echo   项目目录: %CD%
echo   构建类型: !BUILD_TYPE!
echo ============================================================
echo.

rem ---------------------------------------------------------------------------
rem  3. 检查 Java 环境, 版本不对只警告
rem ---------------------------------------------------------------------------
echo ^>^> 检查 Java 环境 ...
where java >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 java 命令, 请安装 JDK %JDK_REQUIRED% 并加入 PATH。
    goto :fail
)

set "JAVA_LINE="
for /f "tokens=*" %%v in ('java -version 2^>^&1') do (
    if not defined JAVA_LINE set "JAVA_LINE=%%v"
)
echo     !JAVA_LINE!

set "JAVA_VERSION_RAW=!JAVA_LINE:*version =!"
set "JAVA_VERSION=!JAVA_VERSION_RAW:"=!"
set "JAVA_MAJOR="
for /f "tokens=1 delims=. " %%v in ("!JAVA_VERSION!") do set "JAVA_MAJOR=%%v"
rem 只接受纯数字主版本号, 避免把带引号或异常的字符串带进后面的 if 比较
echo !JAVA_MAJOR!| findstr /r "^[0-9][0-9]*$" >nul 2>nul
if errorlevel 1 set "JAVA_MAJOR=unknown"
rem 兼容 1.8.0 这类旧命名
if "!JAVA_MAJOR!"=="1" for /f "tokens=2 delims=. " %%v in ("!JAVA_VERSION!") do set "JAVA_MAJOR=%%v"

if not "!JAVA_MAJOR!"=="%JDK_REQUIRED%" (
    echo [警告] 检测到 Java 主版本 !JAVA_MAJOR!, 本项目要求 JDK %JDK_REQUIRED%。
    echo [警告] 构建可能失败, 仍会继续尝试。
)
echo.

rem ---------------------------------------------------------------------------
rem  4. local.properties: Release 用 debug 签名无需 keystore, 但 SDK 路径要有
rem     注意 Gradle 读取的是 java.util.Properties, 反斜杠是转义符,
rem     因此这里把 SDK 路径的反斜杠统一换成正斜杠。
rem ---------------------------------------------------------------------------
if not exist "local.properties" (
    set "SDK_DIR="
    if defined ANDROID_HOME set "SDK_DIR=!ANDROID_HOME!"
    if not defined SDK_DIR if defined ANDROID_SDK_ROOT set "SDK_DIR=!ANDROID_SDK_ROOT!"
    if defined SDK_DIR (
        set "SDK_DIR=!SDK_DIR:\=/!"
        > "local.properties" echo sdk.dir=!SDK_DIR!
        echo ^>^> 已生成 local.properties: sdk.dir=!SDK_DIR!
    ) else (
        echo [警告] 未找到 local.properties, 且 ANDROID_HOME / ANDROID_SDK_ROOT 均未设置。
        echo [警告] 若构建因缺少 Android SDK 失败, 请先设置 ANDROID_HOME 指向 SDK 目录。
    )
    echo.
)

rem ---------------------------------------------------------------------------
rem  5. Gradle wrapper: jar 未提交到仓库, 缺失时用系统 gradle 生成
rem ---------------------------------------------------------------------------
rem 仓库里的 gradlew.bat / gradle-wrapper.jar 都是二进制或不提交的文件,
rem 任一缺失就用系统 gradle 重新生成完整 wrapper。
set "NEED_WRAPPER=0"
if not exist "gradle\wrapper\gradle-wrapper.jar" set "NEED_WRAPPER=1"
if not exist "gradlew.bat" set "NEED_WRAPPER=1"

if "!NEED_WRAPPER!"=="1" (
    echo [警告] 缺少 Gradle wrapper, 尝试用系统 gradle 生成 Gradle %GRADLE_VERSION% wrapper ...
    where gradle >nul 2>nul
    if errorlevel 1 (
        echo [错误] 系统 PATH 中没有 gradle 命令, 无法生成 wrapper。
        echo         请任选一种方式解决后重新运行:
        echo           1 安装 Gradle %GRADLE_VERSION% 并确保 gradle 在 PATH 中
        echo           2 手动把 gradlew.bat 和 gradle-wrapper.jar 放回项目
        echo           3 用 Android Studio 打开本项目, 让它自动生成 wrapper
        goto :fail
    )
    call gradle wrapper --gradle-version %GRADLE_VERSION%
    if errorlevel 1 (
        echo [错误] gradle wrapper --gradle-version %GRADLE_VERSION% 执行失败, 请检查上方输出。
        goto :fail
    )
    if not exist "gradle\wrapper\gradle-wrapper.jar" goto :wrapper_incomplete
    if not exist "gradlew.bat" goto :wrapper_incomplete
    echo ^>^> wrapper 生成完成。
    echo.
)

if not exist "gradlew.bat" (
    echo [错误] 未找到 gradlew.bat, 无法构建。请先恢复 Gradle wrapper 脚本。
    goto :fail
)
goto :after_wrapper_check

:wrapper_incomplete
echo [错误] gradle wrapper 已执行, 但仍缺少 gradlew.bat 或 gradle\wrapper\gradle-wrapper.jar。
goto :fail

:after_wrapper_check

rem ---------------------------------------------------------------------------
rem  6. 执行构建
rem ---------------------------------------------------------------------------
set "GRADLE_TASKS=!GRADLE_TASK!"
if "!DO_CLEAN!"=="1" set "GRADLE_TASKS=clean !GRADLE_TASK!"

echo ^>^> 开始构建: gradlew.bat !GRADLE_TASKS!
echo.
call gradlew.bat !GRADLE_TASKS!
if errorlevel 1 (
    echo.
    echo Gradle 返回非零退出码, 错误见上方输出。
    goto :fail
)
echo.

rem ---------------------------------------------------------------------------
rem  7. 校验产物, 复制到项目根目录, 统计体积
rem ---------------------------------------------------------------------------
if not exist "!APK_PATH!" (
    echo [错误] 构建流程结束, 但未找到产物: !APK_PATH!
    goto :fail
)

copy /y "!APK_PATH!" "!OUT_NAME!" >nul
if errorlevel 1 (
    echo [警告] 复制 APK 到项目根目录失败: !OUT_NAME!
) else (
    echo [信息] 已复制到项目根目录: !OUT_NAME!
)

set "SIZE="
for %%A in ("!APK_PATH!") do set "SIZE=%%~zA"
set "SIZE_MB=0"
if not "!SIZE!"=="" set /a SIZE_MB=!SIZE!/1048576

echo ------------------------------------------------------------
echo   APK 路径 : %CD%\!APK_PATH!
echo   体积     : !SIZE_MB! MB - !SIZE! 字节
if !SIZE_MB! LSS 15 (
    echo   体积检查 : 小于 15 MB - 通过
) else (
    echo   体积检查 : 已达到或超过 15 MB - 请注意控制包体
)
echo ------------------------------------------------------------
echo.

rem ---------------------------------------------------------------------------
rem  8. 可选安装到设备
rem ---------------------------------------------------------------------------
if "!DO_INSTALL!"=="1" (
    where adb >nul 2>nul
    if errorlevel 1 (
        echo [警告] 未找到 adb 命令, 跳过安装步骤。
    ) else (
        echo ^>^> 正在安装到设备: adb install -r !APK_PATH!
        call adb install -r "!APK_PATH!"
        if errorlevel 1 (
            echo [警告] adb install 失败, 请检查设备连接与 USB 调试状态。
            goto :fail
        ) else (
            echo ^>^> 安装成功。
        )
    )
    echo.
)

echo ============================================================
echo   构建成功 SUCCESS
echo   产物: %CD%\!OUT_NAME!
echo ============================================================
exit /b 0

rem ---------------------------------------------------------------------------
rem  失败出口
rem ---------------------------------------------------------------------------
:fail
echo.
echo ============================================================
echo   构建 FAILED -- 请查看上方错误输出
echo ============================================================
exit /b 1

rem ---------------------------------------------------------------------------
rem  帮助
rem ---------------------------------------------------------------------------
:usage
echo 用法: build_apk.bat [选项]
echo.
echo 选项:
echo   --debug       构建 Debug 版本 assembleDebug, 默认构建 Release 版本 assembleRelease
echo   --clean       构建前先执行 gradlew clean
echo   --install     构建完成后, 如果检测到 adb, 自动执行 adb install -r 安装到设备
echo   -h, --help    显示本帮助信息
echo.
echo 产物:
echo   Release : app\build\outputs\apk\release\app-release.apk
echo             并复制到项目根目录 xixi-music-release.apk
echo   Debug   : app\build\outputs\apk\debug\app-debug.apk
echo             并复制到项目根目录 xixi-music-debug.apk
echo.
echo 环境要求:
echo   JDK 17, Android SDK, Gradle 8.9 wrapper。
echo   若缺少 gradle\wrapper\gradle-wrapper.jar, 脚本会用 PATH 中的系统 gradle
echo   执行 gradle wrapper --gradle-version 8.9 生成, 不会联网下载二进制文件。
echo   若缺少 local.properties, 脚本会用 ANDROID_HOME 或 ANDROID_SDK_ROOT 自动生成。
goto :eof
