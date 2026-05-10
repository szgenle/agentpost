package com.szgenle.agentpost.core.data

import com.szgenle.agentpost.core.common.mail.SubjectNormalizer
import com.szgenle.agentpost.core.common.security.CredentialsVault
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
import com.szgenle.agentpost.core.model.Task
import com.szgenle.agentpost.core.model.TaskMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 应用级邮件仓库。串起 mail 协议层 + 数据库 + 凭据保险箱。
 *
 * 唯一对上层（ViewModel / Worker）暴露的类，构造由 [com.szgenle.agentpost.core.data.AppServiceLocator] 完成。
 */
class MailRepository internal constructor(
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
     * 保存（或覆盖）SELF 账户。密码写入 [CredentialsVault]，Account 表只存 credentialKey 引用。
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
        password: String,
    ) {
        val existing = accountDao.getFirstByType(AccountType.SELF)
        val id = existing?.id ?: UUID.randomUUID().toString()
        val credentialKey = existing?.credentialKey ?: "self_$id"
        vault.put(credentialKey, password)
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
                messageId = messageId,
                taskId = taskId,
                fromAgent = false,
                subject = title,
                body = body,
                attachments = attachments.toAttachmentsMeta(),
                inReplyTo = null,
                sentAt = now,
                isRead = true,
            )
        )
        taskId
    }

    /** 回复一个已有 Task。失败时不落库。 */
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

        // References 链 = 已存在的消息链按时间升序
        val existingMessageIds = messageDao.messageIdsByTaskOrderBySentAtAscOrNull(taskId)
        val lastMessageId = existingMessageIds.lastOrNull()

        val replySubject = ensureReplyPrefix(task.title)
        val outgoing = OutgoingMail(
            toAddress = agent.email,
            subject = replySubject,
            body = body,
            attachments = attachments,
            inReplyToMessageId = lastMessageId,
            references = existingMessageIds,
        )
        val messageId = sender.send(creds, outgoing)

        val now = System.currentTimeMillis()
        messageDao.upsert(
            TaskMessage(
                messageId = messageId,
                taskId = taskId,
                fromAgent = false,
                subject = replySubject,
                body = body,
                attachments = attachments.toAttachmentsMeta(),
                inReplyTo = lastMessageId,
                sentAt = now,
                isRead = true,
            )
        )
        taskDao.touch(taskId, now)
    }

    // ============================================================
    // 同步
    // ============================================================

    /** 从 SELF 邮箱拉新邮件；返回本次入库的新消息数量。 */
    suspend fun syncInbox(): Result<Int> = runCatching {
        val self = requireSelf()
        val agent = requireAgent()
        val creds = self.toCredentials()
        val sinceUid = prefs.getLastSyncUid(self.id)

        val incomings = fetcher.fetchNew(creds, sinceUid)
        if (incomings.isEmpty()) return@runCatching 0

        ensureUnclassifiedTask(self.id)
        val router = TaskRouter(taskDao, messageDao)
        var inserted = 0
        for (mail in incomings) {
            if (messageDao.exists(mail.messageId)) continue
            val targetTaskId = router.route(mail, agent.id)
            messageDao.upsert(
                TaskMessage(
                    messageId = mail.messageId,
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
                        )
                    },
                    inReplyTo = mail.inReplyTo,
                    sentAt = mail.sentAt,
                    isRead = mail.seen,
                )
            )
            if (targetTaskId != SystemIds.UNCLASSIFIED_TASK_ID) {
                taskDao.touch(targetTaskId, mail.sentAt)
            }
            inserted++
        }
        val newMaxUid = incomings.maxOf { it.imapUid }
        if (newMaxUid > sinceUid) {
            prefs.setLastSyncUid(self.id, newMaxUid)
        }
        inserted
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

    /** 列表项用：每个 Task 的简要视图（MVP 阶段先只包 Task 本身，后续可加 lastMessage / unreadCount）。 */
    fun observeTaskBriefs(): Flow<List<TaskBrief>> =
        observeTasks().map { tasks -> tasks.map { TaskBrief(task = it) } }

    suspend fun markRead(messageId: String) = messageDao.markRead(messageId)

    suspend fun markTaskRead(taskId: String) = messageDao.markAllReadInTask(taskId)

    /** 把未归类的某条消息手动指派到 Task。 */
    suspend fun assignMessageToTask(messageId: String, targetTaskId: String) {
        val msg = messageDao.getByMessageId(messageId) ?: return
        require(targetTaskId != SystemIds.UNCLASSIFIED_TASK_ID) {
            "targetTaskId cannot be unclassified placeholder"
        }
        messageDao.upsert(msg.copy(taskId = targetTaskId))
        taskDao.touch(targetTaskId, msg.sentAt)
    }

    // ============================================================
    // 内部辅助
    // ============================================================

    private suspend fun requireSelf(): Account =
        accountDao.getFirstByType(AccountType.SELF)
            ?: error("SELF account not configured")

    private suspend fun requireAgent(): Account =
        accountDao.getFirstByType(AccountType.AGENT)
            ?: error("AGENT account not configured")

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
}

/** 给列表页的卡片级简要视图（后续可加 lastMessage / unreadCount）。 */
data class TaskBrief(val task: Task)
