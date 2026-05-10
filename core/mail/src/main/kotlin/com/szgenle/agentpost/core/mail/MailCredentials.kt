package com.szgenle.agentpost.core.mail

/**
 * 邮件连接参数。
 *
 * mail 层故意不依赖 [com.szgenle.agentpost.core.model.Account]：
 * - Entity 含 id/displayName/type 等业务字段，mail 层不关心
 * - 凭据（password / token）的解密也由上层做，mail 层只接收已明文的 [password]
 *
 * 上层调用前负责从 Account + EncryptedSharedPreferences 组装本对象。
 */
data class MailCredentials(
    /** SMTP/IMAP 登录名，通常就是完整邮箱地址。 */
    val username: String,
    /** 已解密的明文密码 / App Password / OAuth token。 */
    val password: String,
    /** 邮件 From/To 展示用的地址，MVP 等于 [username]。 */
    val emailAddress: String,

    // ---- IMAP ----
    val imapHost: String,
    val imapPort: Int,
    val imapUseSsl: Boolean,

    // ---- SMTP ----
    val smtpHost: String,
    val smtpPort: Int,
    val smtpUseStartTls: Boolean,
)
