package com.xixi.music.data.remote

import com.xixi.music.data.model.ApiResponse
import com.xixi.music.data.model.HealthData
import com.xixi.music.data.model.LyricData
import com.xixi.music.data.model.QrCheck
import com.xixi.music.data.model.QrCreate
import com.xixi.music.data.model.RecommendData
import com.xixi.music.data.model.SearchPage
import com.xixi.music.data.model.Song
import com.xixi.music.data.model.SongUrl
import com.xixi.music.data.model.UserInfo
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 自建 Node.js 服务端接口定义。
 * 所有接口统一返回 { code, message, data }。
 */
interface MusicApi {

    /** 搜索，分页每页 20 条 */
    @GET("search")
    suspend fun search(
        @Query("keywords") keywords: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<SearchPage>>

    /** 首页推荐 */
    @GET("recommend/songs")
    suspend fun recommend(): Response<ApiResponse<RecommendData>>

    /** 实时解析直链；quality 不传则由服务端按 flac→ape→320→128 自动降级 */
    @GET("song/urls")
    suspend fun songUrls(
        @Query("id") id: String,
        @Query("quality") quality: String? = null
    ): Response<ApiResponse<SongUrl>>

    /** 歌词（原始 LRC） */
    @GET("song/lyric")
    suspend fun lyric(@Query("id") id: String): Response<ApiResponse<LyricData>>

    /** 单曲详情 */
    @GET("song/detail")
    suspend fun detail(@Query("id") id: String): Response<ApiResponse<Song>>

    /** 创建登录二维码 */
    @POST("login/qr/create")
    suspend fun qrCreate(@Body body: Map<String, String> = emptyMap()): Response<ApiResponse<QrCreate>>

    /** 轮询扫码状态 */
    @GET("login/qr/check")
    suspend fun qrCheck(@Query("identifier") identifier: String): Response<ApiResponse<QrCheck>>

    /** 当前登录用户信息 */
    @GET("user/info")
    suspend fun userInfo(): Response<ApiResponse<UserInfo>>

    /** 退出登录（服务端无状态，仅用于通知） */
    @GET("login/logout")
    suspend fun logout(): Response<ApiResponse<Map<String, Any>>>

    /** 健康检查：用于「服务端是否已启动」的提示 */
    @GET("health")
    suspend fun health(): Response<ApiResponse<HealthData>>
}
