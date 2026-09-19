package com.xixi.music.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Service 与 UI 同进程的轻量事件总线。
 *
 * 只传「播放模式」和「播放错误」这两种小对象，不缓存任何媒体数据。
 */
object PlayerBus {

    /** 当前播放模式，Service 是唯一写入方 */
    private val _mode = MutableStateFlow(PlaybackMode.LOOP_ALL)
    val mode: StateFlow<PlaybackMode> = _mode.asStateFlow()

    /** 播放错误事件（用于 Toast 提示 + 自动跳过） */
    private val _errors = MutableSharedFlow<PlaybackError>(extraBufferCapacity = 4)
    val errors: SharedFlow<PlaybackError> = _errors.asSharedFlow()

    /**
     * 状态变更脉冲：Service 里任何会影响 UI 的事件（换歌、播放/暂停、错误）都会 +1，
     * 让 UI 立刻重建状态，而不是等下一次 500ms 轮询。
     */
    private val _ticks = MutableStateFlow(0L)
    val ticks: StateFlow<Long> = _ticks.asStateFlow()

    fun setMode(value: PlaybackMode) {
        _mode.value = value
    }

    /** 通知 UI「状态变了」 */
    fun poke() {
        _ticks.value = _ticks.value + 1L
    }

    fun postError(code: Int, message: String) {
        if (code == 0 && message.isEmpty()) {
            clearError()
            return
        }
        _errors.tryEmit(PlaybackError(code, message))
    }

    fun clearError() {
        _errors.tryEmit(PlaybackError(0, ""))
    }
}

data class PlaybackError(
    val code: Int,
    val message: String
) {
    val isEmpty: Boolean get() = code == 0 && message.isEmpty()
}
