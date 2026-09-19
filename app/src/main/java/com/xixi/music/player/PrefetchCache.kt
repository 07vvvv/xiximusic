package com.xixi.music.player

import com.xixi.music.data.model.LyricData
import com.xixi.music.data.model.SongUrl
import com.xixi.music.util.LrcParser
import com.xixi.music.util.SmallCache

/**
 * 低延迟切歌的核心：预取下一首（只预取 1 首）。
 *
 * 缓存内容严格限定为：
 * - 直链字符串（SongUrl）
 * - 歌词文本（解析后的 Line 列表，解析完立刻丢掉原始 LRC）
 *
 * 绝不缓存音频文件，绝不落盘。
 * TTL = 3 分钟；过期或失效时同步重新请求新直链。
 */
object PrefetchCache {

    /** 预取存活时间：3 分钟 */
    const val TTL_MS = 3 * 60 * 1000L

    private val urlCache = SmallCache<String, SongUrl>(maxSize = 2, ttlMillis = TTL_MS)
    private val lyricCache = SmallCache<String, List<LrcParser.Line>>(maxSize = 2, ttlMillis = TTL_MS)

    /** 当前正在预取的歌曲 id，用于避免重复请求 */
    @Volatile
    var prefetchingSongId: String? = null
        private set

    fun markPrefetching(songId: String?) {
        prefetchingSongId = songId
    }

    fun putUrl(songId: String, url: SongUrl) {
        urlCache.put(songId, url, TTL_MS)
    }

    fun getUrl(songId: String): SongUrl? = urlCache.get(songId)

    fun getLyric(songId: String): List<LrcParser.Line>? = lyricCache.get(songId)

    /** 解析并放入歌词缓存（原始 LRC 文本随即被丢弃） */
    fun putLyricRaw(songId: String, lyric: LyricData) {
        val lines = LrcParser.parse(lyric.lyric, lyric.trans)
        lyricCache.put(songId, lines, TTL_MS)
    }

    /**
     * 只保留指定歌曲的缓存（以及正在预取的下一首），其余立即释放。
     * 传 null 表示全部清空。用于切歌时把内存占用压到最低。
     */
    fun retainOnly(songId: String?) {
        if (songId == null) {
            clear()
            return
        }
        val inflight = prefetchingSongId
        // 缓存容量只有 2，逐一代入判断，保证内存里最多只留当前与正在预取的那一首
        val keepUrl = urlCache.get(songId)
        val keepLyric = lyricCache.get(songId)
        val keepNextUrl = inflight?.takeIf { it != songId }?.let { urlCache.get(it) }
        val keepNextLyric = inflight?.takeIf { it != songId }?.let { lyricCache.get(it) }

        urlCache.clear()
        lyricCache.clear()

        if (keepUrl != null) urlCache.put(songId, keepUrl, TTL_MS)
        if (keepLyric != null) lyricCache.put(songId, keepLyric, TTL_MS)
        if (inflight != null && inflight != songId) {
            if (keepNextUrl != null) urlCache.put(inflight, keepNextUrl, TTL_MS)
            if (keepNextLyric != null) lyricCache.put(inflight, keepNextLyric, TTL_MS)
        }
    }

    fun invalidate(songId: String) {
        urlCache.remove(songId)
        lyricCache.remove(songId)
    }

    fun clear() {
        urlCache.clear()
        lyricCache.clear()
        prefetchingSongId = null
    }
}
