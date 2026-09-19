package com.xixi.music.data.remote

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.xixi.music.BuildConfig
import com.xixi.music.data.local.SessionStore
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 网络层。只创建一个 Retrofit 实例，OkHttp 连接池全局复用。
 *
 * // TODO 服务端地址请在 ApiConfig.kt 中配置（模拟器 10.0.2.2:3200，真机局域网 IP）
 */
object NetworkModule {

    private const val CONNECT_TIMEOUT_SECONDS = 8L
    private const val READ_TIMEOUT_SECONDS = 12L
    private const val WRITE_TIMEOUT_SECONDS = 12L

    private val gson: Gson = GsonBuilder()
        .setLenient()
        .serializeNulls()
        .create()

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var api: MusicApi? = null

    /** 构建（或复用）OkHttp 客户端 */
    fun okHttpClient(sessionStore: SessionStore): OkHttpClient {
        client?.let { return it }
        synchronized(this) {
            client?.let { return it }

            val builder = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                // Cookie 自动附加
                .addInterceptor(CookieInterceptor(sessionStore))

            if (BuildConfig.API_LOGGING) {
                val logging = HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
                builder.addInterceptor(logging)
            }

            return builder.build().also { client = it }
        }
    }

    /** 构建（或复用）Retrofit 接口 */
    fun api(sessionStore: SessionStore): MusicApi {
        api?.let { return it }
        synchronized(this) {
            api?.let { return it }
            val retrofit = Retrofit.Builder()
                .baseUrl(ApiConfig.baseUrl)
                .client(okHttpClient(sessionStore))
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
            return retrofit.create(MusicApi::class.java).also { api = it }
        }
    }
}
