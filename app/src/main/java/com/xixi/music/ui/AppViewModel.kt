package com.xixi.music.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xixi.music.XixiApp
import com.xixi.music.data.model.QrStatus
import com.xixi.music.data.model.Result
import com.xixi.music.data.model.Song
import com.xixi.music.data.model.UserInfo
import com.xixi.music.data.repository.MusicRepository
import com.xixi.music.player.PlaybackMode
import com.xixi.music.player.PlayerController
import com.xixi.music.player.PlayerUiState
import com.xixi.music.data.local.Prefs
import com.xixi.music.data.local.SessionStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 全局唯一的 ViewModel（不做 Navigation，三个板块用 when 切换）。
 */
class AppViewModel(
    private val repository: MusicRepository,
    private val sessionStore: SessionStore,
    private val prefs: Prefs,
    val playerController: PlayerController
) : ViewModel() {

    // ------------------------------------------------------------ 首页状态

    private val _recommend = MutableStateFlow<List<Song>>(emptyList())
    val recommend: StateFlow<List<Song>> = _recommend.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Song>>(emptyList())
    val searchResults: StateFlow<List<Song>> = _searchResults.asStateFlow()

    private val _searchLoading = MutableStateFlow(false)
    val searchLoading: StateFlow<Boolean> = _searchLoading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _homeError = MutableStateFlow("")
    val homeError: StateFlow<String> = _homeError.asStateFlow()

    /** true 表示当前展示的是搜索结果，false 展示推荐 */
    val searching: StateFlow<Boolean> = _query
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var searchPage = 1
    private var searchJob: Job? = null

    // ------------------------------------------------------------ 登录状态

    private val _qrImage = MutableStateFlow("")
    val qrImage: StateFlow<String> = _qrImage.asStateFlow()

    private val _qrStatus = MutableStateFlow(QrStatus.WAITING)
    val qrStatus: StateFlow<String> = _qrStatus.asStateFlow()

    private val _qrLoading = MutableStateFlow(false)
    val qrLoading: StateFlow<Boolean> = _qrLoading.asStateFlow()

    private val _userInfo = MutableStateFlow<UserInfo?>(null)
    val userInfo: StateFlow<UserInfo?> = _userInfo.asStateFlow()

    val isLoggedIn: StateFlow<Boolean> = sessionStore.isLoggedInFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val nickname: StateFlow<String> = sessionStore.nicknameFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val avatar: StateFlow<String> = sessionStore.avatarFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private var qrIdentifier: String = ""
    private var pollJob: Job? = null

    // ------------------------------------------------------------ 通用提示

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    /** 播放页状态直通 UI */
    val playerState: StateFlow<PlayerUiState> = playerController.state

    private val _playbackMode = MutableStateFlow(PlaybackMode.LOOP_ALL)

    val playbackMode: StateFlow<PlaybackMode> = _playbackMode.asStateFlow()

    init {
        loadRecommend()
        refreshUserInfoIfLoggedIn()
        viewModelScope.launch {
            // 同步播放模式到 UI
            prefs.playbackModeFlow.collect { _playbackMode.value = it }
        }
    }

    // ------------------------------------------------------------ 首页逻辑

    /** 首页推荐（仅加载一次，之后手动下拉/重试） */
    fun loadRecommend(force: Boolean = false) {
        if (!force && _recommend.value.isNotEmpty()) return
        viewModelScope.launch {
            _homeError.value = ""
            when (val result = repository.recommend()) {
                is Result.Ok -> _recommend.value = result.value
                is Result.Err -> _homeError.value = result.error.message
            }
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
        if (value.isBlank()) {
            searchJob?.cancel()
            _searchResults.value = emptyList()
            _hasMore.value = false
            _searchLoading.value = false
            searchPage = 1
            return
        }
        scheduleSearch(value)
    }

    /** 防抖 500ms，避免每个字符都打一次接口 */
    private fun scheduleSearch(value: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            searchPage = 1
            _searchLoading.value = true
            when (val result = repository.search(value.trim(), 1, MusicRepository.PAGE_SIZE)) {
                is Result.Ok -> {
                    _searchResults.value = result.value.list
                    _hasMore.value = result.value.hasMore
                    _homeError.value = ""
                }

                is Result.Err -> {
                    _searchResults.value = emptyList()
                    _hasMore.value = false
                    _homeError.value = result.error.message
                }
            }
            _searchLoading.value = false
        }
    }

    /** 加载下一页（每页 20 条） */
    fun loadMore() {
        val keyword = _query.value.trim()
        if (keyword.isBlank() || _loadingMore.value || !_hasMore.value) return
        viewModelScope.launch {
            _loadingMore.value = true
            val next = searchPage + 1
            when (val result = repository.search(keyword, next, MusicRepository.PAGE_SIZE)) {
                is Result.Ok -> {
                    val existing = _searchResults.value.map { it.songId }.toSet()
                    val appended = result.value.list.filter { it.songId !in existing }
                    _searchResults.value = _searchResults.value + appended
                    _hasMore.value = result.value.hasMore
                    searchPage = next
                }

                is Result.Err -> _messages.tryEmit(UiMessage.NetworkError(result.error.message))
            }
            _loadingMore.value = false
        }
    }

    /**
     * 点击歌曲：队列就是「点击时所在的那个列表」。
     * 传 nil 表示关闭搜索（回到推荐）。
     */
    fun playFromList(list: List<Song>, index: Int) {
        if (list.isEmpty()) return
        val song = list.getOrNull(index) ?: return
        if (song.songId.isBlank()) {
            _messages.tryEmit(UiMessage.Error("歌曲信息不完整，无法播放"))
            return
        }
        playerController.ensureConnected()
        playerController.playQueue(list, index)
        // 未登录 + VIP 歌曲：提示但仍尝试播放（服务端可能返回试听片段）
        if (song.vip && !isLoggedIn.value) {
            _messages.tryEmit(UiMessage.VipNeedLogin)
        }
    }

    fun retryHome() {
        if (_query.value.isBlank()) {
            loadRecommend(force = true)
        } else {
            val q = _query.value
            _query.value = ""
            onQueryChange(q)
        }
    }

    // ------------------------------------------------------------ 登录逻辑

    /** 请求二维码并开始每 2 秒轮询 */
    fun startQrLogin() {
        pollJob?.cancel()
        viewModelScope.launch {
            _qrLoading.value = true
            _qrImage.value = ""
            _qrStatus.value = QrStatus.WAITING
            when (val result = repository.qrCreate()) {
                is Result.Ok -> {
                    qrIdentifier = result.value.identifier
                    _qrImage.value = result.value.qrImage
                    _qrStatus.value = QrStatus.WAITING
                    startPolling()
                }

                is Result.Err -> {
                    _qrImage.value = ""
                    _messages.tryEmit(UiMessage.NetworkError(result.error.message))
                }
            }
            _qrLoading.value = false
        }
    }

    /** 每 2 秒轮询一次扫码状态 */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var attempts = 0
            while (attempts < MAX_POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                attempts++
                val identifier = qrIdentifier
                if (identifier.isBlank()) return@launch
                when (val result = repository.qrCheck(identifier)) {
                    is Result.Ok -> {
                        val status = result.value.status
                        _qrStatus.value = status
                        when (status) {
                            QrStatus.CONFIRMED -> {
                                val cookie = result.value.cookie.orEmpty()
                                if (cookie.isNotBlank()) {
                                    sessionStore.saveCookie(cookie)
                                    sessionStore.saveProfile(
                                        result.value.nickname.orEmpty(),
                                        result.value.avatar.orEmpty()
                                    )
                                    repository.clearCaches()
                                    _messages.tryEmit(UiMessage.LoginSucceeded)
                                    refreshUserInfo()
                                } else {
                                    _messages.tryEmit(UiMessage.Error("登录成功但未拿到 Cookie，请重试"))
                                }
                                return@launch
                            }

                            QrStatus.EXPIRED, QrStatus.REFUSED -> return@launch
                            QrStatus.SCANNED, QrStatus.WAITING -> Unit
                        }
                    }

                    is Result.Err -> {
                        // 轮询期间的网络抖动不打断流程，继续轮询
                        if (result.error.code == MusicRepository.ERR_NETWORK && attempts < MAX_POLL_ATTEMPTS) {
                            continue
                        }
                        _messages.tryEmit(UiMessage.NetworkError(result.error.message))
                        return@launch
                    }
                }
            }
            _qrStatus.value = QrStatus.EXPIRED
        }
    }

    /** 手动粘贴 Cookie 备用方案 */
    fun saveManualCookie(cookie: String) {
        val value = cookie.trim()
        if (value.isBlank()) {
            _messages.tryEmit(UiMessage.Error("Cookie 不能为空"))
            return
        }
        viewModelScope.launch {
            sessionStore.saveCookie(value)
            repository.clearCaches()
            _messages.tryEmit(UiMessage.CookieSaved)
            refreshUserInfo()
        }
    }

    fun refreshUserInfo() {
        viewModelScope.launch {
            when (val result = repository.userInfo()) {
                is Result.Ok -> {
                    _userInfo.value = result.value
                    sessionStore.saveProfile(result.value.nickname, result.value.avatar)
                }

                is Result.Err -> {
                    if (result.error.needLogin) {
                        _userInfo.value = null
                    }
                }
            }
        }
    }

    /** 退出登录：清除本地 Cookie 并通知服务端 */
    fun logout() {
        pollJob?.cancel()
        viewModelScope.launch {
            repository.logoutRemote()
            sessionStore.clear()
            repository.clearCaches()
            _userInfo.value = null
            _qrImage.value = ""
            _qrStatus.value = QrStatus.WAITING
            qrIdentifier = ""
            _messages.tryEmit(UiMessage.LoggedOut)
        }
    }

    private fun refreshUserInfoIfLoggedIn() {
        viewModelScope.launch {
            if (isLoggedIn.value) refreshUserInfo()
        }
    }

    // ------------------------------------------------------------ 播放控制转发

    fun togglePlayPause() = playerController.togglePlayPause()

    fun next() = playerController.next()

    fun previous() = playerController.previous()

    fun seekTo(positionMs: Long) = playerController.seekTo(positionMs)

    fun cyclePlaybackMode() {
        playerController.cyclePlaybackMode()
        _playbackMode.value = com.xixi.music.player.PlayerBus.mode.value
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        playerController.setPlaybackMode(mode)
        _playbackMode.value = mode
    }

    override fun onCleared() {
        pollJob?.cancel()
        searchJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 500L
        private const val POLL_INTERVAL_MS = 2_000L
        private const val MAX_POLL_ATTEMPTS = 60

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as XixiApp
                AppViewModel(
                    repository = app.container.repository,
                    sessionStore = app.container.sessionStore,
                    prefs = app.container.prefs,
                    playerController = app.container.playerController
                )
            }
        }
    }
}

/** UI 提示消息 */
sealed interface UiMessage {
    data class Error(val text: String) : UiMessage
    data class NetworkError(val detail: String) : UiMessage
    data object VipNeedLogin : UiMessage
    data object LoginSucceeded : UiMessage
    data object LoggedOut : UiMessage
    data object CookieSaved : UiMessage
}
