package com.szgenle.agentpost.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

    // --- 任务详情页回复草稿（按 taskId 分 key） ---
    // 空串 ≡ 无草稿。放 DataStore 不放 Room 因为草稿状态不跨设备同步，
    // 且边输边写 Room 成本偏高。
    private fun draftReplyKey(taskId: String) =
        stringPreferencesKey("draft_reply_$taskId")

    fun observeDraftReply(taskId: String): Flow<String> =
        store.data.map { it[draftReplyKey(taskId)].orEmpty() }

    suspend fun getDraftReply(taskId: String): String =
        observeDraftReply(taskId).first()

    suspend fun setDraftReply(taskId: String, text: String) {
        store.edit {
            if (text.isEmpty()) it.remove(draftReplyKey(taskId)) else it[draftReplyKey(taskId)] = text
        }
    }

    suspend fun clearDraftReply(taskId: String) {
        store.edit { it.remove(draftReplyKey(taskId)) }
    }

    // --- 新建任务草稿（全局唯一一份） ---
    fun observeNewTaskDraft(): Flow<NewTaskDraft> =
        store.data.map {
            NewTaskDraft(
                subject = it[NEW_TASK_DRAFT_SUBJECT_KEY].orEmpty(),
                body = it[NEW_TASK_DRAFT_BODY_KEY].orEmpty(),
            )
        }

    suspend fun getNewTaskDraft(): NewTaskDraft = observeNewTaskDraft().first()

    suspend fun setNewTaskDraft(subject: String, body: String) {
        store.edit {
            if (subject.isEmpty()) it.remove(NEW_TASK_DRAFT_SUBJECT_KEY)
            else it[NEW_TASK_DRAFT_SUBJECT_KEY] = subject
            if (body.isEmpty()) it.remove(NEW_TASK_DRAFT_BODY_KEY)
            else it[NEW_TASK_DRAFT_BODY_KEY] = body
        }
    }

    suspend fun clearNewTaskDraft() {
        store.edit {
            it.remove(NEW_TASK_DRAFT_SUBJECT_KEY)
            it.remove(NEW_TASK_DRAFT_BODY_KEY)
        }
    }

    // --- 崩溃上报偏好 ---
    // 三态：每次询问 / 自动发送 / 从不上报。默认每次询问，尊重用户知情权。
    fun observeCrashReportPref(): Flow<CrashReportPref> =
        store.data.map { prefs ->
            val raw = prefs[CRASH_REPORT_PREF_KEY]
            raw?.let { runCatching { CrashReportPref.valueOf(it) }.getOrNull() }
                ?: CrashReportPref.ASK_EACH_TIME
        }

    suspend fun getCrashReportPref(): CrashReportPref = observeCrashReportPref().first()

    suspend fun setCrashReportPref(pref: CrashReportPref) {
        store.edit { it[CRASH_REPORT_PREF_KEY] = pref.name }
    }

    // --- 实时推送（IMAP IDLE + Foreground Service）开关 ---
    // 默认 false：保持现有 30s 前台轮询 + 15min WorkManager 后台双轨。
    // 打开后：启动 PushSyncService 常驻、IDLE 长连接实时感知新邮件；
    //        为避免双拉，ForegroundSyncScheduler 的 30s 循环会被短路。
    fun observeRealtimePush(): Flow<Boolean> =
        store.data.map { it[REALTIME_PUSH_KEY] ?: false }

    suspend fun getRealtimePush(): Boolean = observeRealtimePush().first()

    suspend fun setRealtimePush(enabled: Boolean) {
        store.edit { it[REALTIME_PUSH_KEY] = enabled }
    }

    // 电池优化引导弹框的一次性标志：
    // 用户在首次把「实时通知」切到 ON 时，如尚未加入电池白名单会弹一次引导框；
    // 「去设置」或「不再提示」按钮都会写 true，避免反复打扰；「稍后」保持 false，
    // 下次再次开启时仍会弹出。
    fun observeRealtimeBatteryDialogShown(): Flow<Boolean> =
        store.data.map { it[REALTIME_BATTERY_DIALOG_SHOWN_KEY] ?: false }

    suspend fun getRealtimeBatteryDialogShown(): Boolean =
        observeRealtimeBatteryDialogShown().first()

    suspend fun setRealtimeBatteryDialogShown(shown: Boolean) {
        store.edit { it[REALTIME_BATTERY_DIALOG_SHOWN_KEY] = shown }
    }

    private companion object {
        val LANGUAGE_TAG_KEY = stringPreferencesKey("ui_language_tag")
        val FETCH_FG_SEC_KEY = intPreferencesKey("fetch_foreground_seconds")
        val FETCH_BG_MIN_KEY = intPreferencesKey("fetch_background_minutes")
        val NEW_TASK_DRAFT_SUBJECT_KEY = stringPreferencesKey("new_task_draft_subject")
        val NEW_TASK_DRAFT_BODY_KEY = stringPreferencesKey("new_task_draft_body")
        val CRASH_REPORT_PREF_KEY = stringPreferencesKey("crash_report_pref")
        val REALTIME_PUSH_KEY = booleanPreferencesKey("realtime_push_enabled")
        val REALTIME_BATTERY_DIALOG_SHOWN_KEY =
            booleanPreferencesKey("realtime_battery_dialog_shown")

        const val DEFAULT_FG_SEC = 60
        const val DEFAULT_BG_MIN = 15
    }
}

/** 拉取间隔配置快照。 */
data class FetchIntervals(
    val foregroundSeconds: Int,
    val backgroundMinutes: Int,
)

/** 新建任务草稿（subject 和 body 均为空时视为无草稿）。 */
data class NewTaskDraft(
    val subject: String,
    val body: String,
) {
    val isEmpty: Boolean get() = subject.isEmpty() && body.isEmpty()
}

/**
 * 崩溃上报偏好。
 *
 * - [ASK_EACH_TIME]：默认。检测到未处理的崩溃文件时，弹框询问用户
 * - [AUTO]：静默通过 SELF 邮箱给自己发送，发完删除
 * - [NEVER]：启动时直接删除崩溃文件，不上报
 */
enum class CrashReportPref { ASK_EACH_TIME, AUTO, NEVER }
