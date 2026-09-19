package com.xixi.music.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xixi.music.AppContainer
import com.xixi.music.R
import com.xixi.music.ui.components.MiniPlayerBar
import com.xixi.music.ui.home.HomeScreen
import com.xixi.music.ui.login.LoginScreen
import com.xixi.music.ui.player.NowPlayingScreen

/**
 * 底部导航三个入口（仅三个，不做 Navigation Compose）。
 *
 * 图标说明：Home 用自绘 vector（material-icons-core 不含 MusicNote，
 * 而 material-icons-extended 被体积约束禁止引入）。
 */
private enum class HomeTab(val labelRes: Int, val icon: HomeTabIcon) {
    HOME(R.string.tab_home, HomeTabIcon.Drawable(R.drawable.ic_music_note)),
    LOGIN(R.string.tab_login, HomeTabIcon.Vector(Icons.Filled.Person)),
    PLAYER(R.string.tab_player, HomeTabIcon.Vector(Icons.Filled.PlayArrow))
}

/** 同时支持 vector drawable 与 ImageVector 的小封装 */
private sealed interface HomeTabIcon {
    data class Drawable(@androidx.annotation.DrawableRes val res: Int) : HomeTabIcon
    data class Vector(val image: ImageVector) : HomeTabIcon
}

/**
 * 应用根界面。
 *
 * Android 15 edge-to-edge：
 * - Scaffold 接收 contentWindowInsets = WindowInsets.safeDrawing；
 * - 底部迷你播放条 + NavigationBar 组合在 bottomBar 中，并由 MiniPlayerBar 自己做
 *   navigationBarsPadding，保证不被系统导航栏遮挡；
 * - 顶部搜索框在 HomeScreen 里使用 statusBarsPadding。
 */
@Composable
fun XixiRoot(
    container: AppContainer,
    onShowMessage: (String) -> Unit,
    viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory)
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.HOME.ordinal) }
    val currentTab = HomeTab.entries[tab]

    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 连接播放服务（幂等）
    LaunchedEffect(Unit) {
        viewModel.playerController.ensureConnected()
    }

    // UI 内部提示（网络错误、登录成功等）走 Snackbar
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                is UiMessage.Error -> message.text
                is UiMessage.NetworkError -> "网络请求失败：${message.detail}"
                UiMessage.VipNeedLogin -> "该歌曲需要 VIP，请先到「登录」扫码"
                UiMessage.LoginSucceeded -> "登录成功"
                UiMessage.LoggedOut -> "已退出登录"
                UiMessage.CookieSaved -> "Cookie 已保存"
            }
            onShowMessage(text)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // 自己处理内边距，避免 innerPadding 与组件级 statusBarsPadding 重复叠加
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            Column {
                MiniPlayerBar(
                    state = playerState,
                    onTogglePlay = { viewModel.togglePlayPause() },
                    onNext = { viewModel.next() },
                    onOpen = { tab = HomeTab.PLAYER.ordinal }
                )
                NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                    HomeTab.entries.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = {
                                when (val tabIcon = item.icon) {
                                    is HomeTabIcon.Drawable -> Icon(
                                        painter = painterResource(tabIcon.res),
                                        contentDescription = stringResource(item.labelRes)
                                    )

                                    is HomeTabIcon.Vector -> Icon(
                                        imageVector = tabIcon.image,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                }
                            },
                            label = { Text(text = stringResource(item.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        // 内容区使用 innerPadding（此处为 0），各屏幕自行处理状态栏内边距
        when (currentTab) {
            HomeTab.HOME -> HomeScreen(
                viewModel = viewModel,
                contentPadding = innerPadding,
                modifier = Modifier.fillMaxSize()
            )

            HomeTab.LOGIN -> LoginScreen(
                viewModel = viewModel,
                contentPadding = innerPadding,
                modifier = Modifier.fillMaxSize()
            )

            HomeTab.PLAYER -> NowPlayingScreen(
                viewModel = viewModel,
                contentPadding = innerPadding,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
