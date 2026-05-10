package com.szgenle.agentpost.core.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 任务中的单条消息，1:1 对应一封真实邮件（或一条本地草稿/失败发送）。
 *
 * 主键语义演进（v2）：
 * - [id] 本地 UUID，是 Room 主键。草稿/发送中/失败消息在真正写到 SMTP 之前已经有稳定 id，
 *   UI 的 LazyColumn key 不会因为发送成功填入邮件 Message-ID 而跳变。
 * - [externalMessageId] 邮件原生 Message-ID；
 *   拉取到的邮件入库时立即填充；本机发出的消息在 SMTP 成功返回后填充；草稿/失败为 null。
 *   供路由（In-Reply-To / References 反查）与去重使用。
 *
 * 附件通过 TypeConverter 以 JSON 形式嵌入本行，MVP 阶段不单独建附件表。
 */
@Entity(
    tableName = "task_messages",
    foreignKeys = [
        ForeignKey(
            entity = Task::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("taskId"),
        Index("sentAt"),
        // externalMessageId 唯一（仅对非 null 生效；草稿多条都是 null，SQLite 允许多个 null 并存）
        Index(value = ["externalMessageId"], unique = true),
    ],
)
data class TaskMessage(
    @PrimaryKey val id: String,                 // 本地 UUID
    val externalMessageId: String?,             // 邮件 Message-ID；草稿/失败为 null
    val taskId: String,
    val fromAgent: Boolean,                     // true = AI 发来；false = 我发出
    val subject: String,                        // 邮件原始 Subject（可能含 Re:）
    val body: String,                           // Markdown 或纯文本
    val attachments: List<Attachment> = emptyList(),
    val inReplyTo: String?,                     // 邮件 header 原值，用于线程重建
    val sentAt: Long,                           // 本机发出：入库时刻；AI 来信：邮件 sentDate
    val isRead: Boolean = false,                // 对应 IMAP SEEN flag
    val sendStatus: SendStatus = SendStatus.SENT,   // 默认 SENT 以兼容收到的邮件
    val sendError: String? = null,              // FAILED 时的错误原因（截断过的简要信息）
)
