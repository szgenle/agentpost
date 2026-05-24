package com.szgenle.agentpost.feature.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.szgenle.agentpost.core.ui.components.AppTopBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.szgenle.agentpost.core.data.SystemIds
import com.szgenle.agentpost.core.data.TaskBrief
import com.szgenle.agentpost.core.ui.UiText
import com.szgenle.agentpost.core.ui.time.RelativeTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.szgenle.agentpost.core.ui.R as CoreUiR

/**
 * 任务列表 ViewModel。
 *
 * 订阅 [MailRepository.observeTaskBriefs]（Task + 最新消息摘要 + 未读数），
 * 提供手动下拉刷新入口：走一次 [MailRepository.syncInbox]，失败时暴露一次性错误文案给 UI 弹 Snackbar。
 */
class TasksViewModel(
    private val repo: MailRepository,
) : ViewModel() {

    val briefs: StateFlow<List<TaskBrief>> = repo.observeTaskBriefs().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /**
     * 未分类消息条数。值 > 0 时任务列表顶部会显示一条「未分类 · N 条」入口，
     * 点进可手动指派到具体 Task。
     */
    val unclassifiedCount: StateFlow<Int> = repo.observeUnclassifiedMessages()
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _syncError = MutableStateFlow<UiText?>(null)
    val syncError: StateFlow<UiText?> = _syncError.asStateFlow()

    /** 批量归档成功事件：值为本次归档成功的任务标题，UI 消费后调 [consumeArchivedEvent]。 */
    private val _archivedEvent = MutableStateFlow<String?>(null)
    val archivedEvent: StateFlow<String?> = _archivedEvent.asStateFlow()

    /** 删除成功事件：值为被删除的任务标题。 */
    private val _deletedEvent = MutableStateFlow<String?>(null)
    val deletedEvent: StateFlow<String?> = _deletedEvent.asStateFlow()

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            val r = repo.syncInbox()
            _isRefreshing.value = false
            r.onFailure { e ->
                val msg = e.message
                _syncError.value = if (!msg.isNullOrBlank()) {
                    UiText.Dynamic(msg)
                } else {
                    UiText.Resource(R.string.tasks_sync_failed)
                }
            }
        }
    }

    fun consumeSyncError() {
        _syncError.value = null
    }

    /**
     * 归档单个任务。占位任务不可归档。
     */
    fun archiveTask(taskId: String, title: String) {
        if (taskId == SystemIds.UNCLASSIFIED_TASK_ID) return
        viewModelScope.launch {
            val r = runCatching { repo.archiveTask(taskId) }
            r.fold(
                onSuccess = { _archivedEvent.value = title },
                onFailure = { e ->
                    val msg = e.message
                    _syncError.value = if (!msg.isNullOrBlank()) {
                        UiText.Dynamic(msg)
                    } else {
                        UiText.Resource(R.string.tasks_archive_failed)
                    }
                },
            )
        }
    }
    
    /**
     * 删除单个任务（硬删除）。占位任务不可删除。
     */
    fun deleteTask(taskId: String, title: String) {
        if (taskId == SystemIds.UNCLASSIFIED_TASK_ID) return
        viewModelScope.launch {
            val r = runCatching { repo.deleteTask(taskId) }
            r.fold(
                onSuccess = { _deletedEvent.value = title },
                onFailure = { e ->
                    val msg = e.message
                    _syncError.value = if (!msg.isNullOrBlank()) {
                        UiText.Dynamic(msg)
                    } else {
                        UiText.Resource(R.string.tasks_delete_failed)
                    }
                },
            )
        }
    }

    fun consumeArchivedEvent() {
        _archivedEvent.value = null
    }

    fun consumeDeletedEvent() {
        _deletedEvent.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TasksViewModel(AppServiceLocator.mailRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksRoute(
    onOpenSettings: () -> Unit,
    onOpenNewTask: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenUnclassified: () -> Unit,
    onOpenArchived: () -> Unit,
    viewModel: TasksViewModel = viewModel(factory = TasksViewModel.Factory),
) {
    val briefs by viewModel.briefs.collectAsStateWithLifecycle()
    val unclassifiedCount by viewModel.unclassifiedCount.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val syncError by viewModel.syncError.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }

    // 长按卡片时弹出的上下文菜单目标
    var contextMenuBrief by remember { mutableStateOf<TaskBrief?>(null) }

    // 删除确认弹窗
    var pendingDelete by remember { mutableStateOf<TaskBrief?>(null) }

    LaunchedEffect(syncError) {
        val e = syncError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(e.asString(context))
        viewModel.consumeSyncError()
    }

    // 归档成功：弹 Snackbar 提示。
    val archivedEvent by viewModel.archivedEvent.collectAsStateWithLifecycle()
    LaunchedEffect(archivedEvent) {
        val title = archivedEvent ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            context.getString(R.string.tasks_archived_toast, title),
        )
        viewModel.consumeArchivedEvent()
    }

    // 删除成功：弹 Snackbar 提示。
    val deletedEvent by viewModel.deletedEvent.collectAsStateWithLifecycle()
    LaunchedEffect(deletedEvent) {
        val title = deletedEvent ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            context.getString(R.string.tasks_deleted_toast, title),
        )
        viewModel.consumeDeletedEvent()
    }

    val untitled = stringResource(R.string.tasks_untitled)
    val noMessages = stringResource(R.string.tasks_item_no_messages)

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(stringResource(R.string.tasks_title)) },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        // "⋮" 是纯视觉符号，不做国际化；跟 FAB 的 "+" 保持一致。
                        Text("⋮", style = MaterialTheme.typography.titleLarge)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tasks_menu_archived)) },
                            onClick = {
                                menuExpanded = false
                                onOpenArchived()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tasks_menu_settings)) },
                            onClick = {
                                menuExpanded = false
                                onOpenSettings()
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenNewTask,
                text = { Text(stringResource(R.string.tasks_action_new)) },
                // "+" 是视觉符号，不做国际化
                icon = { Text("+") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // 空列表也接下拉刷新：让用户"刚装上还没数据时"能主动触发一次拉取
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (briefs.isEmpty() && unclassifiedCount == 0) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.tasks_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.tasks_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                // 卡片式列表：上下留边、卡片间垂直间距 10dp，让每个任务独立成块。
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 顶部固定行：未分类消息入口。条数为 0 时整行不渲染，
                    // 使用者看不到干扰；一旦有兜底邮件再置顶提醒。
                    // 用 tertiaryContainer 色卡片与普通任务卡片区分，强调"待处理"。
                    if (unclassifiedCount > 0) {
                        item(key = "__unclassified_entry__") {
                            ElevatedCard(
                                onClick = onOpenUnclassified,
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                ),
                            ) {
                                ListItem(
                                    headlineContent = {
                                        Text(stringResource(R.string.tasks_unclassified_entry))
                                    },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                R.string.tasks_unclassified_entry_hint,
                                            ),
                                        )
                                    },
                                    trailingContent = {
                                        Badge { Text(unclassifiedCount.toString()) }
                                    },
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                    ),
                                )
                            }
                        }
                    }
                    items(briefs, key = { it.task.id }) { brief ->
                        TaskBriefCard(
                            brief = brief,
                            onClick = { onOpenTask(brief.task.id) },
                            onLongClick = { contextMenuBrief = brief },
                            contextMenuExpanded = contextMenuBrief?.task?.id == brief.task.id,
                            onDismissContextMenu = { contextMenuBrief = null },
                            onArchive = {
                                contextMenuBrief = null
                                viewModel.archiveTask(brief.task.id, brief.task.title)
                            },
                            onDelete = {
                                contextMenuBrief = null
                                pendingDelete = brief
                            },
                            untitledLabel = untitled,
                            noMessagesLabel = noMessages,
                        )
                    }
                }
            }
        }
    }

    // 删除确认弹窗
    pendingDelete?.let { target ->
        val label = target.task.title.ifEmpty { untitled }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.tasks_delete_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.tasks_delete_confirm_message))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTask(target.task.id, target.task.title)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.tasks_delete_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.tasks_delete_confirm_cancel))
                }
            },
        )
    }
}

/**
 * 任务卡片：点击进详情，长按弹出上下文菜单（归档/删除）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskBriefCard(
    brief: TaskBrief,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    contextMenuExpanded: Boolean,
    onDismissContextMenu: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    untitledLabel: String,
    noMessagesLabel: String,
) {
    val context = LocalContext.current
    Box {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            colors = CardDefaults.elevatedCardColors(),
        ) {
            ListItem(
                overlineContent = {
                    Text(RelativeTime.format(context, brief.lastMessageAt))
                },
                headlineContent = {
                    Text(brief.task.title.ifEmpty { untitledLabel })
                },
                supportingContent = {
                    Text(
                        text = brief.lastMessagePreview.ifEmpty { noMessagesLabel },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    if (brief.unreadCount > 0) {
                        Badge { Text(brief.unreadCount.toString()) }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                ),
            )
        }
        DropdownMenu(
            expanded = contextMenuExpanded,
            onDismissRequest = onDismissContextMenu,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tasks_card_menu_archive)) },
                onClick = onArchive,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tasks_card_menu_delete)) },
                onClick = onDelete,
            )
        }
    }
}
