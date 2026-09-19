package com.xixi.music.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.xixi.music.player.PlaybackMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 轻量偏好：播放模式 + 上次播放位置（用于进程被杀后恢复）。
 * 不保存任何音频数据、搜索历史或播放历史。
 */
class Prefs(private val context: Context) {

    private val dataStore = context.xixiDataStore

    val playbackModeFlow: Flow<PlaybackMode> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            PlaybackMode.fromKey(prefs[KEY_PLAY_MODE])
        }

    val lastMediaIdFlow: Flow<String> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[KEY_LAST_MEDIA_ID].orEmpty() }

    val lastPositionFlow: Flow<Long> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[KEY_LAST_POSITION] ?: 0L }

    suspend fun playbackMode(): PlaybackMode =
        runCatching { playbackModeFlow.first() }.getOrDefault(PlaybackMode.LOOP_ALL)

    suspend fun setPlaybackMode(mode: PlaybackMode) {
        dataStore.edit { prefs -> prefs[KEY_PLAY_MODE] = mode.key }
    }

    suspend fun saveLastPlayed(mediaId: String, positionMs: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_MEDIA_ID] = mediaId
            prefs[KEY_LAST_POSITION] = positionMs
        }
    }

    suspend fun lastMediaId(): String =
        runCatching { lastMediaIdFlow.first() }.getOrDefault("")

    suspend fun lastPosition(): Long =
        runCatching { lastPositionFlow.first() }.getOrDefault(0L)

    private companion object {
        val KEY_PLAY_MODE = stringPreferencesKey("play_mode")
        val KEY_LAST_MEDIA_ID = stringPreferencesKey("last_media_id")
        val KEY_LAST_POSITION = longPreferencesKey("last_position")
    }
}
