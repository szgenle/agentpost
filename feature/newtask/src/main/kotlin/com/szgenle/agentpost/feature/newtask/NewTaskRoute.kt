package com.szgenle.agentpost.feature.newtask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.core.data.MailRepository
import com.szgenle.agentpost.core.datastore.AppPreferences
import com.szgenle.agentpost.core.datastore.NewTaskDraft
import com.szgenle.agentpost.core.ui.UiText
import com.szgenle.agentpost.core.ui.R as CoreUiR
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NewTaskUiState(
    val draft: NewTaskDraft = NewTaskDraft("", ""),
    val busy: Boolean = false,
    val sentTaskId: String? = null,
    val error: UiText? = null,
)

class NewTaskViewModel(
    private val repo: MailRepository,
    private val prefs: AppPreferences,
) : ViewModel() {
    private val transient = MutableStateFlow(TransientState())

    val state: StateFlow<NewTaskUiState> = combine(
        prefs.observeNewTaskDraft(),
        transient,
    ) { draft, t ->
        NewTaskUiState(
            draft = draft,
            busy = t.busy,
            sentTaskId = t.sentTaskId,
            error = t.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NewTaskUiState(),
    )

    /** subject / body 有任一变动时整体落一次盘，语义简单。 */
    fun updateDraft(subject: String, body: String) {
        viewModelScope.launch { prefs.setNewTaskDraft(subject, body) }
    }

    fun send(title: String, body: String) {
        viewModelScope.launch {
            transient.value = TransientState(busy = true)
            val result = repo.sendNewTask(title = title, body = body, attachments = emptyList())
            transient.value = result.fold(
                onSuccess = { taskId ->
                    // 发送成功清空草稿，避免下次进来又出现
                    prefs.clearNewTaskDraft()
                    TransientState(sentTaskId = taskId)
                },
                onFailure = { e ->
                    // 服务端错误优先直接展示，其次才 fallback 到本地资源
                    val msg = e.message
                    val uiText = if (!msg.isNullOrBlank()) {
                        UiText.Dynamic(msg)
                    } else {
                        UiText.Resource(R.string.new_task_send_failed)
                    }
                    TransientState(error = uiText)
                },
            )
        }
    }

    fun consumeSent() { transient.value = TransientState() }
    fun consumeError() { transient.value = transient.value.copy(error = null) }

    private data class TransientState(
        val busy: Boolean = false,
        val sentTaskId: String? = null,
        val error: UiText? = null,
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NewTaskViewModel(
                    repo = AppServiceLocator.mailRepository,
                    prefs = AppServiceLocator.appPreferences,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTaskRoute(
    onBack: () -> Unit,
    onSent: () -> Unit,
    viewModel: NewTaskViewModel = viewModel(factory = NewTaskViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.sentTaskId) {
        if (state.sentTaskId != null) {
            viewModel.consumeSent()
            onSent()
        }
    }
    LaunchedEffect(state.error) {
        val e = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(e.asString(context))
        viewModel.consumeError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_task_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(CoreUiR.string.common_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // 两个输入框各自本地托管 TextFieldValue，保住光标位置。
        // 外部 state.draft 只在首次加载 / 外部清空时同步到本地，避免 DataStore
        // 异步回推的旧值把当前输入覆盖回去。
        var subjectField by rememberSaveable(stateSaver = TextFieldValue.Saver) {
            mutableStateOf(
                TextFieldValue(state.draft.subject, TextRange(state.draft.subject.length)),
            )
        }
        var bodyField by rememberSaveable(stateSaver = TextFieldValue.Saver) {
            mutableStateOf(
                TextFieldValue(state.draft.body, TextRange(state.draft.body.length)),
            )
        }
        LaunchedEffect(state.draft.subject) {
            val remote = state.draft.subject
            if (remote != subjectField.text &&
                (subjectField.text.isEmpty() || remote.isEmpty())
            ) {
                subjectField = TextFieldValue(remote, TextRange(remote.length))
            }
        }
        LaunchedEffect(state.draft.body) {
            val remote = state.draft.body
            if (remote != bodyField.text &&
                (bodyField.text.isEmpty() || remote.isEmpty())
            ) {
                bodyField = TextFieldValue(remote, TextRange(remote.length))
            }
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = subjectField,
                onValueChange = {
                    subjectField = it
                    viewModel.updateDraft(it.text, bodyField.text)
                },
                label = { Text(stringResource(R.string.new_task_subject_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = bodyField,
                onValueChange = {
                    bodyField = it
                    viewModel.updateDraft(subjectField.text, it.text)
                },
                label = { Text(stringResource(R.string.new_task_body_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
            )
            Button(
                onClick = { viewModel.send(subjectField.text.trim(), bodyField.text) },
                enabled = !state.busy && subjectField.text.isNotBlank() && bodyField.text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.busy) {
                        stringResource(R.string.new_task_sending)
                    } else {
                        stringResource(CoreUiR.string.common_send)
                    },
                )
            }
        }
    }
}
