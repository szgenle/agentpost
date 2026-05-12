package com.szgenle.agentpost.feature.tasks

import androidx.activity.compose.BackHandler
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

    /** 批量归档成功事件：值为本次归档成功的条数，UI 消费后调 [consumeArchivedEvent]。 */
    private val _archivedEvent = MutableStateFlow<Int?>(null)
    val archivedEvent: StateFlow<Int?> = _archivedEvent.asStateFlow()

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
     * 批量归档。防御过滤掉占位任务；UNCLASSIFIED 流入这里属于调用方漏针，遵循 “宽进严出” 静默忽略。
     * 单笔失败即中断，既已成功的不回滚（archived=1 本就幂等），UI 提示归档失败 → 用户打开已归档页均可核验实际结果。
     */
    fun archiveTasks(ids: Set<String>) {
        if (ids.isEmpty()) return
        val targets = ids.filter { it != SystemIds.UNCLASSIFIED_TASK_ID }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            var success = 0
            var failure: Throwable? = null
            for (id in targets) {
                val r = runCatching { repo.archiveTask(id) }
                if (r.isSuccess) {
                    success++
                } else {
                    failure = r.exceptionOrNull()
                    break
                }
            }
            if (failure != null) {
                val msg = failure.message
                _syncError.value = if (!msg.isNullOrBlank()) {
                    UiText.Dynamic(msg)
                } else {
                    UiText.Resource(R.string.tasks_archive_failed)
                }
                // 对已经成功的批次仍给反馈，避免用户误以为全部没生效
                if (success > 0) _archivedEvent.value = success
            } else {
                _archivedEvent.value = success
            }
        }
    }

    fun consumeArchivedEvent() {
        _archivedEvent.value = null
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

    // 长按进入的批量选择模式。配置变更（旋转屏）会丢当前选中集，这个折衰可接受。
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    val inSelectionMode = selectedIds.isNotEmpty()

    // 系统返回键：选择模式下拦截并退出选择，避免直接退出页面。
    BackHandler(enabled = inSelectionMode) {
        selectedIds = emptySet()
    }

    LaunchedEffect(syncError) {
        val e = syncError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(e.asString(context))
        viewModel.consumeSyncError()
    }

    // 批量归档成功：弹 Snackbar + 清空选中集，顶栏自然回落普通态。
    val archivedEvent by viewModel.archivedEvent.collectAsStateWithLifecycle()
    LaunchedEffect(archivedEvent) {
        val count = archivedEvent ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            context.getString(R.string.tasks_archived_toast, count),
        )
        selectedIds = emptySet()
        viewModel.consumeArchivedEvent()
    }

    val untitled = stringResource(R.string.tasks_untitled)
    val noMessages = stringResource(R.string.tasks_item_no_messages)

    Scaffold(
        topBar = {
            if (inSelectionMode) {
                AppTopBar(
                    title = {
                        Text(
                            stringResource(
                                R.string.tasks_selection_title_count,
                                selectedIds.size,
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            // “✕”是纯视觉符号，不做国际化；语义由 contentDescription 提供。
                            Text("✕", style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    actions = {
                        val allIds = briefs.map { it.task.id }.toSet()
                        val allSelected = allIds.isNotEmpty() &&
                            selectedIds.containsAll(allIds)
                        TextButton(
                            onClick = {
                                selectedIds = if (allSelected) emptySet() else allIds
                            },
                        ) {
                            Text(stringResource(R.string.tasks_selection_action_select_all))
                        }
                        TextButton(
                            onClick = { viewModel.archiveTasks(selectedIds) },
                        ) {
                            Text(stringResource(R.string.tasks_selection_action_archive))
                        }
                    },
                )
            } else {
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
            }
        },
        floatingActionButton = {
            // 选择模式下隐藏 FAB，避免与顶栏归档按钮冲突
            if (!inSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = onOpenNewTask,
                    text = { Text(stringResource(R.string.tasks_action_new)) },
                    // "+" 是视觉符号，不做国际化
                    icon = { Text("+") },
                )
            }
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
                    // 选择模式下隐藏：未分类不参与批量操作，保留会产生歧义。
                    if (!inSelectionMode && unclassifiedCount > 0) {
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
                            selected = brief.task.id in selectedIds,
                            onClick = {
                                if (inSelectionMode) {
                                    selectedIds = if (brief.task.id in selectedIds) {
                                        selectedIds - brief.task.id
                                    } else {
                                        selectedIds + brief.task.id
                                    }
                                } else {
                                    onOpenTask(brief.task.id)
                                }
                            },
                            onLongClick = {
                                selectedIds = selectedIds + brief.task.id
                            },
                            untitledLabel = untitled,
                            noMessagesLabel = noMessages,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 任务卡片：同时支持点击进详情与长按进入选择模式。
 * 选中时切换到 secondaryContainer 色，让选中态在长列表里一眼可辨。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskBriefCard(
    brief: TaskBrief,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    untitledLabel: String,
    noMessagesLabel: String,
) {
    val context = LocalContext.current
    val colors = if (selected) {
        CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    } else {
        CardDefaults.elevatedCardColors()
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = colors,
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
}
