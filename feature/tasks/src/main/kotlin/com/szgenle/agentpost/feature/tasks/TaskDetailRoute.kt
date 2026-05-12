package com.szgenle.agentpost.feature.tasks

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.szgenle.agentpost.core.ui.components.AppTopBar
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.szgenle.agentpost.core.data.SystemIds
import com.szgenle.agentpost.core.model.Attachment
import com.szgenle.agentpost.core.model.SendStatus
import com.szgenle.agentpost.core.model.TaskMessage
import com.szgenle.agentpost.core.ui.time.RelativeTime
import java.io.File
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
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(state.error) {
        val e = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(e.asString(context))
        viewModel.consumeError()
    }

    // 归档成功 → 自动返回列表页。
    LaunchedEffect(state.archivedEvent) {
        if (state.archivedEvent) {
            viewModel.consumeArchivedEvent()
            onBack()
        }
    }

    // 下载完成后由 ViewModel 丢出 pendingOpen，由此处就地 拉起系统 ACTION_VIEW 打开附件。
    // 用后马上 consumePendingOpen 清除，避免 配置变更 后重新触发。
    val noAppMsg = stringResource(R.string.attachment_no_app_to_open)
    LaunchedEffect(state.pendingOpen) {
        val p = state.pendingOpen ?: return@LaunchedEffect
        val ok = openAttachment(context, p.filePath, p.mimeType)
        if (!ok) {
            snackbarHostState.showSnackbar(noAppMsg)
        }
        viewModel.consumePendingOpen()
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
            AppTopBar(
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
                actions = {
                    val task = state.task
                    // 占位任务 / 已归档任务不暴露归档入口
                    val canArchive = task != null &&
                        task.id != SystemIds.UNCLASSIFIED_TASK_ID &&
                        !task.archived
                    if (canArchive) {
                        var menuExpanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuExpanded = true }) {
                            Text(
                                text = "⋮",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.task_detail_menu_archive)) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.archive()
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 仅让气泡容器 + 输入框响应软键盘，TopAppBar 由 Scaffold 固定在顶
                .imePadding()
                // 点击空白处主动收起键盘（OutlinedTextField 等子组件会优先消费触摸事件）
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    })
                },
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
                                downloadingKeys = state.downloadingKeys,
                                onRetry = { viewModel.retrySend(msg.id) },
                                onAttachmentClick = { index, att ->
                                    viewModel.onAttachmentClick(msg.id, index, att)
                                },
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

    // 加密 zip 密码请求 Dialog：VM 下发 zipPrompt 时弹出。
    state.zipPrompt?.let { prompt ->
        ZipPasswordPromptDialog(
            prompt = prompt,
            onSubmit = viewModel::submitZipPassword,
            onDismiss = viewModel::cancelZipPrompt,
        )
    }
}

@Composable
private fun MessageBubble(
    msg: TaskMessage,
    downloadingKeys: Set<String>,
    onRetry: () -> Unit,
    onAttachmentClick: (index: Int, attachment: Attachment) -> Unit,
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
                    Spacer(Modifier.height(6.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        msg.attachments.forEachIndexed { index, att ->
                            AttachmentRow(
                                attachment = att,
                                downloading = "${msg.id}:$index" in downloadingKeys,
                                onClick = { onAttachmentClick(index, att) },
                            )
                        }
                    }
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

/**
 * 气泡内的单行附件行：`文件名 · 大小` + 右侧动作按钮。
 *
 * 动作按钮三态：
 * - 已落盘 (`localPath` 不空且文件存在) → 打开
 * - 正在下载 (`downloading = true`) → 下载中…（禁用，防重复点击）
 * - 其他 → 下载
 *
 * 整个行点击事件托管到 [onClick]，ViewModel 再根据附件当前状态分发为
 * “直接打开” 或 “先下载再打开”。
 */
@Composable
private fun AttachmentRow(
    attachment: Attachment,
    downloading: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val hasLocal = !attachment.localPath.isNullOrBlank() &&
        runCatching { File(attachment.localPath!!).exists() }.getOrDefault(false)

    val actionText = when {
        downloading -> stringResource(R.string.attachment_action_downloading)
        hasLocal -> stringResource(R.string.attachment_action_open)
        else -> stringResource(R.string.attachment_action_download)
    }
    val sizeLabel = Formatter.formatShortFileSize(context, attachment.sizeBytes)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                R.string.attachment_item_label,
                attachment.fileName,
                sizeLabel,
            ),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onClick,
            enabled = !downloading,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 8.dp,
                vertical = 0.dp,
            ),
        ) {
            Text(actionText)
        }
    }
}

/**
 * 通过 FileProvider 将附件文件暂授权给外部 app 并发 ACTION_VIEW。
 * authority 统一是 `"${packageName}.fileprovider"`，给 manifest 里的 provider 配置保持同步。
 *
 * 返回值：`true` 表示已成功拉起某个 Activity；`false` 表示没有应用能处理该 MIME。
 */
private fun openAttachment(
    context: Context,
    filePath: String,
    mimeType: String,
): Boolean {
    val file = File(filePath)
    if (!file.exists()) return false
    val authority = "${context.packageName}.fileprovider"
    val uri = runCatching { FileProvider.getUriForFile(context, authority, file) }
        .getOrElse { return false }
    val safeMime = mimeType.ifBlank { "application/octet-stream" }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, safeMime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * 加密 zip 解压密码输入 Dialog：
 * - [ZipPasswordPrompt.wrongPassword] 决定提示文案是「首次输入」还是「主密码错」；
 * - 确认回调交给 [TaskDetailViewModel.submitZipPassword]，由 VM 再次调 [com.szgenle.agentpost.core.data.MailRepository.decryptZipAttachment]。
 */
@Composable
private fun ZipPasswordPromptDialog(
    prompt: ZipPasswordPrompt,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val desc = if (prompt.wrongPassword) {
        stringResource(R.string.attachment_zip_prompt_desc_wrong, prompt.fileName)
    } else {
        stringResource(R.string.attachment_zip_prompt_desc, prompt.fileName)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.attachment_zip_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = desc, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.attachment_zip_prompt_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(password) },
                enabled = password.isNotEmpty(),
            ) { Text(stringResource(R.string.attachment_zip_prompt_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreUiR.string.common_cancel))
            }
        },
    )
}
