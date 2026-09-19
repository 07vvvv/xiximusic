package com.xixi.music

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.xixi.music.player.PlaybackMessage
import com.xixi.music.ui.XixiRoot
import com.xixi.music.ui.theme.XixiMusicTheme
import kotlinx.coroutines.launch

/**
 * 唯一的 Activity。
 *
 * Android 15（API 35）适配：
 * - enableEdgeToEdge()：强制 edge-to-edge 下自行处理系统栏内边距；
 * - 界面层用 WindowInsets.safeDrawing + statusBarsPadding/navigationBarsPadding；
 * - 深色模式状态栏图标颜色由 XixiMusicTheme 动态设置；
 * - Manifest 中 windowSoftInputMode=adjustResize，键盘弹出时内容可滚动。
 */
class MainActivity : ComponentActivity() {

    /** 最近一次提示文案：保留字段便于排查，同时避免重复 Toast 同一条消息 */
    private var lastMessage: String = ""

    /** Android 13+ 通知运行时权限 */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                lastMessage = getString(R.string.toast_notification_denied)
                Toast.makeText(this, lastMessage, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前调用，保证 Android 15 上系统栏正确处理
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        setContent {
            XixiMusicTheme {
                XixiRoot(
                    container = (application as XixiApp).container,
                    onShowMessage = { text ->
                        lastMessage = text
                        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        observePlayerMessages()
        maybeAskBatteryOptimization()
    }

    override fun onResume() {
        super.onResume()
        // 回到前台检查一次通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) requestNotificationPermissionIfNeeded()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 播放错误 / 服务不可用 -> Toast 提示（VIP 无法播放时提示并自动跳过） */
    private fun observePlayerMessages() {
        val controller = (application as XixiApp).container.playerController
        lifecycleScope.launch {
            controller.messages.collect { message ->
                val text = when (message) {
                    is PlaybackMessage.PlayFailed -> getString(R.string.toast_play_failed)
                    PlaybackMessage.ServiceUnavailable -> getString(R.string.toast_network_error)
                }
                Toast.makeText(this@MainActivity, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Android 15 电池优化：后台播放容易被系统杀掉。
     * 首次进入时引导用户把 App 加入「不受限制」（不强制，可跳过）。
     */
    private fun maybeAskBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BATTERY_ASKED, false)) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean(KEY_BATTERY_ASKED, true).apply()
            return
        }
        prefs.edit().putBoolean(KEY_BATTERY_ASKED, true).apply()
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }.onFailure {
            // 部分 ROM 没有该页面，退回应用详情页设置
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "xixi_runtime"
        private const val KEY_BATTERY_ASKED = "battery_asked"
    }
}
