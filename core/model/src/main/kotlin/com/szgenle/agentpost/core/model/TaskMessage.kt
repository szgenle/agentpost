package com.szgenle.agentpost.core.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 任务中的单条消息，1:1 对应一封真实邮件。
 *
 * - [messageId] 直接复用邮件原生 Message-ID，作为唯一主键。
 * - [attachments] 通过 TypeConverter 以 JSON 形式嵌入本行，
 *   MVP 阶段不单独建附件表；未来"按附件查询"再拆表。
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
    ],
)
data class TaskMessage(
    @PrimaryKey val messageId: String,   // 邮件原生 Message-ID
    val taskId: String,
    val fromAgent: Boolean,              // true = AI 发来；false = 我发出
    val subject: String,                 // 邮件原始 Subject（可能含 Re:）
    val body: String,                    // Markdown 或纯文本
    val attachments: List<Attachment> = emptyList(),
    val inReplyTo: String?,              // 邮件 header 原值，用于线程重建
    val sentAt: Long,
    val isRead: Boolean = false,         // 对应 IMAP SEEN flag
)
