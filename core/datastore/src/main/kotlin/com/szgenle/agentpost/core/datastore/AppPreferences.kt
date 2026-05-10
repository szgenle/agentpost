package com.szgenle.agentpost.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
}
