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
import com.szgenle.agentpost.core.model.Task
import com.szgenle.agentpost.core.model.TaskMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val TASK_ID_ARG = "taskId"

data class TaskDetailUiState(
    val task: Task? = null,
    val messages: List<TaskMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
)

class TaskDetailViewModel(
    private val repo: MailRepository,
    private val taskId: String,
) : ViewModel() {

    private val taskFlow = MutableStateFlow<Task?>(null)
    private val transient = MutableStateFlow(TransientState())

    val uiState: StateFlow<TaskDetailUiState> = combine(
        taskFlow,
        repo.observeMessages(taskId),
        transient,
    ) { task, messages, t ->
        TaskDetailUiState(
            task = task,
            messages = messages,
            sending = t.sending,
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

    fun sendReply(body: String) {
        if (body.isBlank()) return
        viewModelScope.launch {
            transient.value = TransientState(sending = true)
            val r = repo.sendReply(taskId = taskId, body = body, attachments = emptyList())
            transient.value = r.fold(
                onSuccess = { TransientState() },
                onFailure = { TransientState(error = it.message ?: "发送失败") },
            )
        }
    }

    fun consumeError() {
        transient.value = transient.value.copy(error = null)
    }

    private data class TransientState(
        val sending: Boolean = false,
        val error: String? = null,
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val handle: SavedStateHandle = createSavedStateHandle()
                val taskId: String = checkNotNull(handle[TASK_ID_ARG]) {
                    "taskId arg required for TaskDetailViewModel"
                }
                TaskDetailViewModel(AppServiceLocator.mailRepository, taskId)
            }
        }
    }
}
