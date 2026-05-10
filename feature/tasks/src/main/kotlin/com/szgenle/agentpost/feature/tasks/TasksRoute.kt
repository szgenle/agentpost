package com.szgenle.agentpost.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.szgenle.agentpost.core.data.TaskBrief
import com.szgenle.agentpost.core.ui.UiText
import com.szgenle.agentpost.core.ui.time.RelativeTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _syncError = MutableStateFlow<UiText?>(null)
    val syncError: StateFlow<UiText?> = _syncError.asStateFlow()

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
    viewModel: TasksViewModel = viewModel(factory = TasksViewModel.Factory),
) {
    val briefs by viewModel.briefs.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val syncError by viewModel.syncError.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(syncError) {
        val e = syncError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(e.asString(context))
        viewModel.consumeSyncError()
    }

    val untitled = stringResource(R.string.tasks_untitled)
    val noMessages = stringResource(R.string.tasks_item_no_messages)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tasks_title)) },
                actions = {
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.tasks_action_settings))
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
            if (briefs.isEmpty()) {
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
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(briefs, key = { it.task.id }) { brief ->
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
                                if (brief.unreadCount > 0) {
                                    Badge { Text(brief.unreadCount.toString()) }
                                }
                            },
                            modifier = Modifier.clickable { onOpenTask(brief.task.id) },
                        )
                    }
                }
            }
        }
    }
}
