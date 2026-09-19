package com.xixi.music.player

import androidx.media3.session.SessionCommand

/**
 * UI -> Service 的自定义命令定义。
 */
object PlaybackCommands {

    /** 设置播放模式（顺序/列表循环/单曲循环/随机） */
    const val ACTION_SET_MODE = "com.xixi.music.SET_PLAYBACK_MODE"

    /** 参数 key：PlaybackMode.key */
    const val KEY_MODE = "mode"

    val SET_MODE: SessionCommand = SessionCommand(ACTION_SET_MODE, android.os.Bundle.EMPTY)

    /** 供 MediaController 发送命令时复用 */
    fun setModeCommand(): SessionCommand = SET_MODE
}
