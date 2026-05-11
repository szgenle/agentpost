package com.szgenle.agentpost.feature.newtask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.clickable
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
import com.szgenle.agentpost.core.model.CommandTemplate
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
    /** 命令模板库当前快照。空列表 ⇒ 模板选择器展空态。 */
    val templates: List<CommandTemplate> = emptyList(),
)

class NewTaskViewModel(
    private val repo: MailRepository,
    private val prefs: AppPreferences,
) : ViewModel() {
    private val transient = MutableStateFlow(TransientState())

    val state: StateFlow<NewTaskUiState> = combine(
        prefs.observeNewTaskDraft(),
        prefs.observeCommandTemplates(),
        transient,
    ) { draft, templates, t ->
        NewTaskUiState(
            draft = draft,
            busy = t.busy,
            sentTaskId = t.sentTaskId,
            error = t.error,
            templates = templates,
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
    var showTemplateSheet by remember { mutableStateOf(false) }

    // 两个输入框各自本地托管 TextFieldValue，保住光标位置。
    // 外部 state.draft 只在首次加载 / 外部清空时同步到本地，避免 DataStore
    // 异步回推的旧值把当前输入覆盖回去。
    // 提升到 Scaffold 外层是为了让 TemplatePickerBottomSheet 的 onPick 回调也能回填。
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
                actions = {
                    // 模板选择入口：空库时仍允许点开以引导用户去设置页新建，
                    // 设置页管理逻辑在 feature:settings/CommandTemplatesRoute。
                    IconButton(onClick = { showTemplateSheet = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.new_task_action_pick_template),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
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

    if (showTemplateSheet) {
        TemplatePickerBottomSheet(
            templates = state.templates,
            onDismiss = { showTemplateSheet = false },
            onPick = { template ->
                subjectField = TextFieldValue(
                    template.subject,
                    TextRange(template.subject.length),
                )
                bodyField = TextFieldValue(
                    template.body,
                    TextRange(template.body.length),
                )
                // 同步回 DataStore，避免下次进来 LaunchedEffect 把空草稿洗回去
                viewModel.updateDraft(template.subject, template.body)
                showTemplateSheet = false
            },
        )
    }
}

/**
 * 模板选择 BottomSheet。
 *
 * - 空库时展现一条引导文案，不提供弹跳（v1 保持简单）；
 * - headline 用 title，supporting 预览前 40 字的 subject，帮用户辨认；
 * - 选中后由上层填 subject/body、同步草稿、关闭 sheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplatePickerBottomSheet(
    templates: List<CommandTemplate>,
    onPick: (CommandTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.new_task_template_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (templates.isEmpty()) {
                Text(
                    text = stringResource(R.string.new_task_template_sheet_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(templates, key = { it.id }) { template ->
                        ListItem(
                            headlineContent = { Text(template.title) },
                            supportingContent = {
                                val preview = template.subject.take(40)
                                if (preview.isNotBlank()) Text(preview)
                            },
                            modifier = Modifier.clickable { onPick(template) },
                        )
                    }
                }
            }
        }
    }
}
