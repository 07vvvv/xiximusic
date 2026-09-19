#!/usr/bin/env bash
#
# 嘻嘻音乐 XixiMusic —— APK 构建脚本（Linux / macOS）
#
# 用法:
#   ./build_apk.sh [--debug] [--clean] [--install]
#
# 说明:
#   - 默认构建 Release 版本（assembleRelease，使用 debug 签名配置，无需 keystore）。
#   - 兼容 macOS 自带的 bash 3.2，不使用关联数组 / mapfile。
#   - 仓库中不提交 gradle-wrapper.jar（二进制文件），脚本会在缺失时用系统
#     gradle 生成 wrapper，绝不会联网下载任何二进制文件。
#
set -euo pipefail

# ---------------------------------------------------------------------------
# 0. 定位脚本自身目录，保证从任意 cwd 调用都能正确工作
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
PROJECT_ROOT="$SCRIPT_DIR"

GRADLE_VERSION="8.9"
JDK_REQUIRED="17"

BUILD_TYPE="release"
DO_CLEAN=0
DO_INSTALL=0

usage() {
    cat <<'USAGE'
用法: ./build_apk.sh [选项]

选项:
  --debug       构建 Debug 版本 (assembleDebug)，默认构建 Release 版本 (assembleRelease)
  --clean       构建前先执行 gradlew clean
  --install     构建完成后，如果检测到 adb，自动执行 adb install -r 安装到设备
  -h, --help    显示本帮助信息

产物:
  Release : app/build/outputs/apk/release/app-release.apk
            并复制到项目根目录 xixi-music-release.apk
  Debug   : app/build/outputs/apk/debug/app-debug.apk
            并复制到项目根目录 xixi-music-debug.apk

环境要求:
  JDK 17、Android SDK、Gradle 8.9 wrapper。
  若缺少 gradle/wrapper/gradle-wrapper.jar，脚本会尝试用 PATH 中的系统 gradle
  执行 `gradle wrapper --gradle-version 8.9` 生成；不会联网下载二进制文件。
  若缺少 local.properties，脚本会用 ANDROID_HOME / ANDROID_SDK_ROOT 自动生成。
USAGE
}

# ---------------------------------------------------------------------------
# 1. 解析命令行参数
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --debug)
            BUILD_TYPE="debug"
            ;;
        --clean)
            DO_CLEAN=1
            ;;
        --install)
            DO_INSTALL=1
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "错误: 未知参数 '$1'" >&2
            echo "" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

if [ "$BUILD_TYPE" = "debug" ]; then
    GRADLE_TASK="assembleDebug"
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    OUT_NAME="xixi-music-debug.apk"
else
    GRADLE_TASK="assembleRelease"
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
    OUT_NAME="xixi-music-release.apk"
fi

echo "============================================================"
echo "  嘻嘻音乐 XixiMusic 构建脚本"
echo "  项目目录: ${PROJECT_ROOT}"
echo "  构建类型: ${BUILD_TYPE}"
echo "============================================================"
echo ""

# ---------------------------------------------------------------------------
# 2. 检查 Java 环境（缺失则直接失败，版本不对只警告）
# ---------------------------------------------------------------------------
echo ">> 检查 Java 环境 ..."
if ! command -v java >/dev/null 2>&1; then
    echo "错误: 未找到 java 命令。请安装 JDK ${JDK_REQUIRED}，并确保其 bin 目录在 PATH 中。" >&2
    exit 1
fi

# 不用 `| head -n 1`: pipefail 下 java 可能因 SIGPIPE 被误判为失败
JAVA_VERSION_FULL="$(java -version 2>&1)"
JAVA_VERSION_LINE="${JAVA_VERSION_FULL%%$'\n'*}"
echo "    ${JAVA_VERSION_LINE}"

JAVA_MAJOR="$(printf '%s\n' "$JAVA_VERSION_LINE" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')"
if [ "$JAVA_MAJOR" = "1" ]; then
    # 形如 "1.8.0_392" 的旧版命名
    JAVA_MAJOR="$(printf '%s\n' "$JAVA_VERSION_LINE" | sed -n 's/.*version "1\.\([0-9][0-9]*\).*/\1/p')"
fi
if [ -z "$JAVA_MAJOR" ]; then
    JAVA_MAJOR="unknown"
fi

if [ "$JAVA_MAJOR" != "$JDK_REQUIRED" ]; then
    echo "警告: 检测到 Java 主版本 ${JAVA_MAJOR}，本项目要求 JDK ${JDK_REQUIRED}。"
    echo "警告: 构建可能失败，仍会继续尝试。"
fi
echo ""

# ---------------------------------------------------------------------------
# 3. local.properties：Release 用 debug 签名，无需 keystore，但 SDK 路径仍要有
# ---------------------------------------------------------------------------
if [ ! -f local.properties ]; then
    SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -n "$SDK_DIR" ]; then
        printf 'sdk.dir=%s\n' "$SDK_DIR" > local.properties
        echo ">> 已生成 local.properties: sdk.dir=${SDK_DIR}"
    else
        echo "警告: 未找到 local.properties，且 ANDROID_HOME / ANDROID_SDK_ROOT 均未设置。"
        echo "警告: 若构建因缺少 Android SDK 失败，请先导出 ANDROID_HOME 指向 SDK 目录。"
    fi
    echo ""
fi

# ---------------------------------------------------------------------------
# 4. Gradle wrapper：jar 未提交到仓库，缺失时用系统 gradle 生成
# ---------------------------------------------------------------------------
WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
# 仓库里的 gradlew / gradlew.bat / gradle-wrapper.jar 都是二进制或不提交的文件,
# 任一缺失就用系统 gradle 重新生成完整 wrapper。
if [ ! -f "$WRAPPER_JAR" ] || [ ! -f gradlew ]; then
    echo "警告: 缺少 Gradle wrapper (gradlew / ${WRAPPER_JAR})，尝试用系统 gradle 生成 Gradle ${GRADLE_VERSION} wrapper ..."
    if command -v gradle >/dev/null 2>&1; then
        if ! gradle wrapper --gradle-version "${GRADLE_VERSION}"; then
            echo "错误: 'gradle wrapper --gradle-version ${GRADLE_VERSION}' 执行失败，请检查上方输出。" >&2
            exit 1
        fi
    else
        echo "错误: 缺少 Gradle wrapper (gradlew / ${WRAPPER_JAR})，且系统 PATH 中没有 gradle 命令。" >&2
        echo "      请任选一种方式解决后重新运行:" >&2
        echo "        1) 安装 Gradle ${GRADLE_VERSION} 并确保 gradle 在 PATH 中;" >&2
        echo "        2) 手动把 gradlew、gradlew.bat、gradle-wrapper.jar 放回项目;" >&2
        echo "        3) 用 Android Studio 打开本项目，让它自动生成 wrapper。" >&2
        exit 1
    fi

    if [ ! -f "$WRAPPER_JAR" ] || [ ! -f gradlew ]; then
        echo "错误: gradle wrapper 已执行，但仍缺少 gradlew 或 ${WRAPPER_JAR}。" >&2
        exit 1
    fi
    echo ">> wrapper 生成完成。"
    echo ""
fi

if [ -f gradlew ]; then
    chmod +x gradlew
else
    echo "错误: 未找到 gradlew，无法构建。请先恢复 Gradle wrapper 脚本。" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# 5. 执行构建
# ---------------------------------------------------------------------------
GRADLE_TASKS="${GRADLE_TASK}"
if [ "$DO_CLEAN" = "1" ]; then
    GRADLE_TASKS="clean ${GRADLE_TASK}"
fi

echo ">> 开始构建: ./gradlew ${GRADLE_TASKS}"
echo ""
# 故意不加引号: 需要按空格拆分成多个 Gradle task
if ! ./gradlew ${GRADLE_TASKS}; then
    echo ""
    echo "============================================================"
    echo "  构建 FAILED —— Gradle 返回非零退出码，错误见上方输出"
    echo "============================================================"
    exit 1
fi
echo ""

# ---------------------------------------------------------------------------
# 6. 校验产物、复制到项目根目录、统计体积
# ---------------------------------------------------------------------------
if [ ! -f "$APK_PATH" ]; then
    echo "错误: 构建流程结束，但未找到产物 ${APK_PATH}" >&2
    echo "============================================================"
    echo "  构建 FAILED —— 缺少 APK 产物"
    echo "============================================================"
    exit 1
fi

cp -f "$APK_PATH" "$OUT_NAME"

SIZE_BYTES="$(wc -c < "$APK_PATH" | tr -d '[:space:]')"
SIZE_HUMAN="$(du -h "$APK_PATH" | cut -f1)"
SIZE_MB="$(awk -v b="$SIZE_BYTES" 'BEGIN { printf "%.2f", b / 1048576 }')"

echo "------------------------------------------------------------"
echo "  APK 路径 : ${PROJECT_ROOT}/${APK_PATH}"
echo "  已复制到 : ${PROJECT_ROOT}/${OUT_NAME}"
ls -lh "$APK_PATH"
echo "  体积     : ${SIZE_MB} MB (${SIZE_HUMAN}, ${SIZE_BYTES} 字节)"
if [ -n "$SIZE_BYTES" ] && [ "$SIZE_BYTES" -lt 15728640 ]; then
    echo "  体积检查 : 小于 15 MB —— 通过"
else
    echo "  体积检查 : 已达到或超过 15 MB —— 请注意控制包体"
fi
echo "------------------------------------------------------------"
echo ""

# ---------------------------------------------------------------------------
# 7. 可选安装到设备
# ---------------------------------------------------------------------------
if [ "$DO_INSTALL" = "1" ]; then
    if command -v adb >/dev/null 2>&1; then
        echo ">> 正在安装到设备: adb install -r ${APK_PATH}"
        if adb install -r "$APK_PATH"; then
            echo ">> 安装成功。"
        else
            echo "警告: adb install 失败，请检查设备连接与 USB 调试状态。" >&2
            exit 1
        fi
    else
        echo "警告: 未找到 adb 命令，跳过安装步骤。"
    fi
    echo ""
fi

echo "============================================================"
echo "  构建成功 SUCCESS"
echo "  产物: ${PROJECT_ROOT}/${OUT_NAME}"
echo "============================================================"
exit 0
