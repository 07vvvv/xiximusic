package com.xixi.music.util

/**
 * LRC 歌词解析。
 *
 * 设计约束（内存优化）：
 * - 只解析当前歌曲，解析完成后立即丢弃原始文本；
 * - 不保留任何历史歌曲的歌词；
 * - 同时支持翻译行（trans），与原文按时间戳合并成一行显示。
 */
object LrcParser {

    /** 精确定位到毫秒的 LRC 标签：[mm:ss.xx] / [mm:ss.xxx] / [mm:ss] */
    private val TAG_REGEX = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    data class Line(
        val timeMs: Long,
        val text: String,
        val translation: String = ""
    )

    /**
     * 解析 LRC 文本。
     * @param lrc 原文
     * @param trans 翻译（可为空）
     */
    fun parse(lrc: String, trans: String = ""): List<Line> {
        if (lrc.isBlank() && trans.isBlank()) return emptyList()

        val translations = parseRaw(trans).associate { it.timeMs to it.text }
        val main = parseRaw(lrc)

        if (main.isEmpty() && translations.isEmpty()) return emptyList()

        val merged = ArrayList<Line>(main.size + 4)
        for (line in main) {
            merged += Line(
                timeMs = line.timeMs,
                text = line.text,
                translation = translations[line.timeMs].orEmpty()
            )
        }
        // 只有翻译没有原文的极端情况
        if (merged.isEmpty()) {
            translations.entries.sortedBy { it.key }.forEach { (t, text) ->
                merged += Line(timeMs = t, text = text)
            }
        }
        return merged.sortedBy { it.timeMs }
    }

    /** 仅解析出纯文本行（过滤元信息标签） */
    private fun parseRaw(raw: String): List<Line> {
        if (raw.isBlank()) return emptyList()
        val result = ArrayList<Line>(64)
        raw.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach
            // 跳过 [ti:] [ar:] [al:] [by:] [offset:] 等元信息
            if (META_REGEX.containsMatchIn(trimmed) && !TAG_REGEX.containsMatchIn(trimmed)) {
                return@forEach
            }
            val matches = TAG_REGEX.findAll(trimmed).toList()
            if (matches.isEmpty()) {
                // 无时间戳的行（部分接口会返回纯文本歌词），按顺序追加，时间 0
                val text = trimmed.trim()
                if (text.isNotEmpty()) result += Line(0L, text)
                return@forEach
            }
            val text = trimmed.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) return@forEach
            for (m in matches) {
                val minutes = m.groupValues[1].toLongOrNull() ?: 0L
                val seconds = m.groupValues[2].toLongOrNull() ?: 0L
                val fractionRaw = m.groupValues[3]
                val fractionMs = when {
                    fractionRaw.isEmpty() -> 0L
                    fractionRaw.length == 1 -> fractionRaw.toLong() * 100
                    fractionRaw.length == 2 -> fractionRaw.toLong() * 10
                    else -> fractionRaw.substring(0, 3).toLong()
                }
                result += Line(minutes * 60_000L + seconds * 1_000L + fractionMs, text)
            }
        }
        return result
    }

    private val META_REGEX = Regex("\\[(ti|ar|al|by|offset|re|ve|length):", RegexOption.IGNORE_CASE)

    /**
     * 二分查找当前时间对应的歌词行下标。
     * 返回 -1 表示还没到第一行。
     */
    fun findIndex(lines: List<Line>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.size - 1
        var ans = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return ans
    }
}
