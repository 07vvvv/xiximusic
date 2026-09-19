package com.xixi.music.data.model

import com.google.gson.annotations.SerializedName

/**
 * 服务端统一响应包裹：{ code, message, data }
 * code == 0 表示成功。
 *
 * 关于 Gson + Kotlin 的重要说明：
 * Gson 对 Kotlin 类会走 Unsafe 分配并直接写字段，**不会调用构造函数**，
 * 因此 Kotlin 构造参数的默认值在反序列化时并不生效，缺失字段会留下
 * JVM 默认值（引用类型为 null）。为避免「声明为非空却实际是 null」这类
 * 隐式崩溃，本项目所有响应模型统一：
 *   1. 使用 var + 显式初值（无参构造路径安全）；
 *   2. 服务端字段名与 @SerializedName 严格一一对应。
 * 读模型时仍然用 isSuccess / songId / displaySinger 这类派生属性做兜底。
 */
data class ApiResponse<T>(
    @SerializedName("code") var code: Int = -1,
    @SerializedName("message") var message: String? = null,
    @SerializedName("data") var data: T? = null
) {
    val isSuccess: Boolean get() = code == 0
}

/** 服务端错误码（与 server/src/routes/index.js 保持一致） */
object ApiCode {
    const val OK = 0
    const val NO_COOKIE = 40101
    const val NEED_VIP = 40401
}

/**
 * 歌手。
 *
 * id 用 String：服务端在没有歌手 id 时会返回空字符串 ""，
 * 若声明为 Long，Gson 解析空串会直接抛 JsonSyntaxException 导致整批歌曲解析失败。
 */
data class Singer(
    @SerializedName("id") var id: String = "",
    @SerializedName("mid") var mid: String = "",
    @SerializedName("name") var name: String = ""
)

/** 付费信息 */
data class PayInfo(
    @SerializedName("play") var play: Int = 0,
    @SerializedName("download") var download: Int = 0
)

/**
 * 歌曲模型，字段与服务端 /search、/recommend/songs、/song/detail 返回一致。
 */
data class Song(
    @SerializedName("id") var id: String = "",
    @SerializedName("mid") var mid: String = "",
    @SerializedName("name") var name: String = "",
    @SerializedName("singer") var singer: String = "",
    @SerializedName("singers") var singers: List<Singer> = emptyList(),
    @SerializedName("album") var album: String = "",
    @SerializedName("albumMid") var albumMid: String = "",
    @SerializedName("cover") var cover: String = "",
    @SerializedName("duration") var duration: Int = 0,
    @SerializedName("interval") var interval: Int = 0,
    @SerializedName("vip") var vip: Boolean = false,
    @SerializedName("pay") var pay: PayInfo? = null,
    @SerializedName("quality") var quality: List<String> = emptyList()
) {
    /** 实际歌曲 id：优先 mid，服务端两者同值，做一次兜底 */
    val songId: String get() = mid.ifBlank { id }

    /** 时长（秒）：duration 与 interval 取非零值 */
    val durationSeconds: Int get() = if (duration > 0) duration else interval

    /** 展示用歌手名；列表为空时返回空串，UI 再回退到「未知歌手」 */
    val displaySinger: String
        get() = singer.ifBlank { singers.joinToString("/") { it.name } }
}

/** 搜索分页结果 */
data class SearchPage(
    @SerializedName("list") var list: List<Song> = emptyList(),
    @SerializedName("total") var total: Int = 0,
    @SerializedName("page") var page: Int = 1,
    @SerializedName("limit") var limit: Int = 20,
    @SerializedName("hasMore") var hasMore: Boolean = false
)

/** 推荐列表 */
data class RecommendData(
    @SerializedName("list") var list: List<Song> = emptyList()
)

/**
 * 直链解析结果。
 * quality 为实际可用音质（flac/ape/320/128），界面不展示，仅内部使用。
 */
data class SongUrl(
    @SerializedName("url") var url: String = "",
    @SerializedName("quality") var quality: String = "",
    @SerializedName("qualityLabel") var qualityLabel: String = "",
    @SerializedName("size") var size: Long = 0L,
    @SerializedName("expiredAt") var expiredAt: Long = 0L
) {
    val playable: Boolean get() = url.isNotBlank()
}

/** 歌词 */
data class LyricData(
    @SerializedName("lyric") var lyric: String = "",
    @SerializedName("trans") var trans: String = ""
) {
    val empty: Boolean get() = lyric.isBlank() && trans.isBlank()
}

/** 二维码登录创建结果 */
data class QrCreate(
    @SerializedName("qrsig") var qrsig: String = "",
    @SerializedName("identifier") var identifier: String = "",
    @SerializedName("qrImage") var qrImage: String = "",
    @SerializedName("expireSeconds") var expireSeconds: Int = 120
)

/** 二维码登录轮询结果 */
data class QrCheck(
    @SerializedName("status") var status: String = QrStatus.WAITING,
    @SerializedName("cookie") var cookie: String? = null,
    @SerializedName("nickname") var nickname: String? = null,
    @SerializedName("avatar") var avatar: String? = null
)

object QrStatus {
    const val WAITING = "waiting"
    const val SCANNED = "scanned"
    const val CONFIRMED = "confirmed"
    const val EXPIRED = "expired"
    const val REFUSED = "refused"
}

/** 用户信息 */
data class UserInfo(
    @SerializedName("nickname") var nickname: String = "",
    @SerializedName("avatar") var avatar: String = "",
    @SerializedName("vip") var vip: Boolean = false,
    @SerializedName("uin") var uin: String = ""
)

/** /health 返回 */
data class HealthData(
    @SerializedName("name") var name: String = "",
    @SerializedName("version") var version: String = "",
    @SerializedName("uptime") var uptime: Double = 0.0,
    @SerializedName("qqApiPackage") var qqApiPackage: Boolean = false
)

/** 统一错误对象，供 UI 提示使用 */
data class ApiError(
    val code: Int = -1,
    val message: String = ""
) {
    val needLogin: Boolean get() = code == ApiCode.NO_COOKIE
    val needVip: Boolean get() = code == ApiCode.NEED_VIP
}

/** 解析结果包装：成功携带数据，失败携带错误 */
sealed interface Result<out T> {
    data class Ok<T>(val value: T) : Result<T>
    data class Err(val error: ApiError) : Result<Nothing>
}
