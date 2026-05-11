package com.szgenle.agentpost.core.data

import android.content.Context
import com.szgenle.agentpost.core.common.logging.AppLog
import com.szgenle.agentpost.core.common.mail.SubjectNormalizer
import com.szgenle.agentpost.core.common.security.CredentialsVault
import com.szgenle.agentpost.core.common.zip.DecryptResult
import com.szgenle.agentpost.core.common.zip.ZipDecryptor
import com.szgenle.agentpost.core.data.internal.TaskRouter
import com.szgenle.agentpost.core.database.dao.AccountDao
import com.szgenle.agentpost.core.database.dao.TaskDao
import com.szgenle.agentpost.core.database.dao.TaskMessageDao
import com.szgenle.agentpost.core.datastore.AppPreferences
import com.szgenle.agentpost.core.mail.MailCredentials
import com.szgenle.agentpost.core.mail.MailFetcher
import com.szgenle.agentpost.core.mail.MailSender
import com.szgenle.agentpost.core.mail.OutgoingAttachment
import com.szgenle.agentpost.core.mail.OutgoingMail
import com.szgenle.agentpost.core.model.Account
import com.szgenle.agentpost.core.model.AccountType
import com.szgenle.agentpost.core.model.Attachment
import com.szgenle.agentpost.core.model.SendStatus
import com.szgenle.agentpost.core.model.Task
import com.szgenle.agentpost.core.model.TaskMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 应用级邮件仓库。串起 mail 协议层 + 数据库 + 凭据保险箱。
 *
 * 唯一对上层（ViewModel / Worker）暴露的类，构造由 [com.szgenle.agentpost.core.data.AppServiceLocator] 完成。
 */
class MailRepository internal constructor(
    private val appContext: Context,
    private val accountDao: AccountDao,
    private val taskDao: TaskDao,
    private val messageDao: TaskMessageDao,
    private val vault: CredentialsVault,
    private val prefs: AppPreferences,
    private val sender: MailSender,
    private val fetcher: MailFetcher,
) {

    // ============================================================
    // 账户配置
    // ============================================================

    fun observeSelfAccount(): Flow<Account?> = accountDao.observeFirstByType(AccountType.SELF)

    fun observeAgentAccount(): Flow<Account?> = accountDao.observeFirstByType(AccountType.AGENT)

    suspend fun getSelfAccount(): Account? = accountDao.getFirstByType(AccountType.SELF)

    suspend fun getAgentAccount(): Account? = accountDao.getFirstByType(AccountType.AGENT)

    /**
     * 判断 SELF 账户是否已经存过密码（Vault 中是否有对应条目）。
     *
     * 用于 UI 区分"只设了身份、尚未配置密码"与"完整设置过"两种状态。
     */
    suspend fun hasSelfPassword(): Boolean {
        val existing = accountDao.getFirstByType(AccountType.SELF) ?: return false
        return vault.contains(existing.credentialKey)
    }

    // ============================================================
    // 加密 zip 附件主密码
    // ============================================================
    // 密码与 Account 凭据共用同一个 EncryptedSharedPreferences。
    // feature 层不直接触 vault，通过 Repository 开放最小 API。

    fun hasZipPassword(): Boolean = vault.contains(CredentialsVault.ZIP_MASTER_KEY)

    fun getZipPassword(): String? = vault.get(CredentialsVault.ZIP_MASTER_KEY)

    fun setZipPassword(password: String) {
        vault.put(CredentialsVault.ZIP_MASTER_KEY, password)
    }

    fun clearZipPassword() {
        vault.remove(CredentialsVault.ZIP_MASTER_KEY)
    }

    /**
     * 尝试用 [password] 解密 [src]。
     *
     * 解压级路径：`cacheDir/decrypted/{messageId}/{attIndex}/`，解压前先递归清一遍避免老残留。
     * App 启动时 `AgentPostApp.onCreate` 会整体清除 `cacheDir/decrypted/`，再架上本方法的单次清除，
     * 保证解压结果不跨生命周期泄露。
     */
    suspend fun decryptZipAttachment(
        messageId: String,
        attIndex: Int,
        src: File,
        password: String,
    ): DecryptResult {
        val outputDir = File(appContext.cacheDir, "decrypted/$messageId/$attIndex")
        runCatching { outputDir.deleteRecursively() }
        return ZipDecryptor.decrypt(src = src, outputDir = outputDir, password = password)
    }

    /**
     * 仅保存 SELF 的身份信息（displayName + email），不涉及 IMAP/SMTP/密码。
     *
     * 允许在 SELF 尚未建立时创建一条仅含身份的占位记录，host/port/ssl 留空，
     * 后续由"邮箱设置"入口补齐。用于破除"我"与"邮箱设置"的先后循环依赖。
     */
    suspend fun saveSelfIdentity(displayName: String, email: String) {
        val existing = accountDao.getFirstByType(AccountType.SELF)
        val id = existing?.id ?: UUID.randomUUID().toString()
        val credentialKey = existing?.credentialKey ?: "self_$id"
        accountDao.upsert(
            Account(
                id = id,
                type = AccountType.SELF,
                displayName = displayName,
                email = email,
                imapHost = existing?.imapHost ?: "",
                imapPort = existing?.imapPort ?: 993,
                imapUseSsl = existing?.imapUseSsl ?: true,
                smtpHost = existing?.smtpHost ?: "",
                smtpPort = existing?.smtpPort ?: 587,
                smtpUseStartTls = existing?.smtpUseStartTls ?: true,
                credentialKey = credentialKey,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
        )
    }

    /**
     * 保存（或覆盖）SELF 账户完整配置。密码写入 [CredentialsVault]，Account 表只存 credentialKey 引用。
     *
     * password 语义：
     * - 非 null：覆盖写入 Vault；
     * - null：保留原有凭据（"留空=不改"）。此时 Vault 中必须已存在对应凭据，否则抛异常。
     */
    suspend fun saveSelfAccount(
        displayName: String,
        email: String,
        imapHost: String,
        imapPort: Int,
        imapUseSsl: Boolean,
        smtpHost: String,
        smtpPort: Int,
        smtpUseStartTls: Boolean,
        password: String?,
    ) {
        val existing = accountDao.getFirstByType(AccountType.SELF)
        val id = existing?.id ?: UUID.randomUUID().toString()
        val credentialKey = existing?.credentialKey ?: "self_$id"
        if (password == null && !vault.contains(credentialKey)) {
            throw IllegalArgumentException("Saving SELF account requires a password when none is stored.")
        }
        if (password != null) {
            vault.put(credentialKey, password)
        }
        accountDao.upsert(
            Account(
                id = id,
                type = AccountType.SELF,
                displayName = displayName,
                email = email,
                imapHost = imapHost,
                imapPort = imapPort,
                imapUseSsl = imapUseSsl,
                smtpHost = smtpHost,
                smtpPort = smtpPort,
                smtpUseStartTls = smtpUseStartTls,
                credentialKey = credentialKey,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
        )
    }

    /**
     * 保存（或覆盖）AGENT 账户（家里 AI 的邮箱）。MVP 阶段 AGENT 只当"发件目标地址"用，
     * 不需要 IMAP/SMTP 凭据——相关字段填空。
     */
    suspend fun saveAgentAccount(displayName: String, email: String) {
        val existing = accountDao.getFirstByType(AccountType.AGENT)
        val id = existing?.id ?: UUID.randomUUID().toString()
        accountDao.upsert(
            Account(
                id = id,
                type = AccountType.AGENT,
                displayName = displayName,
                email = email,
                imapHost = "",
                imapPort = 0,
                imapUseSsl = false,
                smtpHost = "",
                smtpPort = 0,
                smtpUseStartTls = false,
                credentialKey = existing?.credentialKey ?: "",
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
        )
    }

    // ============================================================
    // 发送
    // ============================================================

    /** 新建任务：发首封邮件并记录线程锚点。返回新建的 taskId。 */
    suspend fun sendNewTask(
        title: String,
        body: String,
        attachments: List<OutgoingAttachment> = emptyList(),
    ): Result<String> = runCatching {
        val self = requireSelf()
        val agent = requireAgent()
        val creds = self.toCredentials()

        val outgoing = OutgoingMail(
            toAddress = agent.email,
            subject = title,
            body = body,
            attachments = attachments,
        )
        val messageId = sender.send(creds, outgoing)

        ensureUnclassifiedTask(self.id)
        val now = System.currentTimeMillis()
        val taskId = UUID.randomUUID().toString()
        val normalizedTitle = SubjectNormalizer.normalize(title).ifEmpty { title }
        taskDao.upsert(
            Task(
                id = taskId,
                rootMessageId = messageId,
                title = normalizedTitle,
                agentAccountId = agent.id,
                createdAt = now,
                lastActivityAt = now,
            )
        )
        messageDao.upsert(
            TaskMessage(
                id = UUID.randomUUID().toString(),
                externalMessageId = messageId,
                taskId = taskId,
                fromAgent = false,
                subject = title,
                body = body,
                attachments = attachments.toAttachmentsMeta(),
                inReplyTo = null,
                sentAt = now,
                isRead = true,
                sendStatus = SendStatus.SENT,
                sendError = null,
            )
        )
        taskId
    }

    /**
     * 回复一个已有 Task。
     *
     * 发送流程采用状态机，前置校验失败（无账户 / Task 不存在 / 未归类）招 Result.failure；
     * SMTP 失败不抛，只会在消息行写入 [SendStatus.FAILED] + [TaskMessage.sendError]，
     * UI 通过气泡的红色态和重试按钮提示。
     *
     * 步骤：
     * 1. 前置校验 → 拼 References 链
     * 2. 记录一条 `PENDING` 占位（UI 立刻看到气泡），同步 touch Task
     * 3. [dispatchSend] 内部转 SENDING、调 SMTP、结果回填
     */
    suspend fun sendReply(
        taskId: String,
        body: String,
        attachments: List<OutgoingAttachment> = emptyList(),
    ): Result<Unit> = runCatching {
        val self = requireSelf()
        val agent = requireAgent()
        val task = requireNotNull(taskDao.getById(taskId)) { "Task not found: $taskId" }
        require(taskId != SystemIds.UNCLASSIFIED_TASK_ID) { "Cannot reply under unclassified placeholder" }

        val creds = self.toCredentials()
        val replySubject = ensureReplyPrefix(task.title)

        // References 链 = 已收到回执的历史消息（按时间升序）
        val existingMessageIds = messageDao.externalMessageIdsByTaskOrderBySentAtAsc(taskId)
        val lastMessageId = existingMessageIds.lastOrNull()

        val now = System.currentTimeMillis()
        val localId = UUID.randomUUID().toString()
        messageDao.upsert(
            TaskMessage(
                id = localId,
                externalMessageId = null,
                taskId = taskId,
                fromAgent = false,
                subject = replySubject,
                body = body,
                attachments = attachments.toAttachmentsMeta(),
                inReplyTo = lastMessageId,
                sentAt = now,
                isRead = true,
                sendStatus = SendStatus.PENDING,
                sendError = null,
            )
        )
        taskDao.touch(taskId, now)

        dispatchSend(
            localId = localId,
            taskId = taskId,
            toAddress = agent.email,
            subject = replySubject,
            body = body,
            attachments = attachments,
            inReplyTo = lastMessageId,
            references = existingMessageIds,
            creds = creds,
        )
    }

    /**
     * 重试一条 [SendStatus.FAILED] 的消息。
     *
     * MVP 限制：重试不携带附件（原始字节流未持久化，只存了 [Attachment] 元数据）。
     * 重试时重新查询 References 链，以防期间收到了新的回执。
     */
    suspend fun retrySend(localId: String): Result<Unit> = runCatching {
        val msg = requireNotNull(messageDao.getById(localId)) { "Message not found: $localId" }
        require(msg.sendStatus == SendStatus.FAILED) {
            "Only FAILED messages can be retried; current=${msg.sendStatus}"
        }
        val self = requireSelf()
        val agent = requireAgent()
        val creds = self.toCredentials()

        val existingMessageIds = messageDao.externalMessageIdsByTaskOrderBySentAtAsc(msg.taskId)
        val lastMessageId = existingMessageIds.lastOrNull()

        dispatchSend(
            localId = localId,
            taskId = msg.taskId,
            toAddress = agent.email,
            subject = msg.subject,
            body = msg.body,
            attachments = emptyList(),
            inReplyTo = lastMessageId,
            references = existingMessageIds,
            creds = creds,
        )
    }

    /**
     * 内部共用发送调度：SENDING → SMTP → SENT / FAILED。
     * 所有异常在这里吸掉并落库为 FAILED，不再向上抛。
     */
    private suspend fun dispatchSend(
        localId: String,
        taskId: String,
        toAddress: String,
        subject: String,
        body: String,
        attachments: List<OutgoingAttachment>,
        inReplyTo: String?,
        references: List<String>,
        creds: MailCredentials,
    ) {
        val startNow = System.currentTimeMillis()
        messageDao.updateSendStatus(
            id = localId,
            status = SendStatus.SENDING,
            externalMessageId = null,
            sendError = null,
            sentAt = startNow,
        )
        try {
            val outgoing = OutgoingMail(
                toAddress = toAddress,
                subject = subject,
                body = body,
                attachments = attachments,
                inReplyToMessageId = inReplyTo,
                references = references,
            )
            val messageId = sender.send(creds, outgoing)
            val endNow = System.currentTimeMillis()
            messageDao.updateSendStatus(
                id = localId,
                status = SendStatus.SENT,
                externalMessageId = messageId,
                sendError = null,
                sentAt = endNow,
            )
            taskDao.touch(taskId, endNow)
        } catch (e: Exception) {
            val endNow = System.currentTimeMillis()
            // Jakarta Mail 的异常（AuthenticationFailedException / MessagingException 等）
            // 往往要看 cause 链才能定位到真正的 SMTP 错误，这里把完整堆栈写进 logcat/rolling file；
            // DB 里只留 message 摘要给 UI 展示。
            AppLog.w(TAG, "SMTP send failed taskId=$taskId localId=$localId", e)
            messageDao.updateSendStatus(
                id = localId,
                status = SendStatus.FAILED,
                externalMessageId = null,
                sendError = e.message ?: e.javaClass.simpleName,
                sentAt = endNow,
            )
        }
    }

    // ============================================================
    // 同步
    // ============================================================

    /**
     * 从 SELF 邮箱拉新邮件；返回 [SyncResult]，内含本次入库的新消息数量与按任务归组的摘要，
     * 给通知层按 Task 分组推通知用。
     */
    suspend fun syncInbox(): Result<SyncResult> = runCatching {
        val self = requireSelf()
        val agent = requireAgent()
        val creds = self.toCredentials()
        val sinceUid = prefs.getLastSyncUid(self.id)

        val incomings = fetcher.fetchNew(creds, sinceUid)
        if (incomings.isEmpty()) return@runCatching SyncResult(totalNew = 0, perTask = emptyList())

        ensureUnclassifiedTask(self.id)
        val router = TaskRouter(taskDao, messageDao)
        // 保留插入顺序，同时便于按 taskId 累积
        val perTaskBuckets = linkedMapOf<String, MutableTaskSummary>()
        var inserted = 0
        for (mail in incomings) {
            if (messageDao.existsByExternalMessageId(mail.messageId)) continue
            val targetTaskId = router.route(mail, agent.id)
            messageDao.upsert(
                TaskMessage(
                    id = UUID.randomUUID().toString(),
                    externalMessageId = mail.messageId,
                    taskId = targetTaskId,
                    fromAgent = true,
                    subject = mail.subject,
                    body = mail.body,
                    attachments = mail.attachmentParts.map {
                        Attachment(
                            fileName = it.fileName,
                            mimeType = it.mimeType,
                            sizeBytes = it.sizeBytes,
                            localPath = null,
                            imapUid = mail.imapUid,
                            partIndex = it.partIndex,
                        )
                    },
                    inReplyTo = mail.inReplyTo,
                    sentAt = mail.sentAt,
                    isRead = mail.seen,
                    sendStatus = SendStatus.SENT,
                    sendError = null,
                )
            )
            if (targetTaskId != SystemIds.UNCLASSIFIED_TASK_ID) {
                taskDao.touch(targetTaskId, mail.sentAt)
            }
            inserted++

            // 统计通知摘要：按任务累积条数，并记下最新一条的预览
            val bucket = perTaskBuckets.getOrPut(targetTaskId) { MutableTaskSummary() }
            bucket.newCount += 1
            if (mail.sentAt >= bucket.latestSentAt) {
                bucket.latestSentAt = mail.sentAt
                bucket.latestPreview = previewOf(mail.body)
                bucket.latestSubject = mail.subject
            }
        }
        val newMaxUid = incomings.maxOf { it.imapUid }
        if (newMaxUid > sinceUid) {
            prefs.setLastSyncUid(self.id, newMaxUid)
        }

        // 解析每个 bucket 的任务标题（未归类不给标题）
        val perTask = perTaskBuckets.map { (taskId, s) ->
            val title = if (taskId == SystemIds.UNCLASSIFIED_TASK_ID) {
                ""
            } else {
                taskDao.getById(taskId)?.title.orEmpty()
            }
            TaskNewMessages(
                taskId = taskId,
                taskTitle = title,
                newCount = s.newCount,
                latestPreview = s.latestPreview,
                latestSubject = s.latestSubject,
                latestSentAt = s.latestSentAt,
            )
        }
        SyncResult(totalNew = inserted, perTask = perTask)
    }

    private class MutableTaskSummary(
        var newCount: Int = 0,
        var latestSentAt: Long = Long.MIN_VALUE,
        var latestPreview: String = "",
        var latestSubject: String = "",
    )

    /** 取邮件正文的单行摘要：第一段非空行，最多 [limit] 字。 */
    private fun previewOf(body: String, limit: Int = 160): String {
        val firstLine = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return ""
        return if (firstLine.length <= limit) firstLine else firstLine.substring(0, limit) + "…"
    }

    // ============================================================
    // 附件
    // ============================================================

    /**
     * 下载指定消息里第 [attachmentIndex] 个附件到 app 私有 filesDir，
     * 成功后把 [Attachment.localPath] 回写进该消息，返回落盘的绝对路径。
     *
     * 实现策略：只对来信附件（`imapUid`/`partIndex` 都非空）生效；本机发出的附件没有
     * 远端定位信息，直接返回 failure。下载用 SELF 账户的凭据重新打开 IMAP，按 UID +
     * walkParts 顺序序号定位到 part 再读字节流。
     *
     * 幂等性：若对应 localPath 已存在且文件可读，直接返回已有路径；否则总是覆盖写入。
     *
     * 落盘位置：`filesDir/attachments/{messageLocalId}/{fileName}`，后续通过 FileProvider
     * 转成 content:// URI 再交给系统打开。
     */
    suspend fun downloadAttachment(
        messageLocalId: String,
        attachmentIndex: Int,
    ): Result<String> = runCatching {
        val msg = requireNotNull(messageDao.getById(messageLocalId)) {
            "Message not found: $messageLocalId"
        }
        val att = msg.attachments.getOrNull(attachmentIndex)
            ?: error("Attachment index out of bounds: $attachmentIndex")
        // 已下载过，直接返回
        att.localPath?.let { path ->
            if (File(path).exists()) return@runCatching path
        }
        val uid = att.imapUid ?: error("附件缺少 imapUid，无法下载")
        val pi = att.partIndex ?: error("附件缺少 partIndex，无法下载")
        val self = requireSelf()
        val creds = self.toCredentials()

        val dir = File(appContext.filesDir, "attachments/$messageLocalId")
        val target = withContext(Dispatchers.IO) {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, sanitizeFileName(att.fileName))
            fetcher.fetchAttachment(creds, uid, pi).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file
        }
        // 回写 localPath
        val updatedAttachments = msg.attachments.mapIndexed { i, a ->
            if (i == attachmentIndex) a.copy(localPath = target.absolutePath) else a
        }
        messageDao.upsert(msg.copy(attachments = updatedAttachments))
        target.absolutePath
    }

    /**
     * 简单的文件名清洗：把路径分隔符等不安全字符替换为下划线，避免 IMAP 上的文件名
     * 带斜杠/反斜杠导致落盘时穿越目录。
     */
    private fun sanitizeFileName(raw: String): String {
        val trimmed = raw.trim().ifEmpty { "attachment" }
        return trimmed.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
    }

    // ============================================================
    // 观察 / 读
    // ============================================================

    /** 观察所有非占位的任务，按 lastActivityAt 倒序。 */
    fun observeTasks(): Flow<List<Task>> =
        taskDao.observeActive().map { list -> list.filter { it.id != SystemIds.UNCLASSIFIED_TASK_ID } }

    fun observeMessages(taskId: String): Flow<List<TaskMessage>> =
        messageDao.observeByTaskId(taskId)

    suspend fun getTask(taskId: String): Task? = taskDao.getById(taskId)

    fun observeUnclassifiedMessages(): Flow<List<TaskMessage>> =
        messageDao.observeByTaskId(SystemIds.UNCLASSIFIED_TASK_ID)

    fun observeUnreadCount(taskId: String): Flow<Int> = messageDao.observeUnreadCount(taskId)

    /**
     * 列表页用：每个 Task 的简要视图（最新消息摘要 + 最新消息时间 + 未读数）。
     * 过滤掉 `__UNCLASSIFIED__` 占位任务，同 [observeTasks]。
     */
    fun observeTaskBriefs(): Flow<List<TaskBrief>> =
        taskDao.observeActiveBriefs().map { rows ->
            rows.asSequence()
                .filter { it.task.id != SystemIds.UNCLASSIFIED_TASK_ID }
                .map { row ->
                    TaskBrief(
                        task = row.task,
                        lastMessagePreview = previewOf(row.lastBody ?: "", limit = 60),
                        lastMessageAt = row.lastSentAt ?: row.task.lastActivityAt,
                        unreadCount = row.unreadCount,
                    )
                }
                .toList()
        }

    suspend fun markRead(localMessageId: String) = messageDao.markRead(localMessageId)

    suspend fun markTaskRead(taskId: String) = messageDao.markAllReadInTask(taskId)

    /** 把未归类的某条消息手动指派到 Task。参数为消息的本地 UUID 主键。 */
    suspend fun assignMessageToTask(localMessageId: String, targetTaskId: String) {
        val msg = messageDao.getById(localMessageId) ?: return
        require(targetTaskId != SystemIds.UNCLASSIFIED_TASK_ID) {
            "targetTaskId cannot be unclassified placeholder"
        }
        messageDao.upsert(msg.copy(taskId = targetTaskId))
        taskDao.touch(targetTaskId, msg.sentAt)
    }

    // ============================================================
    // 崩溃上报（给自己发邮件，不落 Task/TaskMessage）
    // ============================================================

    /**
     * 通过 SELF 的 SMTP 通道给自己发一封崩溃报告邮件。
     *
     * 特性：
     *  - 不入库（不生成 Task、不生成 TaskMessage），避免污染任务池
     *  - SELF 未配置、凭据缺失、SMTP 失败…统一以 [Result.failure] 返回，
     *    Crash 路径不应因上报失败再抛异常；原始异常/原因放在 Result 里，供 UI 反馈
     *  - 失败原因同时写入 AppLog（下次启动仍可从日志追查）
     */
    suspend fun sendCrashReport(
        subject: String,
        body: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val self = accountDao.getFirstByType(AccountType.SELF)
        if (self == null) {
            AppLog.w(TAG, "crash-report: SELF account missing, skip")
            return@withContext Result.failure(IllegalStateException("SELF account missing"))
        }
        if (!vault.contains(self.credentialKey)) {
            AppLog.w(TAG, "crash-report: SELF credential missing, skip")
            return@withContext Result.failure(IllegalStateException("SELF credential missing"))
        }
        if (self.smtpHost.isBlank()) {
            AppLog.w(TAG, "crash-report: SELF smtp not configured, skip")
            return@withContext Result.failure(IllegalStateException("SELF SMTP not configured"))
        }
        val creds = try {
            self.toCredentials()
        } catch (e: Exception) {
            AppLog.w(TAG, "crash-report: build credentials failed", e)
            return@withContext Result.failure(e)
        }
        try {
            sender.send(
                creds,
                OutgoingMail(
                    toAddress = self.email,
                    subject = subject,
                    body = body,
                ),
            )
            Result.success(Unit)
        } catch (e: Exception) {
            AppLog.w(TAG, "crash-report: SMTP send failed", e)
            Result.failure(e)
        }
    }

    // ============================================================
    // 内部辅助
    // ============================================================

    private suspend fun requireSelf(): Account =
        accountDao.getFirstByType(AccountType.SELF)
            ?: error("尚未配置自己的邮箱（SELF），请先在设置页填写")

    private suspend fun requireAgent(): Account =
        accountDao.getFirstByType(AccountType.AGENT)
            ?: error("尚未配置 AI 的邮箱（AGENT），请先在设置页填写")

    private fun Account.toCredentials(): MailCredentials {
        val password = vault.get(credentialKey)
            ?: error("Credential missing for account $id")
        return MailCredentials(
            username = email,
            password = password,
            emailAddress = email,
            imapHost = imapHost,
            imapPort = imapPort,
            imapUseSsl = imapUseSsl,
            smtpHost = smtpHost,
            smtpPort = smtpPort,
            smtpUseStartTls = smtpUseStartTls,
        )
    }

    private suspend fun ensureUnclassifiedTask(selfAccountId: String) {
        if (taskDao.getById(SystemIds.UNCLASSIFIED_TASK_ID) != null) return
        taskDao.upsert(
            Task(
                id = SystemIds.UNCLASSIFIED_TASK_ID,
                rootMessageId = SystemIds.UNCLASSIFIED_TASK_ID,
                title = "",
                agentAccountId = selfAccountId,
                createdAt = 0L,
                lastActivityAt = 0L,
                archived = true,
            )
        )
    }

    private fun ensureReplyPrefix(title: String): String =
        if (title.trimStart().lowercase().startsWith("re:")) title else "Re: $title"

    private fun List<OutgoingAttachment>.toAttachmentsMeta(): List<Attachment> =
        map { Attachment(it.fileName, it.mimeType, it.bytes.size.toLong(), null) }

    private companion object {
        const val TAG = "MailRepository"
    }
}

/**
 * 给列表页的卡片级简要视图。
 *
 * @property task 任务本体
 * @property lastMessagePreview 最新一条消息正文的单行摘要（最多 60 字）；任务还没消息时为空串
 * @property lastMessageAt 最新一条消息的时间；任务无消息时回落到 `task.lastActivityAt`
 * @property unreadCount 未读消息数（fromAgent=1 AND isRead=0）
 */
data class TaskBrief(
    val task: Task,
    val lastMessagePreview: String,
    val lastMessageAt: Long,
    val unreadCount: Int,
)

/**
 * 单次 syncInbox 结果。通知层据此按 Task 分组推通知。
 *
 * @property totalNew 本次入库的新消息总条数（所有任务累加，含未归类）
 * @property perTask 按任务归组的摘要，插入顺序与邮件到达顺序一致
 */
data class SyncResult(
    val totalNew: Int,
    val perTask: List<TaskNewMessages>,
)

/**
 * 单个 Task 本次收到的新消息摘要。
 *
 * @property taskId 本地 Task 主键，`__UNCLASSIFIED__` 表示未归类占位
 * @property taskTitle 任务标题；未归类为空串
 * @property newCount 本次新增消息条数
 * @property latestPreview 最新一条消息正文的单行摘要（最多 160 字，用于通知展开）
 * @property latestSubject 最新一条消息的 Subject
 * @property latestSentAt 最新一条消息的发送时间（epoch ms）
 */
data class TaskNewMessages(
    val taskId: String,
    val taskTitle: String,
    val newCount: Int,
    val latestPreview: String,
    val latestSubject: String,
    val latestSentAt: Long,
)
