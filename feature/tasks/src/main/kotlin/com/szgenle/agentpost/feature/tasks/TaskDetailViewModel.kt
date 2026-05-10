package com.szgenle.agentpost.feature.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.core.data.MailRepository
import com.szgenle.agentpost.core.datastore.AppPreferences
import com.szgenle.agentpost.core.model.Task
import com.szgenle.agentpost.core.model.TaskMessage
import com.szgenle.agentpost.core.ui.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val TASK_ID_ARG = "taskId"

data class TaskDetailUiState(
    val task: Task? = null,
    val messages: List<TaskMessage> = emptyList(),
    val draftReply: String = "",
    val sending: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: UiText? = null,
)

class TaskDetailViewModel(
    private val repo: MailRepository,
    private val prefs: AppPreferences,
    private val taskId: String,
) : ViewModel() {

    private val taskFlow = MutableStateFlow<Task?>(null)
    private val transient = MutableStateFlow(TransientState())

    val uiState: StateFlow<TaskDetailUiState> = combine(
        taskFlow,
        repo.observeMessages(taskId),
        prefs.observeDraftReply(taskId),
        transient,
    ) { task, messages, draft, t ->
        TaskDetailUiState(
            task = task,
            messages = messages,
            draftReply = draft,
            sending = t.sending,
            isRefreshing = t.refreshing,
            error = t.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TaskDetailUiState(),
    )

    init {
        viewModelScope.launch {
            taskFlow.value = repo.getTask(taskId)
            // 进详情自动清未读
            repo.markTaskRead(taskId)
        }
    }

    /** 输入框边输边持久化。DataStore 会自动合并并发写入。 */
    fun updateDraft(text: String) {
        viewModelScope.launch { prefs.setDraftReply(taskId, text) }
    }

    /**
     * 发送回复。新语义：
     * - 点击后立即清空草稿 + 重置输入框
     * - repo 内部先落 PENDING 占位（UI 立刻看到气泡）再异步发送
     * - 失败不弹 snackbar，由气泡的 FAILED 状态 + 重试按钮反馈
     */
    fun sendReply(body: String) {
        if (body.isBlank()) return
        viewModelScope.launch {
            prefs.clearDraftReply(taskId)
            transient.value = transient.value.copy(sending = true)
            // repo.sendReply 只在"前置校验失败"（无账号/Task 不存在）时 Result.failure
            val r = repo.sendReply(taskId = taskId, body = body, attachments = emptyList())
            transient.value = r.fold(
                onSuccess = { transient.value.copy(sending = false, error = null) },
                onFailure = { e ->
                    val msg = e.message
                    val uiText = if (!msg.isNullOrBlank()) {
                        UiText.Dynamic(msg)
                    } else {
                        UiText.Resource(R.string.task_detail_send_failed)
                    }
                    transient.value.copy(sending = false, error = uiText)
                },
            )
        }
    }

    /** 重试一条 FAILED 的消息（按本地 id 定位）。 */
    fun retrySend(localMessageId: String) {
        viewModelScope.launch {
            val r = repo.retrySend(localMessageId)
            if (r.isFailure) {
                val msg = r.exceptionOrNull()?.message
                val uiText = if (!msg.isNullOrBlank()) {
                    UiText.Dynamic(msg)
                } else {
                    UiText.Resource(R.string.task_detail_send_failed)
                }
                transient.value = transient.value.copy(error = uiText)
            }
        }
    }

    /** 详情页手动下拉刷新：触发一次 [MailRepository.syncInbox]，失败走同一 error 管道。 */
    fun refresh() {
        if (transient.value.refreshing) return
        viewModelScope.launch {
            transient.value = transient.value.copy(refreshing = true)
            val r = repo.syncInbox()
            transient.value = r.fold(
                onSuccess = { transient.value.copy(refreshing = false) },
                onFailure = { e ->
                    val msg = e.message
                    val uiText = if (!msg.isNullOrBlank()) {
                        UiText.Dynamic(msg)
                    } else {
                        UiText.Resource(R.string.tasks_sync_failed)
                    }
                    transient.value.copy(refreshing = false, error = uiText)
                },
            )
        }
    }

    fun consumeError() {
        transient.value = transient.value.copy(error = null)
    }

    private data class TransientState(
        val sending: Boolean = false,
        val refreshing: Boolean = false,
        val error: UiText? = null,
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val handle: SavedStateHandle = createSavedStateHandle()
                val taskId: String = checkNotNull(handle[TASK_ID_ARG]) {
                    "taskId arg required for TaskDetailViewModel"
                }
                TaskDetailViewModel(
                    repo = AppServiceLocator.mailRepository,
                    prefs = AppServiceLocator.appPreferences,
                    taskId = taskId,
                )
            }
        }
    }
}
