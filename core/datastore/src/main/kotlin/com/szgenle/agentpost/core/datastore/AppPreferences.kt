package com.szgenle.agentpost.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 应用级非敏感偏好。
 *
 * MVP 阶段只记录：每个 Account 上一次 IMAP 拉取到的最大 UID，
 * 用于增量拉新邮件。
 *
 * 敏感信息（密码 / Token）**不要**放这里，用 [com.szgenle.agentpost.core.common.security.CredentialsVault]。
 */
private val Context.dataStore: DataStore<Preferences>
    by preferencesDataStore(name = "agentpost_prefs")

class AppPreferences(context: Context) {

    private val store = context.applicationContext.dataStore

    private fun lastSyncUidKey(accountId: String) =
        longPreferencesKey("last_sync_uid_$accountId")

    fun observeLastSyncUid(accountId: String): Flow<Long> =
        store.data.map { it[lastSyncUidKey(accountId)] ?: 0L }

    suspend fun getLastSyncUid(accountId: String): Long =
        store.data.map { it[lastSyncUidKey(accountId)] ?: 0L }.first()

    suspend fun setLastSyncUid(accountId: String, uid: Long) {
        store.edit { it[lastSyncUidKey(accountId)] = uid }
    }

    // --- 语言偏好 ---
    // 值为 BCP-47 tag（如 "zh-CN" / "en"）；null 表示用户从未选过，
    // Application 会在首次启动时按系统语言自动写入一个初始值。
    fun observeLanguageTag(): Flow<String?> =
        store.data.map { it[LANGUAGE_TAG_KEY] }

    suspend fun getLanguageTag(): String? =
        store.data.map { it[LANGUAGE_TAG_KEY] }.first()

    suspend fun setLanguageTag(tag: String) {
        store.edit { it[LANGUAGE_TAG_KEY] = tag }
    }

    // --- 收件拉取间隔（仅 UI 持久化，本期不接引擎） ---
    // 前台：秒；后台：分钟。默认值与 UI 下限一致，避免用户填出被邮服限流的值。
    fun observeFetchIntervals(): Flow<FetchIntervals> =
        store.data.map {
            FetchIntervals(
                foregroundSeconds = it[FETCH_FG_SEC_KEY] ?: DEFAULT_FG_SEC,
                backgroundMinutes = it[FETCH_BG_MIN_KEY] ?: DEFAULT_BG_MIN,
            )
        }

    suspend fun getFetchIntervals(): FetchIntervals =
        observeFetchIntervals().first()

    suspend fun setFetchIntervals(foregroundSeconds: Int, backgroundMinutes: Int) {
        store.edit {
            it[FETCH_FG_SEC_KEY] = foregroundSeconds
            it[FETCH_BG_MIN_KEY] = backgroundMinutes
        }
    }

    private companion object {
        val LANGUAGE_TAG_KEY = stringPreferencesKey("ui_language_tag")
        val FETCH_FG_SEC_KEY = intPreferencesKey("fetch_foreground_seconds")
        val FETCH_BG_MIN_KEY = intPreferencesKey("fetch_background_minutes")

        const val DEFAULT_FG_SEC = 60
        const val DEFAULT_BG_MIN = 15
    }
}

/** 拉取间隔配置快照。 */
data class FetchIntervals(
    val foregroundSeconds: Int,
    val backgroundMinutes: Int,
)
