package com.szgenle.agentpost.core.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 任务 = 一条邮件会话（以 Message-ID 作为线程锚点）。
 *
 * 已删除字段（见 HANDOVER 第 5 节）：
 * - TaskStatus 枚举（无法由 Android 端自动判定，未读/已读/归档已足够）
 * - Kind 枚举（通用协议路线不做类型标签）
 * - tags（MVP 用不上）
 */
@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["agentAccountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("agentAccountId"),
        Index(value = ["rootMessageId"], unique = true),
    ],
)
data class Task(
    @PrimaryKey val id: String,          // 本地 UUID，只用作内部主键，不进邮件 header
    val rootMessageId: String,           // 首封邮件的 Message-ID，作为线程锚点
    val title: String,                   // 首封邮件 Subject 去 Re: 规范化后的值
    val agentAccountId: String,          // → Account.id（type=AGENT），为未来多 Agent 预留
    val createdAt: Long,
    val lastActivityAt: Long,
    val archived: Boolean = false,       // 用户主动归档；MVP 可先留字段、UI 后补
)
