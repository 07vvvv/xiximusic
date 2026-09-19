package com.xixi.music.util

/**
 * 极简内存缓存：限制条目数与存活时间。
 *
 * 严守约束：这里只缓存「直链字符串」和「歌词文本」，
 * 绝不缓存 / 下载 / 落盘任何音频数据。
 */
class SmallCache<K, V>(
    private val maxSize: Int = 64,
    private val ttlMillis: Long = 60_000L
) {
    private data class Entry<V>(val value: V, val expireAt: Long)

    private val map = LinkedHashMap<K, Entry<V>>(maxSize, 0.75f, true)

    /** 读取未过期的值；过期自动移除 */
    @Synchronized
    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (entry.expireAt <= System.currentTimeMillis()) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V, ttl: Long = ttlMillis) {
        if (map.size >= maxSize) {
            val oldest = map.keys.firstOrNull()
            if (oldest != null) map.remove(oldest)
        }
        map[key] = Entry(value, System.currentTimeMillis() + ttl)
    }

    @Synchronized
    fun remove(key: K) {
        map.remove(key)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun size(): Int = map.size
}

/** 时间格式化：毫秒 -> mm:ss */
fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "00:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        val mins = minutes % 60
        String.format("%d:%02d:%02d", hours, mins, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
