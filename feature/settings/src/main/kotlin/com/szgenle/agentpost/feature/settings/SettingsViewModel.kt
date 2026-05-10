package com.szgenle.agentpost.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.core.data.MailRepository
import com.szgenle.agentpost.core.model.Account
import com.szgenle.agentpost.core.model.AccountType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * Settings 屏幕状态。
 */
data class SettingsUiState(
    val self: Account? = null,
    val agent: Account? = null,
    val message: String? = null,
    val busy: Boolean = false,
)

class SettingsViewModel(
    private val repo: MailRepository,
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    val uiState: StateFlow<SettingsUiState> = combine(
        repo.observeSelfAccount(),
        repo.observeAgentAccount(),
        transient,
    ) { self, agent, t ->
        SettingsUiState(self = self, agent = agent, message = t.message, busy = t.busy)
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
            }.onFailure {
                transient.value = TransientState(message = "保存失败：${it.message}")
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
            }.onFailure {
                transient.value = TransientState(message = "保存失败：${it.message}")
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            transient.value = TransientState(busy = true)
            val result = repo.syncInbox()
            transient.value = TransientState(
                message = result.fold(
                    onSuccess = { n -> "连接成功，拉到 $n 封新邮件" },
                    onFailure = { "连接失败：${it.message}" },
                )
            )
        }
    }

    /**
     * 保存 SELF / AGENT 成功后自动触发一次拉取，不再等 15 分钟的周期性 Worker。
     * - 两个账户都已配置：立即 syncInbox，结果回到 snackbar
     * - 只配了一方：提示用户继续配另一方（syncInbox 会报缺少 AGENT/SELF的错）
     */
    private fun autoSyncAfterSave(savedLabel: String) {
        viewModelScope.launch {
            val self = repo.getSelfAccount()
            val agent = repo.getAgentAccount()
            if (self == null || agent == null) {
                val missing = if (self == null) "自己的邮箱 SELF" else "AI 的邮箱 AGENT"
                transient.value = TransientState(
                    message = "$savedLabel 已保存，请继续配置${missing}后才能拉取邮件",
                )
                return@launch
            }
            transient.value = TransientState(busy = true, message = "$savedLabel 已保存，正在拉取邮件……")
            val result = repo.syncInbox()
            transient.value = TransientState(
                message = result.fold(
                    onSuccess = { n -> "$savedLabel 已保存，拉到 $n 封新邮件" },
                    onFailure = { "$savedLabel 已保存，但拉取失败：${it.message}" },
                )
            )
        }
    }

    fun clearMessage() {
        transient.value = transient.value.copy(message = null)
    }

    private data class TransientState(
        val message: String? = null,
        val busy: Boolean = false,
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(AppServiceLocator.mailRepository)
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
