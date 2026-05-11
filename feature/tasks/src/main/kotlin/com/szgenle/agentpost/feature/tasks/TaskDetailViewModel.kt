package com.szgenle.agentpost.feature.tasks

import android.webkit.MimeTypeMap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szgenle.agentpost.core.common.zip.DecryptResult
import com.szgenle.agentpost.core.common.zip.ZipDecryptor
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.core.data.MailRepository
import com.szgenle.agentpost.core.datastore.AppPreferences
import com.szgenle.agentpost.core.model.Attachment
import com.szgenle.agentpost.core.model.Task
import com.szgenle.agentpost.core.model.TaskMessage
import com.szgenle.agentpost.core.ui.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

const val TASK_ID_ARG = "taskId"

data class TaskDetailUiState(
    val task: Task? = null,
    val messages: List<TaskMessage> = emptyList(),
    val draftReply: String = "",
    val sending: Boolean = false,
    val isRefreshing: Boolean = false,
    /** 正在下载中的附件 key 集合，key 格式 `"$messageId:$attachmentIndex"`。 */
    val downloadingKeys: Set<String> = emptySet(),
    /**
     * 一次性的打开附件请求。UI 消费后调用 [TaskDetailViewModel.consumePendingOpen] 清空，
     * 避免 配置变更 后重发。
     */
    val pendingOpen: PendingOpen? = null,
    /** 加密 zip 需要用户手输密码时的提示态，null 表示无待确认。 */
    val zipPrompt: ZipPasswordPrompt? = null,
    val error: UiText? = null,
)

/** 打开附件需要的最小信息：本地路径 + MIME。 */
data class PendingOpen(
    val filePath: String,
    val mimeType: String,
)

/**
 * 加密 zip 解密密码提示的上下文。
 *
 * - [messageId] / [attIndex]：定位解压目录（跟cacheDir/decrypted/{messageId}/{attIndex}保持一致）；
 * - [srcPath]：待解密 zip 文件绝对路径；
 * - [fileName]：Dialog 文案展示的附件名；
 * - [wrongPassword]：true 表示试过主密码但密码错（UI 换更严重的提示文案）。
 */
data class ZipPasswordPrompt(
    val messageId: String,
    val attIndex: Int,
    val srcPath: String,
    val fileName: String,
    val wrongPassword: Boolean,
)

class TaskDetailViewModel(
    private val repo: MailRepository,
    private val prefs: AppPreferences,
    private val taskId: String,
) : ViewModel() {

    private val taskFlow = MutableStateFlow<Task?>(null)
    private val transient = MutableStateFlow(TransientState())

    val uiState: StateFlow<TaskDetailUiState> = combine(
        taskFlow,
        repo.observeMessages(taskId),
        prefs.observeDraftReply(taskId),
        transient,
    ) { task, messages, draft, t ->
        TaskDetailUiState(
            task = task,
            messages = messages,
            draftReply = draft,
            sending = t.sending,
            isRefreshing = t.refreshing,
            downloadingKeys = t.downloadingKeys,
            pendingOpen = t.pendingOpen,
            zipPrompt = t.zipPrompt,
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

    /** 输入框边输边持久化。DataStore 会自动合并并发写入。 */
    fun updateDraft(text: String) {
        viewModelScope.launch { prefs.setDraftReply(taskId, text) }
    }

    /**
     * 发送回复。新语义：
     * - 点击后立即清空草稿 + 重置输入框
     * - repo 内部先落 PENDING 占位（UI 立刻看到气泡）再异步发送
     * - 失败不弹 snackbar，由气泡的 FAILED 状态 + 重试按钮反馈
     */
    fun sendReply(body: String) {
        if (body.isBlank()) return
        viewModelScope.launch {
            prefs.clearDraftReply(taskId)
            transient.value = transient.value.copy(sending = true)
            // repo.sendReply 只在"前置校验失败"（无账号/Task 不存在）时 Result.failure
            val r = repo.sendReply(taskId = taskId, body = body, attachments = emptyList())
            transient.value = r.fold(
                onSuccess = { transient.value.copy(sending = false, error = null) },
                onFailure = { e ->
                    val msg = e.message
                    val uiText = if (!msg.isNullOrBlank()) {
                        UiText.Dynamic(msg)
                    } else {
                        UiText.Resource(R.string.task_detail_send_failed)
                    }
                    transient.value.copy(sending = false, error = uiText)
                },
            )
        }
    }

    /** 重试一条 FAILED 的消息（按本地 id 定位）。 */
    fun retrySend(localMessageId: String) {
        viewModelScope.launch {
            val r = repo.retrySend(localMessageId)
            if (r.isFailure) {
                val msg = r.exceptionOrNull()?.message
                val uiText = if (!msg.isNullOrBlank()) {
                    UiText.Dynamic(msg)
                } else {
                    UiText.Resource(R.string.task_detail_send_failed)
                }
                transient.value = transient.value.copy(error = uiText)
            }
        }
    }

    /** 详情页手动下拉刷新：触发一次 [MailRepository.syncInbox]，失败走同一 error 管道。 */
    fun refresh() {
        if (transient.value.refreshing) return
        viewModelScope.launch {
            transient.value = transient.value.copy(refreshing = true)
            val r = repo.syncInbox()
            transient.value = r.fold(
                onSuccess = { transient.value.copy(refreshing = false) },
                onFailure = { e ->
                    val msg = e.message
                    val uiText = if (!msg.isNullOrBlank()) {
                        UiText.Dynamic(msg)
                    } else {
                        UiText.Resource(R.string.tasks_sync_failed)
                    }
                    transient.value.copy(refreshing = false, error = uiText)
                },
            )
        }
    }

    fun consumeError() {
        transient.value = transient.value.copy(error = null)
    }

    /**
     * 处理附件点击：
     * - 已有 [Attachment.localPath] 且文件存在→走加密判断后直接打开或请求密码
     * - 有 `imapUid` 但还没落盘→触发下载，完成后同样走加密判断
     * - 本机发出的附件（无 imapUid 也无 localPath）→返回不可用提示
     *
     * 同一条附件正在下载时重复点击会被忽略（降低对 SMTP/IMAP 无谓压力）。
     */
    fun onAttachmentClick(messageLocalId: String, index: Int, att: Attachment) {
        // 已落盘：先判断是否加密 zip，再分流打开
        val localPath = att.localPath
        if (!localPath.isNullOrBlank() && File(localPath).exists()) {
            handleAttachmentReady(messageLocalId, index, File(localPath), att)
            return
        }
        // 本机发出的附件没有下载信息
        if (att.imapUid == null || att.partIndex == null) {
            transient.value = transient.value.copy(
                error = UiText.Resource(R.string.attachment_not_downloadable),
            )
            return
        }
        val key = "$messageLocalId:$index"
        if (key in transient.value.downloadingKeys) return

        viewModelScope.launch {
            transient.value = transient.value.copy(
                downloadingKeys = transient.value.downloadingKeys + key,
            )
            val r = repo.downloadAttachment(messageLocalId, index)
            transient.value = transient.value.copy(
                downloadingKeys = transient.value.downloadingKeys - key,
            )
            r.fold(
                onSuccess = { path -> handleAttachmentReady(messageLocalId, index, File(path), att) },
                onFailure = { e ->
                    val msg = e.message
                    val uiText = if (!msg.isNullOrBlank()) {
                        UiText.Dynamic(msg)
                    } else {
                        UiText.Resource(R.string.attachment_download_failed)
                    }
                    transient.value = transient.value.copy(error = uiText)
                },
            )
        }
    }

    /**
     * 附件已落盘 / 刚下载完成后的统一入口：
     * - 非加密 zip → 直接 [PendingOpen] 交给 UI 调 FileProvider 打开；
     * - 加密 zip 且已配置主密码 → 直接解密尝试，错了才弹提示；
     * - 加密 zip 但未配置主密码 → 直接弹提示（wrongPassword=false）。
     */
    private fun handleAttachmentReady(messageId: String, index: Int, src: File, att: Attachment) {
        viewModelScope.launch {
            val encrypted = withContext(Dispatchers.IO) { ZipDecryptor.isEncryptedZip(src) }
            if (!encrypted) {
                transient.value = transient.value.copy(
                    pendingOpen = PendingOpen(src.absolutePath, att.mimeType),
                )
                return@launch
            }
            val master = repo.getZipPassword()
            if (master.isNullOrEmpty()) {
                transient.value = transient.value.copy(
                    zipPrompt = ZipPasswordPrompt(
                        messageId = messageId,
                        attIndex = index,
                        srcPath = src.absolutePath,
                        fileName = att.fileName,
                        wrongPassword = false,
                    ),
                )
                return@launch
            }
            tryDecryptAndOpen(messageId, index, src, att.fileName, master)
        }
    }

    /**
     * 用 [password] 解密 [src]，成功则打开第一个解压产物；密码错弹 Dialog。
     */
    private suspend fun tryDecryptAndOpen(
        messageId: String,
        index: Int,
        src: File,
        fileName: String,
        password: String,
    ) {
        val result = repo.decryptZipAttachment(
            messageId = messageId,
            attIndex = index,
            src = src,
            password = password,
        )
        transient.value = when (result) {
            is DecryptResult.Success -> {
                val first = result.files.firstOrNull()
                if (first == null) {
                    transient.value.copy(
                        zipPrompt = null,
                        error = UiText.Resource(R.string.attachment_decrypt_empty),
                    )
                } else {
                    transient.value.copy(
                        zipPrompt = null,
                        pendingOpen = PendingOpen(first.absolutePath, guessMimeType(first)),
                    )
                }
            }
            is DecryptResult.WrongPassword -> transient.value.copy(
                zipPrompt = ZipPasswordPrompt(
                    messageId = messageId,
                    attIndex = index,
                    srcPath = src.absolutePath,
                    fileName = fileName,
                    wrongPassword = true,
                ),
            )
            is DecryptResult.Failure -> {
                val msg = result.error.message
                val uiText = if (!msg.isNullOrBlank()) {
                    UiText.Dynamic(msg)
                } else {
                    UiText.Resource(R.string.attachment_decrypt_failed)
                }
                transient.value.copy(zipPrompt = null, error = uiText)
            }
        }
    }

    /** 用户在 Dialog 里输入一次性密码后提交，空串走 [cancelZipPrompt]。 */
    fun submitZipPassword(password: String) {
        if (password.isEmpty()) {
            cancelZipPrompt()
            return
        }
        val prompt = transient.value.zipPrompt ?: return
        viewModelScope.launch {
            tryDecryptAndOpen(
                messageId = prompt.messageId,
                index = prompt.attIndex,
                src = File(prompt.srcPath),
                fileName = prompt.fileName,
                password = password,
            )
        }
    }

    fun cancelZipPrompt() {
        transient.value = transient.value.copy(zipPrompt = null)
    }

    fun consumePendingOpen() {
        transient.value = transient.value.copy(pendingOpen = null)
    }

    /** 根据文件名后缀推 MIME，拿不到则回落 `application/octet-stream`。 */
    private fun guessMimeType(file: File): String {
        val ext = file.extension.lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private data class TransientState(
        val sending: Boolean = false,
        val refreshing: Boolean = false,
        val downloadingKeys: Set<String> = emptySet(),
        val pendingOpen: PendingOpen? = null,
        val zipPrompt: ZipPasswordPrompt? = null,
        val error: UiText? = null,
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val handle: SavedStateHandle = createSavedStateHandle()
                val taskId: String = checkNotNull(handle[TASK_ID_ARG]) {
                    "taskId arg required for TaskDetailViewModel"
                }
                TaskDetailViewModel(
                    repo = AppServiceLocator.mailRepository,
                    prefs = AppServiceLocator.appPreferences,
                    taskId = taskId,
                )
            }
        }
    }
}
