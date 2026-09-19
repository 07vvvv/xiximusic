package com.xixi.music.player

import androidx.annotation.StringRes
import com.xixi.music.R

/**
 * 播放模式：顺序 / 列表循环 / 单曲循环 / 随机。
 * UI 只展示这四种，不涉及任何音质选项。
 */
enum class PlaybackMode(
    val key: String,
    @StringRes val labelRes: Int
) {
    SEQUENCE("sequence", R.string.player_mode_sequence),
    LOOP_ALL("loop_all", R.string.player_mode_loop_all),
    LOOP_ONE("loop_one", R.string.player_mode_loop_one),
    SHUFFLE("shuffle", R.string.player_mode_shuffle);

    /** 下一个模式（点击按钮循环切换） */
    fun next(): PlaybackMode {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }

    companion object {
        fun fromKey(key: String?): PlaybackMode =
            entries.firstOrNull { it.key == key } ?: LOOP_ALL
    }
}
