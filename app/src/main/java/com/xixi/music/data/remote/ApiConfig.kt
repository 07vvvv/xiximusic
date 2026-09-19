package com.xixi.music.data.remote

import com.xixi.music.BuildConfig

/**
 * 服务端地址配置。
 *
 * // TODO 配置点 1：真机调试请把 LAN_HOST 改成你电脑的局域网 IP
 *                   （服务端 `npm start` 启动日志会打印，例如 192.168.1.23）
 * // TODO 配置点 2：端口默认 3200，与服务端 package.json 的默认 PORT 一致
 * // TODO 配置点 3：如果部署到公网/内网域名，把 CUSTOM_BASE_URL 写成完整地址即可（最高优先级）
 * // TODO 配置点 4：模拟器调试保持 USE_EMULATOR_HOST = true（10.0.2.2 指向宿主机）；真机改 false
 */
object ApiConfig {

    /** 电脑的局域网 IP —— 真机必须改这里 */
    private const val LAN_HOST: String = "192.168.1.100"

    /** 服务端端口 */
    private const val PORT: Int = 3200

    /** Android 模拟器专用宿主机地址（模拟器里 10.0.2.2 等价于宿主机的 127.0.0.1） */
    private const val EMULATOR_HOST: String = "10.0.2.2"

    /** 模拟器调试 true；真机改成 false 并填好 LAN_HOST */
    private const val USE_EMULATOR_HOST: Boolean = true

    /** 自定义完整地址，非空时优先级最高，例如 "https://music.example.com/" */
    private const val CUSTOM_BASE_URL: String = ""

    /** Retrofit 需要的 baseUrl，必须以 "/" 结尾 */
    val baseUrl: String
        get() = when {
            CUSTOM_BASE_URL.isNotBlank() -> ensureTrailingSlash(CUSTOM_BASE_URL)
            USE_EMULATOR_HOST -> "http://$EMULATOR_HOST:$PORT/"
            else -> "http://$LAN_HOST:$PORT/"
        }

    /** debug 构建才打印网络日志（release 的 BuildConfig.API_LOGGING = false） */
    val loggingEnabled: Boolean get() = BuildConfig.API_LOGGING

    private fun ensureTrailingSlash(url: String): String =
        if (url.endsWith("/")) url else "$url/"
}
