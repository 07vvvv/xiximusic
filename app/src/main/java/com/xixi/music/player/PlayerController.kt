package com.xixi.music.player

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.xixi.music.data.model.Result
import com.xixi.music.data.model.Song
import com.xixi.music.data.local.Prefs
import com.xixi.music.data.repository.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * UI 与播放服务的桥梁。
 *
 * - 全局只有一个 ExoPlayer（在 MusicService 里），这里只持有 MediaController；
 * - 队列跟随用户点击的那个列表（playQueue 一次性替换整条队列）；
 * - 位置轮询 250ms（播放中）/ 800ms（暂停），供进度条与歌词高亮使用；
 * - 换歌时立即刷新通知栏与 UI，并同步预取下一首。
 */
class PlayerController(
    private val context: Context,
    private val repository: MusicRepository,
    private val prefs: Prefs
) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controllerFuture: ListenableFuture<MediaController>? = null

    @Volatile
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** 当前队列（用于 UI 展示「第 x/y 首」与队列来源列表） */
    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _messages = MutableSharedFlow<PlaybackMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<PlaybackMessage> = _messages.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** 当前展示的歌曲 id，用于跳过重复的歌词加载 */
    private var lyricSongId: String = ""
    private var lastSaveAt = 0L

    private var tickerJob: Job? = null
    private var errorJob: Job? = null

    init {
        connect()
    }

    // ------------------------------------------------------------ 连接服务

    private fun connect() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(
            context,
            ComponentName(context, MusicService::class.java)
        )
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val c = runCatching { future.get() }.getOrNull()
                if (c == null) {
                    _connected.value = false
                    _messages.tryEmit(PlaybackMessage.ServiceUnavailable)
                    return@addListener
                }
                controller = c
                c.addListener(playerListener)
                _connected.value = true
                // 连上后同步一次队列与状态
                syncQueueFromController(c)
                startTicker()
                startErrorCollector()
            },
            MoreExecutors.directExecutor()
        )
    }

    /** App 回到前台时确保连接仍然有效 */
    fun ensureConnected() {
        if (controller == null) {
            controllerFuture = null
            connect()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            emitState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncQueueFromController(controller)
            emitState()
        }
    }

    /**
     * 状态刷新循环：
     * - 位置轮询（播放中 250ms / 暂停 800ms）驱动进度条与歌词高亮；
     * - 同时监听 PlayerBus.ticks，Service 侧一有事件（换歌/播放暂停/错误）立即刷新，
     *   保证通知栏与 UI 同步更新，而不是等下一次轮询。
     */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            launch {
                PlayerBus.ticks.collect {
                    emitState()
                }
            }
            while (isActive) {
                emitState()
                val playing = controller?.isPlaying == true
                delay(if (playing) POSITION_TICK_MS else POSITION_TICK_IDLE_MS)
            }
        }
    }

    private fun startErrorCollector() {
        errorJob?.cancel()
        errorJob = scope.launch {
            PlayerBus.errors.collect { error ->
                if (!error.isEmpty) {
                    _messages.tryEmit(PlaybackMessage.PlayFailed(error.message))
                }
            }
        }
    }

    /** 释放时一并结束歌词等内部协程 */
    private fun cancelAllJobs() {
        tickerJob?.cancel()
        errorJob?.cancel()
    }

    // ------------------------------------------------------------ 播放控制

    /**
     * 用「用户点击的那个列表」替换整条播放队列，并从 startIndex 开始播放。
     * 队列规则：从哪个列表点击，队列就是该列表。
     */
    fun playQueue(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        val index = startIndex.coerceIn(0, songs.size - 1)
        _queue.value = songs

        val target = songs[index]
        // 先乐观更新标题/封面，避免切歌瞬间 UI 还是旧歌
        _state.value = _state.value.copy(
            hasSong = true,
            songId = target.songId,
            title = target.name,
            artist = target.displaySinger,
            cover = target.cover,
            isVip = target.vip,
            isBuffering = true,
            hasError = false,
            errorMessage = "",
            positionMs = 0L,
            durationMs = target.durationSeconds * 1000L,
            index = index,
            queueSize = songs.size,
            lyricLines = emptyList(),
            lyricLoading = true
        )
        lyricSongId = target.songId

        val c = controller ?: run {
            // 服务还没连上：连上后再播
            controllerFuture?.let { future ->
                future.addListener(
                    {
                        runCatching { future.get() }.getOrNull()?.let { ctl ->
                            applyQueue(ctl, songs, index)
                        }
                    },
                    MoreExecutors.directExecutor()
                )
            }
            return
        }
        applyQueue(c, songs, index)
    }

    private fun applyQueue(c: MediaController, songs: List<Song>, index: Int) {
        val items = songs.map { it.toMediaItem() }
        c.setMediaItems(items, index, 0L)
        c.prepare()
        c.play()
        // 首次播放/切歌都实时解析直链（不复用旧直链）
        PrefetchCache.invalidate(songs[index].songId)
        loadLyricFor(songs.getOrNull(index)?.songId.orEmpty())
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun play() {
        controller?.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun next() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
        } else {
            c.seekTo(0, 0L)
        }
        c.play()
    }

    fun previous() {
        val c = controller ?: return
        // 播放超过 3 秒先回到开头，符合常见音乐 App 习惯
        if (c.currentPosition > 3_000L) {
            c.seekTo(0L)
        } else if (c.hasPreviousMediaItem()) {
            c.seekToPreviousMediaItem()
        } else {
            c.seekTo(0L)
        }
    }

    /** 拖动进度条完成后 seek */
    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        val duration = c.duration.takeIf { it > 0 } ?: _state.value.durationMs
        c.seekTo(positionMs.coerceIn(0L, if (duration > 0) duration else positionMs))
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    /** 切换播放模式（顺序/列表循环/单曲循环/随机），由 Service 统一落地 */
    fun cyclePlaybackMode() {
        val mode = PlayerBus.mode.value.next()
        setPlaybackMode(mode)
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        val c = controller
        if (c == null) {
            // 服务未连接：至少本地先反映
            PlayerBus.setMode(mode)
            _state.value = _state.value.copy(playbackMode = mode)
            return
        }
        val args = android.os.Bundle().apply {
            putString(PlaybackCommands.KEY_MODE, mode.key)
        }
        c.sendCustomCommand(PlaybackCommands.setModeCommand(), args)
        _state.value = _state.value.copy(playbackMode = mode)
    }

    /** 跳到队列中的某一首（用于队列列表点击） */
    fun playQueueIndex(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0L)
            c.play()
        }
    }

    // ------------------------------------------------------------ 状态组装

    private fun syncQueueFromController(c: MediaController?) {
        // 队列以 UI 侧记录为准；这里只在控制器有内容而 UI 为空时兜底
        if (c == null) return
        if (_queue.value.isEmpty() && c.mediaItemCount > 0) {
            val restored = (0 until c.mediaItemCount).mapNotNull { i ->
                val item = c.getMediaItemAt(i)
                item.toSongOrNull()
            }
            if (restored.isNotEmpty()) _queue.value = restored
        }
    }

    private fun emitState() {
        val c = controller
        if (c == null) {
            _state.value = _state.value.copy(
                playbackMode = PlayerBus.mode.value,
                isPlaying = false,
                isBuffering = false
            )
            return
        }

        val metadata = c.mediaMetadata
        val mediaId = c.currentMediaItem?.mediaId.orEmpty()
        val songId = mediaId.ifBlank { _state.value.songId }
        val duration = c.duration.takeIf { it > 0 }
            ?: metadata.durationMs
            ?: _state.value.durationMs

        val position = c.currentPosition.coerceAtLeast(0L)

        // 换歌时重置歌词
        if (songId.isNotBlank() && songId != lyricSongId) {
            lyricSongId = songId
            loadLyricFor(songId)
        }

        val buffering = c.playbackState == Player.STATE_BUFFERING ||
            c.playbackState == Player.STATE_IDLE

        _state.value = PlayerUiState(
            hasSong = songId.isNotBlank() && c.mediaItemCount > 0,
            songId = songId,
            title = metadata.title?.toString().orEmpty().ifBlank { _state.value.title },
            artist = metadata.artist?.toString().orEmpty().ifBlank { _state.value.artist },
            cover = metadata.artworkUri?.toString().orEmpty().ifBlank { _state.value.cover },
            isVip = _state.value.isVip,
            isPlaying = c.isPlaying,
            isBuffering = buffering && c.playWhenReady,
            hasError = false,
            errorMessage = "",
            positionMs = position,
            durationMs = duration,
            index = c.currentMediaItemIndex.coerceAtLeast(0),
            queueSize = c.mediaItemCount,
            quality = PrefetchCache.getUrl(songId)?.quality.orEmpty(),
            lyricLines = PrefetchCache.getLyric(songId).orEmpty(),
            lyricLoading = _state.value.lyricLoading && PrefetchCache.getLyric(songId) == null,
            playbackMode = PlayerBus.mode.value
        )

        // 每 10 秒左右落盘一次播放进度，用于进程被系统回收后恢复（不保存音频）
        val now = SystemClock.elapsedRealtime()
        if (songId.isNotBlank() && now - lastSaveAt > PROGRESS_SAVE_INTERVAL_MS) {
            lastSaveAt = now
            scope.launch { prefs.saveLastPlayed(songId, position) }
        }
    }

    /** 加载当前歌曲歌词（内存里只保留当前这一首） */
    private fun loadLyricFor(songId: String) {
        if (songId.isBlank()) return
        PrefetchCache.getLyric(songId)?.let {
            _state.value = _state.value.copy(lyricLines = it, lyricLoading = false)
            return
        }
        _state.value = _state.value.copy(lyricLoading = true, lyricLines = emptyList())
        scope.launch {
            val result = repository.lyric(songId)
            if (result is Result.Ok) {
                PrefetchCache.putLyricRaw(songId, result.value)
                if (lyricSongId == songId) {
                    _state.value = _state.value.copy(
                        lyricLines = PrefetchCache.getLyric(songId).orEmpty(),
                        lyricLoading = false
                    )
                }
            } else {
                if (lyricSongId == songId) {
                    _state.value = _state.value.copy(lyricLoading = false, lyricLines = emptyList())
                }
            }
        }
    }

    // ------------------------------------------------------------ 关闭

    fun release() {
        cancelAllJobs()
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        _connected.value = false
    }

    override fun close() = release()

    companion object {
        private const val POSITION_TICK_MS = 250L
        private const val POSITION_TICK_IDLE_MS = 800L
        private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L
    }
}

/** UI 侧提示消息 */
sealed interface PlaybackMessage {
    data class PlayFailed(val detail: String) : PlaybackMessage
    data object ServiceUnavailable : PlaybackMessage
}

/** Song -> MediaItem，metadata 直接给通知栏用。
 *
 * uri 使用自定义 scheme 占位：真正的音频直链由 MusicService 在 onAddMediaItems
 * 里实时解析后填入，客户端不会直接用 songId 当播放地址。
 */
fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(songId)
    .setUri("xixi://song/$songId")
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(name)
            .setArtist(displaySinger)
            .setAlbumTitle(album)
            .setArtworkUri(cover.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
    )
    .build()

/** MediaItem -> Song 兜底还原（进程恢复时队列信息不全，只能还原基础信息） */
fun MediaItem.toSongOrNull(): Song? {
    val id = mediaId
    if (id.isBlank()) return null
    val meta = mediaMetadata
    return Song(
        id = id,
        mid = id,
        name = meta.title?.toString().orEmpty(),
        singer = meta.artist?.toString().orEmpty(),
        album = meta.albumTitle?.toString().orEmpty(),
        cover = meta.artworkUri?.toString().orEmpty(),
        duration = ((meta.durationMs ?: 0L) / 1000L).toInt()
    )
}
