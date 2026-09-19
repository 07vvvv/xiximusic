package com.xixi.music.data.remote

import com.xixi.music.data.local.SessionStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 自动附加 Cookie 的拦截器。
 *
 * 客户端不实现 QQ 音乐签名逻辑，只把登录 Cookie 原样透传给自建服务端，
 * 由服务端完成签名、QRC 解密与 VIP 直链解析。
 *
 * 注意：这里只读内存副本（cachedCookieSync），绝不在拦截器里做阻塞 IO，
 * 以免拖慢 OkHttp 的调度线程。内存副本由 XixiApp 启动时 warmUp() 预热，
 * 登录 / 退出时同步更新。
 */
class CookieInterceptor(private val sessionStore: SessionStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        val builder = original.newBuilder()
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)

        // 请求自带的 Cookie 优先（例如登录后立刻用新 Cookie 请求 /user/info）
        val headerCookie = original.header(HEADER_COOKIE)
        if (headerCookie.isNullOrBlank()) {
            val saved = sessionStore.cachedCookieSync()
            if (saved.isNotBlank()) {
                builder.header(HEADER_COOKIE, saved)
            }
        }

        return chain.proceed(builder.build())
    }

    companion object {
        const val HEADER_COOKIE = "Cookie"
        const val USER_AGENT = "XixiMusic/1.0 (Android)"
    }
}
