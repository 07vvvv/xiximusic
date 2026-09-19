package com.xixi.music.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.xixi.music.MainActivity
import com.xixi.music.R
import com.xixi.music.XixiApp
import com.xixi.music.data.local.Prefs
import com.xixi.music.data.model.Result
import com.xixi.music.data.model.SongUrl
import com.xixi.music.data.repository.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局唯一的播放服务（前台服务，foregroundServiceType=mediaPlayback）。
 *
 * 关键设计：
 * 1. 全应用只有一个 ExoPlayer 实例，在本 Service 中创建，UI 通过 MediaController 连接；
 * 2. 每次播放/切歌都实时调用 /song/urls 解析直链，绝不复用旧直链；
 * 3. 只预取下一首（直链 + 歌词），缓存 3 分钟，过期同步重取；
 * 4. 不启用任何 ExoPlayer Cache，不写音频文件，不下载；
 * 5. 播放失败自动跳到下一首（VIP 无法播放时同样处理）。
 */
@OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private lateinit var prefs: Prefs
    private lateinit var repository: MusicRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 定时落盘播放进度，避免频繁写 DataStore */
    private var lastProgressSavedAt = 0L

    /** 当前已解析直链及其本地过期时间 */
    private var currentUrl: SongUrl? = null
    private var currentUrlSongId: String = ""
    private var currentUrlExpireAt: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressTask: Runnable? = null

    /** 连续自动跳过计数：避免整条队列都不可播时无限刷 Toast */
    private var consecutiveSkips = 0

    private val appContainer get() = (application as XixiApp).container

    override fun onCreate() {
        super.onCreate()
        prefs = appContainer.prefs
        repository = appContainer.repository

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()

        exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
        exoPlayer.addListener(playerListener)
        player = exoPlayer

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            .build()

        // 恢复上次的播放模式
        scope.launch { applyPlaybackMode(prefs.playbackMode()) }

        startProgressTicker()
    }

    // ------------------------------------------------------------ 会话回调

    private val sessionCallback = object : MediaSession.Callback {

        /** 向 MediaController 声明可用的自定义命令 */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val available = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(PlaybackCommands.SET_MODE)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(available)
                .build()
        }

        /** 播放模式切换通过自定义命令下发（UI -> Service） */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == PlaybackCommands.ACTION_SET_MODE) {
                val mode = PlaybackMode.fromKey(args.getString(PlaybackCommands.KEY_MODE))
                applyPlaybackMode(mode)
                scope.launch { prefs.setPlaybackMode(mode) }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        /**
         * 播放列表入队：这里完成「直链解析 + 预取」。
         * ExoPlayer 拿到的是带直链的 MediaItem，切歌时无需再等网络。
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> =
            futureFromIo(onFailure = { emptyList() }) { resolveQueue(mediaItems) }

        /** 进程被系统回收后，用户点播放恢复上一次的歌曲 */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            futureFromIo(onFailure = { MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L) }) {
                val mediaId = prefs.lastMediaId()
                val position = prefs.lastPosition()
                if (mediaId.isBlank()) {
                    MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
                } else {
                    val item = MediaItem.Builder()
                        .setMediaId(mediaId)
                        .setUri(mediaId)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(getString(R.string.player_unknown_song))
                                .build()
                        )
                        .build()
                    val resolved = resolveQueue(mutableListOf(item))
                    MediaSession.MediaItemsWithStartPosition(resolved, 0, position)
                }
            }
    }

    /**
     * Media3 的回调要求同步返回 ListenableFuture。
     * 这里把挂起逻辑丢到 IO 线程池执行，完成后填充 future ——
     * 绝不阻塞主线程（Service 与 UI 同进程），避免 ANR。
     *
     * 直链解析失败时不抛异常（抛了会让 MediaController 侧直接崩），
     * 而是回调 [onFailure] 的兜底值，由 UI 提示「无法播放」并跳过。
     */
    private fun <T> futureFromIo(onFailure: () -> T, block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch {
            val value = try {
                withContext(Dispatchers.IO) { block() }
            } catch (pfe: PlaybackFailedException) {
                PlayerBus.postError(pfe.code, pfe.message)
                onFailure()
            } catch (t: Throwable) {
                PlayerBus.postError(
                    ERROR_NO_PLAYABLE,
                    t.message ?: getString(R.string.toast_play_failed)
                )
                onFailure()
            }
            future.set(value)
        }
        return future
    }

    /**
     * 解析队列直链。
     *
     * 低延迟策略（只解析前两首，避免一次打出几十个请求）：
     * - 第 1 首（当前歌）必须解析成功，否则抛给上层提示「无法播放」；
     * - 第 2 首（下一首）解析后写入 PrefetchCache（3 分钟有效），实现秒切；
     * - 其余歌曲先放占位 uri（xixi://song/<mid>）。它们真正被播放到之前，
     *   [prefetchNext] 会在上一首播放期间把下一首的直链解析好；
     *   若预取失败（例如刚好过期），则播放到它时触发 onPlayerError，
     *   由错误分支提示并自动跳过 —— 绝不复用旧直链。
     */
    private suspend fun resolveQueue(items: List<MediaItem>): List<MediaItem> {
        if (items.isEmpty()) return items

        val result = ArrayList<MediaItem>(items.size)

        for ((index, item) in items.withIndex()) {
            val songId = item.mediaId
            if (songId.isBlank()) {
                result += item
                continue
            }

            if (index > 1) {
                // 占位 uri（自定义 scheme），真正播放到它时会实时解析成直链
                result += item.buildUpon().setUri("xixi://song/$songId").build()
                continue
            }

            val cached = PrefetchCache.getUrl(songId)
            val outgoing = if (cached != null && cached.playable) Result.Ok(cached)
            else repository.resolveUrl(songId)

            when (outgoing) {
                is Result.Ok -> {
                    if (index == 0) {
                        currentUrl = outgoing.value
                        currentUrlSongId = songId
                        currentUrlExpireAt = SystemClock.elapsedRealtime() + PREPARED_TTL_MS
                    } else {
                        PrefetchCache.putUrl(songId, outgoing.value)
                    }
                    result += item.buildUpon().setUri(Uri.parse(outgoing.value.url)).build()
                    if (PrefetchCache.getLyric(songId) == null) {
                        val lyric = repository.lyric(songId)
                        if (lyric is Result.Ok) PrefetchCache.putLyricRaw(songId, lyric.value)
                    }
                }

                is Result.Err -> {
                    if (index == 0) {
                        throw PlaybackFailedException(outgoing.error.code, outgoing.error.message)
                    }
                    // 后面的歌解析失败直接跳过，不阻塞队列
                }
            }
        }

        if (result.isEmpty()) {
            throw PlaybackFailedException(ERROR_NO_PLAYABLE, getString(R.string.toast_play_failed))
        }

        PrefetchCache.markPrefetching(null)
        return result
    }

    /** 直链是否已过期（本地 2 分钟保守值 + 服务端 expiredAt 双重判断） */
    private fun isExpired(songId: String): Boolean {
        if (songId != currentUrlSongId) return true
        if (SystemClock.elapsedRealtime() > currentUrlExpireAt) return true
        val serverExpire = currentUrl?.expiredAt ?: 0L
        return serverExpire in 1..System.currentTimeMillis()
    }

    // ------------------------------------------------------------ 播放器监听

    private val playerListener = object : Player.Listener {

        /**
         * 换歌处理。
         *
         * 注意：单曲循环（REPEAT_MODE_ONE）时 ExoPlayer 也会回调本方法，
         * 因此这里用 currentUrlSongId 做「真的换歌了吗」的判断，避免每次循环
         * 都清缓存 + 重新预取（否则单曲循环会反复发起解析请求）。
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val songId = mediaItem?.mediaId.orEmpty()
            if (songId.isBlank() || songId == currentUrlSongId) return

            currentUrlSongId = songId
            currentUrlExpireAt = 0L
            consecutiveSkips = 0
            // 真正换歌：释放上一首的直链与歌词（内存里不保留历史，也不缓存音频）。
            // 顺序很重要：先 retainOnly（此时 inflight 仍会连带保留下一首的预取），
            // 再清空 inflight 标记，让 prefetchNext 重新预取新的下一首。
            PrefetchCache.retainOnly(songId)
            PrefetchCache.markPrefetching(null)
            // 通知栏与 UI 立即更新
            notifyStateChanged()
            prefetchNext()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                prefetchNext()
            }
            notifyStateChanged()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) consecutiveSkips = 0
            notifyStateChanged()
            if (isPlaying) saveProgress(force = true)
        }

        override fun onPlayerError(error: PlaybackException) {
            val exo = player ?: return
            val failedIndex = exo.currentMediaItemIndex
            val hasNext = failedIndex < exo.mediaItemCount - 1
            val songId = exo.currentMediaItem?.mediaId.orEmpty()
            if (songId.isNotBlank()) PrefetchCache.invalidate(songId)

            if (hasNext && consecutiveSkips < MAX_CONSECUTIVE_SKIPS) {
                // VIP 无法播放 / 直链失效：提示并自动跳过
                consecutiveSkips++
                PlayerBus.postError(error.errorCode, error.errorCodeName)
                exo.seekTo(failedIndex + 1, 0L)
                exo.prepare()
                exo.play()
            } else {
                // 整条队列都播不了：停下来并给出明确提示
                PlayerBus.postError(
                    error.errorCode,
                    getString(R.string.toast_play_failed)
                )
                notifyStateChanged()
            }
        }
    }

    /** 预取下一首（直链 + 歌词），内存里同时只保留 1 首 */
    private fun prefetchNext() {
        val exo = player ?: return
        val nextIndex = exo.currentMediaItemIndex + 1
        if (nextIndex < 0 || nextIndex >= exo.mediaItemCount) return
        val songId = exo.getMediaItemAt(nextIndex).mediaId
        if (songId.isBlank()) return
        if (PrefetchCache.getUrl(songId) != null && PrefetchCache.getLyric(songId) != null) return
        if (PrefetchCache.prefetchingSongId == songId) return

        PrefetchCache.markPrefetching(songId)
        scope.launch {
            try {
                if (PrefetchCache.getUrl(songId) == null) {
                    val url = repository.resolveUrl(songId)
                    if (url is Result.Ok) PrefetchCache.putUrl(songId, url.value)
                }
                if (PrefetchCache.getLyric(songId) == null) {
                    val lyric = repository.lyric(songId)
                    if (lyric is Result.Ok) PrefetchCache.putLyricRaw(songId, lyric.value)
                }
            } finally {
                PrefetchCache.markPrefetching(null)
            }
        }
    }

    /** 播放模式 -> ExoPlayer 的 repeatMode / shuffleModeEnabled */
    private fun applyPlaybackMode(mode: PlaybackMode) {
        val exo = player ?: return
        when (mode) {
            PlaybackMode.SEQUENCE -> {
                exo.shuffleModeEnabled = false
                exo.repeatMode = Player.REPEAT_MODE_OFF
            }

            PlaybackMode.LOOP_ALL -> {
                exo.shuffleModeEnabled = false
                exo.repeatMode = Player.REPEAT_MODE_ALL
            }

            PlaybackMode.LOOP_ONE -> {
                exo.shuffleModeEnabled = false
                exo.repeatMode = Player.REPEAT_MODE_ONE
            }

            PlaybackMode.SHUFFLE -> {
                exo.shuffleModeEnabled = true
                exo.repeatMode = Player.REPEAT_MODE_ALL
            }
        }
        PlayerBus.setMode(mode)
    }

    /** 通知栏与 UI 立即刷新 */
    private fun notifyStateChanged() {
        PlayerBus.poke()
    }

    /** 进度落盘（每 10 秒一次，避免频繁写磁盘） */
    private fun saveProgress(force: Boolean) {
        val exo = player ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastProgressSavedAt < PROGRESS_SAVE_INTERVAL_MS) return
        lastProgressSavedAt = now
        val mediaId = exo.currentMediaItem?.mediaId ?: return
        val position = exo.currentPosition
        // 走应用级作用域：Service 即将销毁时这一步也能写完
        appContainer.saveLastPlayed(mediaId, position)
    }

    private fun startProgressTicker() {
        val task = object : Runnable {
            override fun run() {
                saveProgress(force = false)
                mainHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS)
            }
        }
        progressTask = task
        mainHandler.postDelayed(task, PROGRESS_SAVE_INTERVAL_MS)
    }

    // ------------------------------------------------------------ 生命周期

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val exo = player
        // 从最近任务划掉：没在播放就正常停服，正在播放则由前台服务继续
        if (exo == null || !exo.playWhenReady || exo.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        progressTask?.let { mainHandler.removeCallbacks(it) }
        progressTask = null
        saveProgress(force = true)

        player?.removeListener(playerListener)
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null

        PrefetchCache.clear()
        repository.clearCaches()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** 本地保守有效期：2 分钟（服务端 expiredAt 更严格时以服务端为准） */
        private const val PREPARED_TTL_MS = 120_000L
        private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L

        /** 连续自动跳过的上限：超过就停止，避免整条队列都失效时无限跳过 */
        private const val MAX_CONSECUTIVE_SKIPS = 3

        const val ERROR_NO_PLAYABLE = 40400
    }
}

/** 直链解析失败时抛出，UI 侧据此提示并跳过 */
class PlaybackFailedException(val code: Int, override val message: String) : Exception(message)
