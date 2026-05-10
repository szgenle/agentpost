package com.szgenle.agentpost.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.szgenle.agentpost.core.model.SendStatus
import com.szgenle.agentpost.core.model.TaskMessage
import com.szgenle.agentpost.core.ui.time.RelativeTime
import com.szgenle.agentpost.core.ui.R as CoreUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailRoute(
    onBack: () -> Unit,
    viewModel: TaskDetailViewModel = viewModel(factory = TaskDetailViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.error) {
        val e = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(e.asString(context))
        viewModel.consumeError()
    }

    val listState = rememberLazyListState()
    // 新消息到达时滚到底
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val untitled = stringResource(R.string.tasks_untitled)
                    val loading = stringResource(R.string.task_detail_loading)
                    Text(state.task?.title?.ifEmpty { untitled } ?: loading)
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(CoreUiR.string.common_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (state.messages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.task_detail_empty),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.messages, key = { it.id }) { msg ->
                            MessageBubble(
                                msg = msg,
                                onRetry = { viewModel.retrySend(msg.id) },
                            )
                        }
                    }
                }
            }

            ReplyBar(
                value = state.draftReply,
                onValueChange = viewModel::updateDraft,
                sending = state.sending,
                onSend = { text ->
                    viewModel.sendReply(text)
                },
            )
        }
    }
}

@Composable
private fun MessageBubble(
    msg: TaskMessage,
    onRetry: () -> Unit,
) {
    val isMine = !msg.fromAgent
    val failed = msg.sendStatus == SendStatus.FAILED
    val inFlight = msg.sendStatus == SendStatus.PENDING || msg.sendStatus == SendStatus.SENDING

    // FAILED 气泡用 errorContainer，在飞途（PENDING/SENDING）气泡深色半透，
    // 并标标识文案说明状态。
    val containerColor = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        isMine -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        isMine -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (isMine) {
                        stringResource(R.string.task_detail_bubble_me)
                    } else {
                        stringResource(R.string.task_detail_bubble_ai)
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = msg.body,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (msg.attachments.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.task_detail_attachments_count,
                            msg.attachments.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                // 状态行：时间 + （可选）状态标签 / 重试按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = RelativeTime.format(context, msg.sentAt),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    when {
                        inFlight -> Text(
                            text = stringResource(R.string.task_detail_status_sending),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        failed -> {
                            Text(
                                text = stringResource(R.string.task_detail_status_failed),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            TextButton(
                                onClick = onRetry,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 8.dp,
                                    vertical = 0.dp,
                                ),
                            ) {
                                Text(stringResource(R.string.task_detail_action_retry))
                            }
                        }
                    }
                }
                if (failed && !msg.sendError.isNullOrBlank()) {
                    Text(
                        text = msg.sendError.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReplyBar(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    onSend: (String) -> Unit,
) {
    // 本地托管 TextFieldValue 以保住光标位置。外部 value（来自 DataStore 异步回推）
    // 只作为"初始化种子"和"外部清空信号"——否则异步回路里过期的 String 会把
    // TextField 覆盖回去，导致新输入的字符被推到光标后面。
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    LaunchedEffect(value) {
        // 只在两种情况下同步外部 value 到本地：
        // 1) 本地空而外部非空：首次从 DataStore 加载草稿；
        // 2) 外部为空而本地非空：外部主动清空（发送成功后）。
        val localEmpty = field.text.isEmpty()
        val remoteEmpty = value.isEmpty()
        if (value != field.text && (localEmpty || remoteEmpty)) {
            field = TextFieldValue(value, TextRange(value.length))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = field,
            onValueChange = {
                field = it
                if (it.text != value) onValueChange(it.text)
            },
            placeholder = { Text(stringResource(R.string.task_detail_reply_placeholder)) },
            modifier = Modifier.weight(1f),
            maxLines = 4,
        )
        Button(
            onClick = { onSend(field.text) },
            enabled = !sending && field.text.isNotBlank(),
        ) {
            Text(
                if (sending) {
                    stringResource(CoreUiR.string.common_sending)
                } else {
                    stringResource(CoreUiR.string.common_send)
                },
            )
        }
    }
}
