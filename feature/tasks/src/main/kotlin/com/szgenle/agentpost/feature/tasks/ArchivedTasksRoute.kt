package com.szgenle.agentpost.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.szgenle.agentpost.core.data.TaskBrief
import com.szgenle.agentpost.core.ui.UiText
import com.szgenle.agentpost.core.ui.components.AppTopBar
import com.szgenle.agentpost.core.ui.time.RelativeTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.szgenle.agentpost.core.ui.R as CoreUiR

/**
 * 已归档任务页 UI 状态。
 *
 * @property briefs 已归档任务的卡片摘要，按 lastActivityAt 倒序
 * @property lastUnarchivedTitle 最近一次成功恢复的任务标题；UI 弹一次 Snackbar 后消费
 * @property lastDeletedTitle 最近一次成功硬删除的任务标题；UI 弹一次 Snackbar 后消费
 * @property error 一次性错误文案
 */
data class ArchivedTasksUiState(
    val briefs: List<TaskBrief> = emptyList(),
    val lastUnarchivedTitle: String? = null,
    val lastDeletedTitle: String? = null,
    val error: UiText? = null,
)

/**
 * 已归档任务 ViewModel。
 *
 * 订阅 [MailRepository.observeArchivedTaskBriefs]；恢复动作走 [MailRepository.unarchiveTask]，
 * 成功后靠 Flow 自动从列表里剔除该任务。
 */
class ArchivedTasksViewModel(
    private val repo: MailRepository,
) : ViewModel() {

    private val transient = MutableStateFlow(Transient())

    val uiState: StateFlow<ArchivedTasksUiState> = combine(
        repo.observeArchivedTaskBriefs(),
        transient,
    ) { briefs, t ->
        ArchivedTasksUiState(
            briefs = briefs,
            lastUnarchivedTitle = t.lastUnarchivedTitle,
            lastDeletedTitle = t.lastDeletedTitle,
            error = t.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ArchivedTasksUiState(),
    )

    fun unarchive(brief: TaskBrief) {
        viewModelScope.launch {
            val r = runCatching { repo.unarchiveTask(brief.task.id) }
            transient.value = r.fold(
                onSuccess = { transient.value.copy(lastUnarchivedTitle = brief.task.title) },
                onFailure = { e ->
                    val msg = e.message
                    val uiText = if (!msg.isNullOrBlank()) {
                        UiText.Dynamic(msg)
                    } else {
                        UiText.Resource(R.string.archived_unarchive_failed)
                    }
                    transient.value.copy(error = uiText)
                },
            )
        }
    }

    /**
     * 硬删除一个已归档任务。调用前应由 UI 弹过二次确认对话框；
     * 这里不再拦截。成功时捕获任务标题用于 Snackbar 回显，失败走统一 error 通道。
     */
    fun deleteTask(brief: TaskBrief) {
        viewModelScope.launch {
            val r = runCatching { repo.deleteTask(brief.task.id) }
            transient.value = r.fold(
                onSuccess = { transient.value.copy(lastDeletedTitle = brief.task.title) },
                onFailure = { e ->
                    val msg = e.message
                    val uiText = if (!msg.isNullOrBlank()) {
                        UiText.Dynamic(msg)
                    } else {
                        UiText.Resource(R.string.archived_delete_failed)
                    }
                    transient.value.copy(error = uiText)
                },
            )
        }
    }

    fun consumeError() {
        transient.value = transient.value.copy(error = null)
    }

    fun consumeUnarchivedTitle() {
        transient.value = transient.value.copy(lastUnarchivedTitle = null)
    }

    fun consumeDeletedTitle() {
        transient.value = transient.value.copy(lastDeletedTitle = null)
    }

    private data class Transient(
        val lastUnarchivedTitle: String? = null,
        val lastDeletedTitle: String? = null,
        val error: UiText? = null,
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { ArchivedTasksViewModel(AppServiceLocator.mailRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedTasksRoute(
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
    viewModel: ArchivedTasksViewModel = viewModel(factory = ArchivedTasksViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        val e = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(e.asString(context))
        viewModel.consumeError()
    }
    val unarchivedFmt = stringResource(R.string.archived_unarchived_toast)
    val untitled = stringResource(R.string.tasks_untitled)
    LaunchedEffect(state.lastUnarchivedTitle) {
        val title = state.lastUnarchivedTitle ?: return@LaunchedEffect
        val label = title.ifEmpty { untitled }
        snackbarHostState.showSnackbar(unarchivedFmt.format(label))
        viewModel.consumeUnarchivedTitle()
    }

    val deletedFmt = stringResource(R.string.archived_deleted_toast)
    LaunchedEffect(state.lastDeletedTitle) {
        val title = state.lastDeletedTitle ?: return@LaunchedEffect
        val label = title.ifEmpty { untitled }
        snackbarHostState.showSnackbar(deletedFmt.format(label))
        viewModel.consumeDeletedTitle()
    }

    // 二次确认硬删除的对象；null 表示当前没有待确认的删除请求
    var pendingDelete by remember { mutableStateOf<TaskBrief?>(null) }
    pendingDelete?.let { target ->
        val label = target.task.title.ifEmpty { untitled }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.archived_delete_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.archived_delete_confirm_message))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTask(target)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.archived_delete_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.archived_delete_confirm_cancel))
                }
            },
        )
    }

    val noMessages = stringResource(R.string.tasks_item_no_messages)

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(stringResource(R.string.archived_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(CoreUiR.string.common_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.briefs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.archived_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.archived_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            // 卡片式列表：与任务列表样式一致，但每张卡片尾部挂一枚"恢复"按钮。
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.briefs, key = { it.task.id }) { brief ->
                    ElevatedCard(
                        onClick = { onOpenTask(brief.task.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        ListItem(
                            overlineContent = {
                                Text(RelativeTime.format(context, brief.lastMessageAt))
                            },
                            headlineContent = {
                                Text(brief.task.title.ifEmpty { untitled })
                            },
                            supportingContent = {
                                Text(
                                    text = brief.lastMessagePreview.ifEmpty { noMessages },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(onClick = { viewModel.unarchive(brief) }) {
                                        Text(stringResource(R.string.archived_action_unarchive))
                                    }
                                    var menuExpanded by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { menuExpanded = true }) {
                                            Text(
                                                "⋮",
                                                style = MaterialTheme.typography.titleLarge,
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = menuExpanded,
                                            onDismissRequest = { menuExpanded = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(stringResource(R.string.archived_menu_delete))
                                                },
                                                onClick = {
                                                    menuExpanded = false
                                                    pendingDelete = brief
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                            ),
                        )
                    }
                }
            }
        }
    }
}
