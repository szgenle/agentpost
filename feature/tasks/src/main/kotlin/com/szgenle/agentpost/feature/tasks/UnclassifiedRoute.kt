package com.szgenle.agentpost.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.core.data.MailRepository
import com.szgenle.agentpost.core.model.Task
import com.szgenle.agentpost.core.model.TaskMessage
import com.szgenle.agentpost.core.ui.UiText
import com.szgenle.agentpost.core.ui.time.RelativeTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.szgenle.agentpost.core.ui.R as CoreUiR

/**
 * 未分类消息管理页的 UI 状态。
 *
 * @property messages 兜底任务（`__UNCLASSIFIED__`）下的所有消息，按时间升序
 * @property tasks 非占位的 Task 列表，用于 BottomSheet 选择指派目标
 * @property pickerForMessageId 当前正要指派的消息 id；非空即展开 BottomSheet
 * @property assigningMessageId 指派动作进行中的消息 id，用于阻止重复点击
 * @property lastAssignedTitle 最近一次成功指派到的 Task 标题，UI 弹一次 Snackbar 后消费
 * @property error 一次性错误文案
 */
data class UnclassifiedUiState(
    val messages: List<TaskMessage> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val pickerForMessageId: String? = null,
    val assigningMessageId: String? = null,
    val lastAssignedTitle: String? = null,
    val error: UiText? = null,
)

/**
 * 未分类消息管理 ViewModel。
 *
 * 订阅 [MailRepository.observeUnclassifiedMessages] 和 [MailRepository.observeTasks]，
 * 指派动作走 [MailRepository.assignMessageToTask]——该 API 里已经兜了 “目标 Task
 * 不能是占位” 的断言，所以 UI 侧只要保证 sheet 里的列表不含占位就安全。
 */
class UnclassifiedViewModel(
    private val repo: MailRepository,
) : ViewModel() {

    private val transient = MutableStateFlow(Transient())

    val uiState: StateFlow<UnclassifiedUiState> = combine(
        repo.observeUnclassifiedMessages(),
        repo.observeTasks(),
        transient,
    ) { messages, tasks, t ->
        UnclassifiedUiState(
            messages = messages,
            tasks = tasks,
            pickerForMessageId = t.pickerForMessageId,
            assigningMessageId = t.assigningMessageId,
            lastAssignedTitle = t.lastAssignedTitle,
            error = t.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UnclassifiedUiState(),
    )

    fun openPicker(messageId: String) {
        transient.value = transient.value.copy(pickerForMessageId = messageId)
    }

    fun dismissPicker() {
        transient.value = transient.value.copy(pickerForMessageId = null)
    }

    /**
     * 执行指派。成功后关闭 sheet、冒一次 Snackbar 含目标 Task 标题。
     * 当前 assigningMessageId 存在期间同一条重复点击被忽略。
     */
    fun assignTo(targetTask: Task) {
        val msgId = transient.value.pickerForMessageId ?: return
        if (transient.value.assigningMessageId != null) return

        viewModelScope.launch {
            transient.value = transient.value.copy(assigningMessageId = msgId)
            val r = runCatching { repo.assignMessageToTask(msgId, targetTask.id) }
            transient.value = r.fold(
                onSuccess = {
                    transient.value.copy(
                        assigningMessageId = null,
                        pickerForMessageId = null,
                        lastAssignedTitle = targetTask.title,
                    )
                },
                onFailure = { e ->
                    val msg = e.message
                    val uiText = if (!msg.isNullOrBlank()) {
                        UiText.Dynamic(msg)
                    } else {
                        UiText.Resource(R.string.unclassified_assign_failed)
                    }
                    transient.value.copy(
                        assigningMessageId = null,
                        pickerForMessageId = null,
                        error = uiText,
                    )
                },
            )
        }
    }

    fun consumeError() {
        transient.value = transient.value.copy(error = null)
    }

    fun consumeAssignedTitle() {
        transient.value = transient.value.copy(lastAssignedTitle = null)
    }

    private data class Transient(
        val pickerForMessageId: String? = null,
        val assigningMessageId: String? = null,
        val lastAssignedTitle: String? = null,
        val error: UiText? = null,
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { UnclassifiedViewModel(AppServiceLocator.mailRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnclassifiedRoute(
    onBack: () -> Unit,
    viewModel: UnclassifiedViewModel = viewModel(factory = UnclassifiedViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 错误：一次性 Snackbar，用完消费
    LaunchedEffect(state.error) {
        val e = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(e.asString(context))
        viewModel.consumeError()
    }
    // 成功：展示指派到的 Task 标题
    val assignedFmt = stringResource(R.string.unclassified_assigned_to)
    val untitled = stringResource(R.string.tasks_untitled)
    LaunchedEffect(state.lastAssignedTitle) {
        val title = state.lastAssignedTitle ?: return@LaunchedEffect
        val label = title.ifEmpty { untitled }
        snackbarHostState.showSnackbar(assignedFmt.format(label))
        viewModel.consumeAssignedTitle()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.unclassified_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(CoreUiR.string.common_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.unclassified_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(state.messages, key = { it.id }) { msg ->
                    UnclassifiedMessageRow(
                        msg = msg,
                        onAssignClick = { viewModel.openPicker(msg.id) },
                    )
                }
            }
        }
    }

    // BottomSheet：选择目标 Task
    if (state.pickerForMessageId != null) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissPicker,
            sheetState = sheetState,
        ) {
            TaskPicker(
                tasks = state.tasks,
                onPick = { task -> viewModel.assignTo(task) },
                onDismiss = viewModel::dismissPicker,
            )
        }
    }
}

@Composable
private fun UnclassifiedMessageRow(
    msg: TaskMessage,
    onAssignClick: () -> Unit,
) {
    val context = LocalContext.current
    ListItem(
        overlineContent = {
            Text(RelativeTime.format(context, msg.sentAt))
        },
        headlineContent = {
            Text(
                msg.subject.ifEmpty { stringResource(R.string.tasks_untitled) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                msg.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            TextButton(onClick = onAssignClick) {
                Text(stringResource(R.string.unclassified_action_assign))
            }
        },
        modifier = Modifier.clickable { onAssignClick() },
    )
}

/**
 * BottomSheet 内的目标 Task 选择列表。空列表时给出提示引导用户先去新建任务。
 */
@Composable
private fun TaskPicker(
    tasks: List<Task>,
    onPick: (Task) -> Unit,
    onDismiss: () -> Unit,
) {
    val untitled = stringResource(R.string.tasks_untitled)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.unclassified_picker_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (tasks.isEmpty()) {
            Text(
                stringResource(R.string.unclassified_picker_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(CoreUiR.string.common_back))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(tasks, key = { it.id }) { task ->
                    ListItem(
                        headlineContent = {
                            Text(task.title.ifEmpty { untitled })
                        },
                        modifier = Modifier.clickable { onPick(task) },
                    )
                }
            }
        }
    }
}
