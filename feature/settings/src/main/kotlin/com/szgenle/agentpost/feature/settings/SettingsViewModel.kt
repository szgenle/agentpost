package com.szgenle.agentpost.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.core.data.MailRepository
import com.szgenle.agentpost.core.datastore.AppPreferences
import com.szgenle.agentpost.core.datastore.CrashReportPref
import com.szgenle.agentpost.core.datastore.FetchIntervals
import com.szgenle.agentpost.core.model.Account
import com.szgenle.agentpost.core.ui.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * 主设置页状态。message 使用 UiText，让 ViewModel 不直接持有本地化字符串。
 *
 * 列表化后本 VM 只负责：
 * - 身份两项（SELF / AGENT）的名称+邮箱编辑（不触碰 IMAP/SMTP/密码）
 * - 语言切换
 * - 收件间隔展示数据（实际编辑在 FetchIntervalViewModel）
 */
data class SettingsUiState(
    val self: Account? = null,
    val agent: Account? = null,
    val message: UiText? = null,
    val busy: Boolean = false,
    /** 当前应用语言 tag："zh-CN" / "en"。null 表示尚未加载完 DataStore。 */
    val languageTag: String? = null,
    val fetchIntervals: FetchIntervals = FetchIntervals(60, 15),
    /** 崩溃上报偏好。默认询问，与 [AppPreferences] 默认一致。 */
    val crashReportPref: CrashReportPref = CrashReportPref.ASK_EACH_TIME,
    /** 加密 zip 附件主密码是否已设置（仅显示状态，不回显明文）。 */
    val hasZipPassword: Boolean = false,
    /** 实时推送开关。默认 false；开启时拉起 PushSyncService。 */
    val realtimePush: Boolean = false,
    /** 电池优化引导弹框是否已演示过。成员函数命名与 DataStore key 保持一致。 */
    val realtimeBatteryDialogShown: Boolean = false,
)

class SettingsViewModel(
    private val repo: MailRepository,
    private val prefs: AppPreferences,
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState(hasZipPassword = repo.hasZipPassword()))

    val uiState: StateFlow<SettingsUiState> = combine(
        repo.observeSelfAccount(),
        repo.observeAgentAccount(),
        transient,
        prefs.observeLanguageTag(),
        prefs.observeFetchIntervals(),
    ) { self, agent, t, langTag, intervals ->
        Quint(self, agent, t, langTag, intervals)
    }.let { base ->
        combine(
            base,
            prefs.observeCrashReportPref(),
            prefs.observeRealtimePush(),
            prefs.observeRealtimeBatteryDialogShown(),
        ) { b, crashPref, push, batteryShown ->
            SettingsUiState(
                self = b.self,
                agent = b.agent,
                message = b.t.message,
                busy = b.t.busy,
                languageTag = b.langTag,
                fetchIntervals = b.intervals,
                crashReportPref = crashPref,
                hasZipPassword = b.t.hasZipPassword,
                realtimePush = push,
                realtimeBatteryDialogShown = batteryShown,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    /** 编辑 AGENT（仅 displayName + email）。 */
    fun saveAgent(displayName: String, email: String) {
        viewModelScope.launch {
            transient.value = TransientState(busy = true)
            runCatching {
                repo.saveAgentAccount(displayName.ifBlank { email }, email.trim())
            }.onFailure { e ->
                transient.value = TransientState(
                    message = UiText.Resource(
                        R.string.settings_msg_save_failed,
                        listOf(e.message.orEmpty()),
                    ),
                )
                return@launch
            }
            transient.value = TransientState()
        }
    }

    /**
     * 编辑"我"的身份（displayName + email）。
     *
     * 该入口不依赖"邮箱设置"：SELF 不存在时会创建一条仅含身份的占位记录，
     * IMAP/SMTP/密码后续由"邮箱设置"补齐。
     */
    fun saveSelfIdentity(displayName: String, email: String) {
        viewModelScope.launch {
            transient.value = TransientState(busy = true)
            runCatching {
                repo.saveSelfIdentity(
                    displayName = displayName.ifBlank { email },
                    email = email.trim(),
                )
            }.onFailure { e ->
                transient.value = TransientState(
                    message = UiText.Resource(
                        R.string.settings_msg_save_failed,
                        listOf(e.message.orEmpty()),
                    ),
                )
                return@launch
            }
            transient.value = TransientState()
        }
    }

    fun clearMessage() {
        transient.value = transient.value.copy(message = null)
    }

    /**
     * 切换应用语言。
     *
     * 1. 持久化 tag，下次启动 Application 将读取它（主要起保留作用，
     *    pre-33 上 AppCompat 自己也会写 SharedPreferences）。
     * 2. 调 [AppCompatDelegate.setApplicationLocales]：
     *    - 当前 Activity 会被 AppCompat 自动 recreate，新语言立即生效。
     *    - API 33+ 上还会同步到系统「应用语言」面板。
     */
    fun setLanguage(tag: String) {
        viewModelScope.launch {
            prefs.setLanguageTag(tag)
        }
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(tag),
        )
    }

    /** 切换实时推送开关，创建引线由 Controller 持续听并拉起/停掉 PushSyncService。 */
    fun setRealtimePush(enabled: Boolean) {
        viewModelScope.launch { prefs.setRealtimePush(enabled) }
    }

    /** 电池优化引导已展示过：走设置 / 不再提示 均调此方法。 */
    fun markBatteryDialogShown() {
        viewModelScope.launch { prefs.setRealtimeBatteryDialogShown(true) }
    }

    /** 切换崩溃上报偏好（马上生效，下次启动 CrashReportPrompt 读新值）。 */
    fun setCrashReportPref(pref: CrashReportPref) {
        viewModelScope.launch { prefs.setCrashReportPref(pref) }
    }

    /**
     * 设置加密 zip 附件的主解密密码。空串等同清除。
     *
     * 密码写入 [CredentialsVault]（EncryptedSharedPreferences）后立刻权威；
     * UI 侧只重置 hasZipPassword 状态，不在界面再回显明文。
     */
    fun setZipPassword(password: String) {
        val trimmed = password
        viewModelScope.launch {
            if (trimmed.isEmpty()) {
                repo.clearZipPassword()
            } else {
                repo.setZipPassword(trimmed)
            }
            transient.value = transient.value.copy(hasZipPassword = repo.hasZipPassword())
        }
    }

    /** 清除已保存的 zip 主密码。 */
    fun clearZipPassword() {
        viewModelScope.launch {
            repo.clearZipPassword()
            transient.value = transient.value.copy(hasZipPassword = false)
        }
    }

    private data class TransientState(
        val message: UiText? = null,
        val busy: Boolean = false,
        val hasZipPassword: Boolean = false,
    )

    /** 5 元组中间态：规避 combine 只有 5 参重载的限制。 */
    private data class Quint(
        val self: Account?,
        val agent: Account?,
        val t: TransientState,
        val langTag: String?,
        val intervals: FetchIntervals,
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    repo = AppServiceLocator.mailRepository,
                    prefs = AppServiceLocator.appPreferences,
                )
            }
        }
    }
}
