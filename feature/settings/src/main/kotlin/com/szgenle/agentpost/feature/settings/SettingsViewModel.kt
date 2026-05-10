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
import com.szgenle.agentpost.core.model.Account
import com.szgenle.agentpost.core.model.AccountType
import com.szgenle.agentpost.core.ui.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * Settings 屏幕状态。message 使用 UiText，让 ViewModel 不直接持有本地化字符串。
 */
data class SettingsUiState(
    val self: Account? = null,
    val agent: Account? = null,
    val message: UiText? = null,
    val busy: Boolean = false,
    /** 当前应用语言 tag："zh-CN" / "en"。null 表示尚未加载完 DataStore。 */
    val languageTag: String? = null,
)

class SettingsViewModel(
    private val repo: MailRepository,
    private val prefs: AppPreferences,
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    val uiState: StateFlow<SettingsUiState> = combine(
        repo.observeSelfAccount(),
        repo.observeAgentAccount(),
        transient,
        prefs.observeLanguageTag(),
    ) { self, agent, t, langTag ->
        SettingsUiState(
            self = self,
            agent = agent,
            message = t.message,
            busy = t.busy,
            languageTag = langTag,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun saveSelf(form: SelfForm) {
        viewModelScope.launch {
            transient.value = TransientState(busy = true)
            runCatching {
                repo.saveSelfAccount(
                    displayName = form.displayName.ifBlank { form.email },
                    email = form.email.trim(),
                    imapHost = form.imapHost.trim(),
                    imapPort = form.imapPort,
                    imapUseSsl = form.imapUseSsl,
                    smtpHost = form.smtpHost.trim(),
                    smtpPort = form.smtpPort,
                    smtpUseStartTls = form.smtpUseStartTls,
                    password = form.password,
                )
            }.onSuccess {
                autoSyncAfterSave(savedLabel = "SELF")
            }.onFailure { e ->
                transient.value = TransientState(
                    message = UiText.Resource(
                        R.string.settings_msg_save_failed,
                        listOf(e.message.orEmpty()),
                    ),
                )
            }
        }
    }

    fun saveAgent(displayName: String, email: String) {
        viewModelScope.launch {
            transient.value = TransientState(busy = true)
            runCatching {
                repo.saveAgentAccount(displayName.ifBlank { email }, email.trim())
            }.onSuccess {
                autoSyncAfterSave(savedLabel = "AGENT")
            }.onFailure { e ->
                transient.value = TransientState(
                    message = UiText.Resource(
                        R.string.settings_msg_save_failed,
                        listOf(e.message.orEmpty()),
                    ),
                )
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            transient.value = TransientState(busy = true)
            val result = repo.syncInbox()
            transient.value = TransientState(
                message = result.fold(
                    onSuccess = { n ->
                        UiText.Resource(R.string.settings_msg_test_success, listOf(n))
                    },
                    onFailure = { e ->
                        UiText.Resource(
                            R.string.settings_msg_test_failed,
                            listOf(e.message.orEmpty()),
                        )
                    },
                ),
            )
        }
    }

    /**
     * 保存 SELF / AGENT 成功后自动触发一次拉取，不再等 15 分钟的周期性 Worker。
     * - 两个账户都已配置：立即 syncInbox，结果回到 snackbar
     * - 只配了一方：提示用户继续配另一方（syncInbox 会报缺少 AGENT/SELF 的错）
     */
    private fun autoSyncAfterSave(savedLabel: String) {
        viewModelScope.launch {
            val self = repo.getSelfAccount()
            val agent = repo.getAgentAccount()
            if (self == null || agent == null) {
                // 把两个"缺哪侧"拆成独立文案，而不是运行时拼接本地化片段，
                // ViewModel 因此不需要持 Context。
                val msgResId = if (self == null) {
                    R.string.settings_msg_saved_missing_self
                } else {
                    R.string.settings_msg_saved_missing_agent
                }
                transient.value = TransientState(
                    message = UiText.Resource(msgResId, listOf(savedLabel)),
                )
                return@launch
            }
            transient.value = TransientState(
                busy = true,
                message = UiText.Resource(
                    R.string.settings_msg_saved_syncing,
                    listOf(savedLabel),
                ),
            )
            val result = repo.syncInbox()
            transient.value = TransientState(
                message = result.fold(
                    onSuccess = { n ->
                        UiText.Resource(
                            R.string.settings_msg_saved_synced,
                            listOf(savedLabel, n),
                        )
                    },
                    onFailure = { e ->
                        UiText.Resource(
                            R.string.settings_msg_saved_sync_failed,
                            listOf(savedLabel, e.message.orEmpty()),
                        )
                    },
                ),
            )
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

    private data class TransientState(
        val message: UiText? = null,
        val busy: Boolean = false,
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

/** SELF 表单字段集合（未持久化，仅供 UI 回填 / 提交）。 */
data class SelfForm(
    val displayName: String = "",
    val email: String = "",
    val imapHost: String = "",
    val imapPort: Int = 993,
    val imapUseSsl: Boolean = true,
    val smtpHost: String = "",
    val smtpPort: Int = 587,
    val smtpUseStartTls: Boolean = true,
    val password: String = "",
)

fun Account.toSelfForm(): SelfForm = SelfForm(
    displayName = displayName,
    email = email,
    imapHost = imapHost,
    imapPort = imapPort,
    imapUseSsl = imapUseSsl,
    smtpHost = smtpHost,
    smtpPort = smtpPort,
    smtpUseStartTls = smtpUseStartTls,
    password = "", // 不回填密码
)
