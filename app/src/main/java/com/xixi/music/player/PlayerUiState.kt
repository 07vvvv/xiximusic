package com.xixi.music.player

/**
 * 播放页/迷你条所需的全部状态。
 * 由 PlayerController 维护，UI 只读。
 */
data class PlayerUiState(
    /** 是否有正在播放（或暂停中）的歌曲 */
    val hasSong: Boolean = false,
    /** 当前歌曲 id（QQ songmid） */
    val songId: String = "",
    val title: String = "",
    val artist: String = "",
    val cover: String = "",
    val isVip: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** 播放失败（用于自动跳过与提示） */
    val hasError: Boolean = false,
    val errorMessage: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** 队列进度 */
    val index: Int = 0,
    val queueSize: Int = 0,
    /** 当前实际使用的音质（界面不展示，仅内部/日志用途） */
    val quality: String = "",
    val lyricLines: List<com.xixi.music.util.LrcParser.Line> = emptyList(),
    val lyricLoading: Boolean = false,
    val playbackMode: PlaybackMode = PlaybackMode.LOOP_ALL
) {
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    /** 当前歌词行下标，-1 表示前奏 */
    val currentLyricIndex: Int
        get() = com.xixi.music.util.LrcParser.findIndex(lyricLines, positionMs)
}
