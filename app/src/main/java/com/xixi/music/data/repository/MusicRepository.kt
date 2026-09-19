package com.xixi.music.data.repository

import com.google.gson.JsonParser
import com.xixi.music.data.local.SessionStore
import com.xixi.music.data.model.ApiCode
import com.xixi.music.data.model.ApiError
import com.xixi.music.data.model.ApiResponse
import com.xixi.music.data.model.HealthData
import com.xixi.music.data.model.LyricData
import com.xixi.music.data.model.QrCheck
import com.xixi.music.data.model.QrCreate
import com.xixi.music.data.model.Result
import com.xixi.music.data.model.SearchPage
import com.xixi.music.data.model.Song
import com.xixi.music.data.model.SongUrl
import com.xixi.music.data.model.UserInfo
import com.xixi.music.data.remote.CookieInterceptor
import com.xixi.music.data.remote.MusicApi
import com.xixi.music.data.remote.NetworkModule
import com.xixi.music.util.SmallCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException

/**
 * 音乐数据仓库：所有网络访问都经过自建服务端。
 *
 * 内存约束：只缓存直链字符串与歌词文本；音频数据一律不落盘、不缓存。
 * 直链缓存 TTL = 120s（QQ 直链本身很短命，过期必须重新解析）。
 *
 * 泛型写法约定（重要）：
 * 本项目所有 `call { ... }` 都显式写出类型参数，并把分支结果先赋给「带显式类型标注的
 * 局部变量」再返回。原因是 `Result.Err` 的类型是 `Result<Nothing>`，当 lambda 内同时
 * 出现 `Result.Ok(x)` 与返回 `Result.Err` 的分支时，编译器没有足够信息推断出 `T`
 * （曾导致 GitHub Actions 编译失败："Argument type mismatch ... but Result<T> was expected"）。
 */
class MusicRepository(
    private val sessionStore: SessionStore,
    private val api: MusicApi = NetworkModule.api(sessionStore)
) {

    private val urlCache = SmallCache<String, SongUrl>(maxSize = 16, ttlMillis = URL_TTL_MS)
    private val lyricCache = SmallCache<String, LyricData>(maxSize = 12, ttlMillis = LYRIC_TTL_MS)
    private val detailCache = SmallCache<String, Song>(maxSize = 32, ttlMillis = DETAIL_TTL_MS)

    // ---------------------------------------------------------------- 首页

    /** 首页推荐列表 */
    suspend fun recommend(): Result<List<Song>> = call<List<Song>> {
        val page = api.recommend()
        val body = page.body()
        val data = body?.data
        if (body?.code == ApiCode.OK && data != null) {
            Result.Ok<List<Song>>(data.list)
        } else {
            errFrom(page)
        }
    }

    /** 搜索（分页每页 20 条） */
    suspend fun search(
        keywords: String,
        page: Int,
        limit: Int = PAGE_SIZE
    ): Result<SearchPage> = call<SearchPage> {
        val response = api.search(keywords, page, limit)
        val body = response.body()
        val data: SearchPage? = body?.data
        if (response.isSuccessful && body?.code == ApiCode.OK && data != null) {
            Result.Ok<SearchPage>(data)
        } else {
            errFrom(response)
        }
    }

    // ---------------------------------------------------------------- 播放

    /**
     * 实时解析直链（不复用旧直链）。
     * 服务端按 flac → ape → 320 → 128 自动降级，客户端只拿最终结果。
     */
    suspend fun resolveUrl(songId: String, forceRefresh: Boolean = false): Result<SongUrl> {
        if (!forceRefresh) {
            val cached: SongUrl? = urlCache.get(songId)
            if (cached != null) return Result.Ok<SongUrl>(cached)
        } else {
            urlCache.remove(songId)
        }
        return call<SongUrl> {
            val response = api.songUrls(songId, null)
            val body = response.body()
            val data: SongUrl? = body?.data
            if (
                response.isSuccessful &&
                body?.code == ApiCode.OK &&
                data != null &&
                data.playable
            ) {
                urlCache.put(songId, data, URL_TTL_MS)
                Result.Ok<SongUrl>(data)
            } else {
                errFrom(response)
            }
        }
    }

    /** 歌词：只解析当前歌曲，不保留历史 */
    suspend fun lyric(songId: String): Result<LyricData> {
        val cached: LyricData? = lyricCache.get(songId)
        if (cached != null) return Result.Ok<LyricData>(cached)
        return call<LyricData> {
            val response = api.lyric(songId)
            val body = response.body()
            val data: LyricData? = body?.data
            if (response.isSuccessful && body?.code == ApiCode.OK && data != null) {
                lyricCache.put(songId, data, LYRIC_TTL_MS)
                Result.Ok<LyricData>(data)
            } else {
                errFrom(response)
            }
        }
    }

    /** 单曲详情 */
    suspend fun detail(songId: String): Result<Song> {
        val cached: Song? = detailCache.get(songId)
        if (cached != null) return Result.Ok<Song>(cached)
        return call<Song> {
            val response = api.detail(songId)
            val body = response.body()
            val data: Song? = body?.data
            if (response.isSuccessful && body?.code == ApiCode.OK && data != null) {
                detailCache.put(songId, data, DETAIL_TTL_MS)
                Result.Ok<Song>(data)
            } else {
                errFrom(response)
            }
        }
    }

    // ---------------------------------------------------------------- 登录

    suspend fun qrCreate(): Result<QrCreate> = call<QrCreate> {
        val response = api.qrCreate(emptyMap())
        val body = response.body()
        val data: QrCreate? = body?.data
        if (response.isSuccessful && body?.code == ApiCode.OK && data != null) {
            Result.Ok<QrCreate>(data)
        } else {
            errFrom(response)
        }
    }

    suspend fun qrCheck(identifier: String): Result<QrCheck> = call<QrCheck> {
        val response = api.qrCheck(identifier)
        val body = response.body()
        val data: QrCheck? = body?.data
        if (response.isSuccessful && body?.code == ApiCode.OK && data != null) {
            Result.Ok<QrCheck>(data)
        } else {
            errFrom(response)
        }
    }

    suspend fun userInfo(): Result<UserInfo> = call<UserInfo> {
        val response = api.userInfo()
        val body = response.body()
        val data: UserInfo? = body?.data
        if (response.isSuccessful && body?.code == ApiCode.OK && data != null) {
            Result.Ok<UserInfo>(data)
        } else {
            errFrom(response)
        }
    }

    /** 通知服务端退出登录（服务端无状态，仅清会话与文件 Cookie） */
    suspend fun logoutRemote(): Result<Boolean> = call<Boolean> {
        val response = api.logout()
        if (response.isSuccessful) {
            Result.Ok<Boolean>(true)
        } else {
            errFrom(response)
        }
    }

    suspend fun health(): Result<HealthData> = call<HealthData> {
        val response = api.health()
        val body = response.body()
        val data: HealthData? = body?.data
        if (response.isSuccessful && body?.code == ApiCode.OK && data != null) {
            Result.Ok<HealthData>(data)
        } else {
            errFrom(response)
        }
    }

    /** 清空所有内存缓存（换号/退出登录时调用） */
    fun clearCaches() {
        urlCache.clear()
        lyricCache.clear()
        detailCache.clear()
    }

    /** 供拦截器使用：当前 Cookie */
    suspend fun currentCookie(): String = sessionStore.currentCookie()

    // ---------------------------------------------------------------- 内部

    /**
     * 统一异常拦截：网络异常转成友好的 Result.Err，绝不抛出到 UI。
     *
     * block 显式声明为 `suspend () -> Result<T>`，为调用处的 lambda 提供目标类型；
     * 配合调用处的显式类型参数，彻底避免 T 无法推断的问题。
     */
    private suspend fun <T> call(block: suspend () -> Result<T>): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (io: IOException) {
                Result.Err(
                    ApiError(
                        code = ERR_NETWORK,
                        message = "无法连接服务端：${io.message ?: "网络错误"}"
                    )
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Result.Err(ApiError(code = ERR_UNKNOWN, message = t.message ?: "未知错误"))
            }
        }

    /**
     * 从响应里取业务错误码。
     *
     * 关键点：Retrofit 在非 2xx 时不会填充 body()，业务错误信息在 errorBody() 里。
     * 服务端对「需要登录 / 需要 VIP」这类失败会带 HTTP 401/404，因此必须解析
     * errorBody 才能拿到 40101 / 40401 这类有意义的业务码。
     *
     * 返回类型固定为 Result.Err（Result<Nothing>），对任意 Result<T> 都成立。
     */
    private fun <T> errFrom(response: Response<ApiResponse<T>>): Result.Err {
        val body = response.body()
        if (body != null) {
            return Result.Err(ApiError(body.code, body.message ?: "请求失败"))
        }

        val raw = runCatching { response.errorBody()?.string() }.getOrNull()
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            if (parsed != null) {
                val code = runCatching { parsed.get("code").asInt }.getOrDefault(0)
                val message = runCatching { parsed.get("message").asString }.getOrNull()
                if (code != 0 || !message.isNullOrBlank()) {
                    return Result.Err(
                        ApiError(
                            code = if (code != 0) code else response.code(),
                            message = message ?: "请求失败"
                        )
                    )
                }
            }
        }

        return Result.Err(
            ApiError(
                code = response.code(),
                message = "服务端返回 ${response.code()} ${response.message()}"
            )
        )
    }

    companion object {
        const val PAGE_SIZE = 20
        const val URL_TTL_MS = 120_000L
        const val LYRIC_TTL_MS = 300_000L
        const val DETAIL_TTL_MS = 300_000L
        const val ERR_NETWORK = -2
        const val ERR_UNKNOWN = -3
        const val COOKIE_HEADER = CookieInterceptor.HEADER_COOKIE
    }
}
