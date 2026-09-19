package com.xixi.music.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** 应用级 DataStore：文件名 xixi_prefs.preferences_pb */
val Context.xixiDataStore: DataStore<Preferences> by preferencesDataStore(name = "xixi_prefs")

/**
 * 登录会话存储。
 *
 * 个人自用：Cookie 按要求明文存入 DataStore，不做加密。
 * 内存里额外保留一份 volatile 副本，供 OkHttp 拦截器同步读取（避免在拦截器里阻塞）。
 */
class SessionStore(private val context: Context) {

    private val dataStore = context.xixiDataStore

    @Volatile
    private var cachedCookie: String? = null

    @Volatile
    private var cookieLoaded: Boolean = false

    /** Cookie 变化流，UI 用它判断是否已登录 */
    val cookieFlow: Flow<String> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[KEY_COOKIE].orEmpty() }

    /** 登录后服务端返回的昵称（仅用于展示） */
    val nicknameFlow: Flow<String> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[KEY_NICKNAME].orEmpty() }

    val avatarFlow: Flow<String> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[KEY_AVATAR].orEmpty() }

    val isLoggedInFlow: Flow<Boolean> = cookieFlow.map { it.isNotBlank() }

    /** 内存副本，供 OkHttp 拦截器同步读取（绝不阻塞调度线程） */
    fun cachedCookieSync(): String = cachedCookie.orEmpty()

    suspend fun currentCookie(): String = runCatching { cookieFlow.first() }.getOrDefault("")

    suspend fun saveCookie(cookie: String) {
        cachedCookie = cookie
        cookieLoaded = true
        dataStore.edit { prefs -> prefs[KEY_COOKIE] = cookie }
    }

    suspend fun saveProfile(nickname: String, avatar: String) {
        dataStore.edit { prefs ->
            prefs[KEY_NICKNAME] = nickname
            prefs[KEY_AVATAR] = avatar
        }
    }

    /** 预热内存副本，App 启动时调用一次 */
    suspend fun warmUp() {
        val value = currentCookie()
        cachedCookie = value
        cookieLoaded = true
    }

    /** 退出登录：清除 Cookie 与资料 */
    suspend fun clear() {
        cachedCookie = ""
        cookieLoaded = true
        dataStore.edit { prefs ->
            prefs.remove(KEY_COOKIE)
            prefs.remove(KEY_NICKNAME)
            prefs.remove(KEY_AVATAR)
        }
    }

    private companion object {
        val KEY_COOKIE = stringPreferencesKey("qq_cookie")
        val KEY_NICKNAME = stringPreferencesKey("qq_nickname")
        val KEY_AVATAR = stringPreferencesKey("qq_avatar")
    }
}
