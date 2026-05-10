package com.szgenle.agentpost.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 邮件账户。
 *
 * MVP 阶段硬约束：SELF 最多 1 条（我自己的邮箱，发信方），
 * AGENT 最多 1 条（家里 AI 的邮箱，收件方）。
 * 表结构已按多账户预留，未来放开 N 个 Agent 只需改 UI 层。
 */
@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey val id: String,          // UUID
    val type: AccountType,               // SELF / AGENT
    val displayName: String,             // "我的 Gmail" / "家里 AI"
    val email: String,
    // IMAP
    val imapHost: String,
    val imapPort: Int,
    val imapUseSsl: Boolean,
    // SMTP
    val smtpHost: String,
    val smtpPort: Int,
    val smtpUseStartTls: Boolean,
    // 凭据引用 key：密码 / App Password / OAuth Token 本身存 EncryptedSharedPreferences，
    // 这里只存引用 key，不存明文
    val credentialKey: String,
    val createdAt: Long,
)

enum class AccountType { SELF, AGENT }
