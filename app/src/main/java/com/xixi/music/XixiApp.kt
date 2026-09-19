package com.xixi.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.xixi.music.data.local.Prefs
import com.xixi.music.data.local.SessionStore
import com.xixi.music.data.repository.MusicRepository
import com.xixi.music.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 不使用 Hilt/Dagger，手写一个极简 ServiceLocator（AppContainer），
 * 减少依赖与体积，也让启动更快。
 */
class XixiApp : Application(), ImageLoaderFactory {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // 预热 Cookie 内存副本，供 OkHttp 拦截器同步读取（不阻塞网络线程）
        container.appScope.launch { container.sessionStore.warmUp() }
    }

    /** Coil 全局配置：内存缓存 25%，禁用磁盘缓存（本应用不缓存任何媒体数据） */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(MEMORY_CACHE_PERCENT)
                .build()
        }
        .diskCache(null as DiskCache?)
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()

    companion object {
        /** 内存缓存上限：可用内存的 25% */
        private const val MEMORY_CACHE_PERCENT = 0.25
    }
}

/**
 * 极简依赖容器：DataStore / 网络 / 仓库 / 播放控制器。
 */
class AppContainer(app: Application) {

    /**
     * 应用级协程作用域：用于「即使 Service 被销毁也必须完成」的收尾写入，
     * 例如 onDestroy 时保存最后播放位置（用 Service 自己的 scope 会被立刻 cancel 掉）。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val sessionStore: SessionStore = SessionStore(app)

    val prefs: Prefs = Prefs(app)

    val repository: MusicRepository = MusicRepository(sessionStore)

    /** 全局唯一的播放控制器（内部通过 MediaController 连接 MusicService） */
    val playerController: PlayerController = PlayerController(app, repository, prefs)

    /** 退出前落盘最后播放位置（不保存音频，只存 mediaId 与进度） */
    fun saveLastPlayed(mediaId: String, positionMs: Long) {
        if (mediaId.isBlank()) return
        appScope.launch { prefs.saveLastPlayed(mediaId, positionMs) }
    }
}
