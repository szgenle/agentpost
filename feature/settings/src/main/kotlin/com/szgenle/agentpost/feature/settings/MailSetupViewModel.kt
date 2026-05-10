package com.szgenle.agentpost.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.core.data.MailRepository
import com.szgenle.agentpost.core.model.Account
import com.szgenle.agentpost.core.ui.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 邮箱设置二级页状态。hasExistingPassword 用于 UI 密码框显示占位符。
 */
data class MailSetupUiState(
    val self: Account? = null,
    val hasExistingPassword: Boolean = false,
    val busy: Boolean = false,
    val message: UiText? = null,
)

class MailSetupViewModel(
    private val repo: MailRepository,
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    // Vault 中是否已存过 SELF 密码。self 每次变化时重新查一次，
    // 用于区分"仅创建了身份占位记录"与"已完整配置过密码"两种状态。
    private val hasPasswordFlow = repo.observeSelfAccount()
        .map { repo.hasSelfPassword() }

    val uiState: StateFlow<MailSetupUiState> = combine(
        repo.observeSelfAccount(),
        hasPasswordFlow,
        transient,
    ) { self, hasPwd, t ->
        MailSetupUiState(
            self = self,
            hasExistingPassword = hasPwd,
            busy = t.busy,
            message = t.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MailSetupUiState(),
    )

    fun save(form: SelfForm, newPassword: String) {
        viewModelScope.launch {
            transient.value = TransientState(busy = true)
            // 留空=不改：仅当用户输入了新密码才覆盖 Vault
            val passwordArg: String? = newPassword.ifBlank { null }
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
                    password = passwordArg,
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

    fun testConnection() {
        viewModelScope.launch {
            transient.value = TransientState(busy = true)
            val result = repo.syncInbox()
            transient.value = TransientState(
                message = result.fold(
                    onSuccess = { r ->
                        UiText.Resource(R.string.settings_msg_test_success, listOf(r.totalNew))
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

    fun clearMessage() {
        transient.value = transient.value.copy(message = null)
    }

    private data class TransientState(
        val message: UiText? = null,
        val busy: Boolean = false,
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MailSetupViewModel(repo = AppServiceLocator.mailRepository)
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
)
