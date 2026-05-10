package com.szgenle.agentpost.core.mail

/**
 * 常用邮箱服务商的 IMAP/SMTP 默认参数预设。
 *
 * 用于设置界面"一键填充"场景：用户选择常用邮箱（QQ / Gmail / 163 等）后，
 * 自动填好 host/port/SSL/STARTTLS，避免手输错。
 *
 * 注意：
 * - QQ / 163 / 126 SMTP 走隐式 SSL（465 端口），STARTTLS 必须关闭。
 * - Gmail / Outlook / iCloud SMTP 走 STARTTLS（587 端口）。
 * - 选择 [CUSTOM] 时不修改字段，由用户手动填写。
 */
data class MailProviderPreset(
    /** 唯一标识，便于 UI 持久化选中状态。 */
    val id: String,
    /** 显示名（中文）。 */
    val displayName: String,
    /** 常见邮箱后缀，用于按邮箱地址自动推断（不含 @）。空表示不参与推断。 */
    val emailDomains: List<String>,
    val imapHost: String,
    val imapPort: Int,
    val imapUseSsl: Boolean,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpUseStartTls: Boolean,
)

object MailProviderPresets {

    val QQ = MailProviderPreset(
        id = "qq",
        displayName = "QQ 邮箱",
        emailDomains = listOf("qq.com", "vip.qq.com", "foxmail.com"),
        imapHost = "imap.qq.com",
        imapPort = 993,
        imapUseSsl = true,
        smtpHost = "smtp.qq.com",
        smtpPort = 465,
        smtpUseStartTls = false,
    )

    val GMAIL = MailProviderPreset(
        id = "gmail",
        displayName = "Gmail",
        emailDomains = listOf("gmail.com", "googlemail.com"),
        imapHost = "imap.gmail.com",
        imapPort = 993,
        imapUseSsl = true,
        smtpHost = "smtp.gmail.com",
        smtpPort = 587,
        smtpUseStartTls = true,
    )

    val NETEASE_163 = MailProviderPreset(
        id = "163",
        displayName = "163 邮箱",
        emailDomains = listOf("163.com"),
        imapHost = "imap.163.com",
        imapPort = 993,
        imapUseSsl = true,
        smtpHost = "smtp.163.com",
        smtpPort = 465,
        smtpUseStartTls = false,
    )

    val NETEASE_126 = MailProviderPreset(
        id = "126",
        displayName = "126 邮箱",
        emailDomains = listOf("126.com"),
        imapHost = "imap.126.com",
        imapPort = 993,
        imapUseSsl = true,
        smtpHost = "smtp.126.com",
        smtpPort = 465,
        smtpUseStartTls = false,
    )

    val CHINA_MOBILE_139 = MailProviderPreset(
        id = "139",
        displayName = "139 邮箱",
        emailDomains = listOf("139.com"),
        imapHost = "imap.139.com",
        imapPort = 993,
        imapUseSsl = true,
        smtpHost = "smtp.139.com",
        smtpPort = 465,
        smtpUseStartTls = false,
    )

    val OUTLOOK = MailProviderPreset(
        id = "outlook",
        displayName = "Outlook",
        emailDomains = listOf("outlook.com", "hotmail.com", "live.com", "msn.com"),
        imapHost = "outlook.office365.com",
        imapPort = 993,
        imapUseSsl = true,
        smtpHost = "smtp.office365.com",
        smtpPort = 587,
        smtpUseStartTls = true,
    )

    val ICLOUD = MailProviderPreset(
        id = "icloud",
        displayName = "iCloud",
        emailDomains = listOf("icloud.com", "me.com", "mac.com"),
        imapHost = "imap.mail.me.com",
        imapPort = 993,
        imapUseSsl = true,
        smtpHost = "smtp.mail.me.com",
        smtpPort = 587,
        smtpUseStartTls = true,
    )

    /** 自定义占位，UI 选中后允许自由编辑，不修改任何字段。 */
    val CUSTOM = MailProviderPreset(
        id = "custom",
        displayName = "自定义",
        emailDomains = emptyList(),
        imapHost = "",
        imapPort = 993,
        imapUseSsl = true,
        smtpHost = "",
        smtpPort = 587,
        smtpUseStartTls = true,
    )

    /** UI 顺序：常用国内邮箱在前。 */
    val ALL: List<MailProviderPreset> = listOf(
        QQ, NETEASE_163, NETEASE_126, CHINA_MOBILE_139, GMAIL, OUTLOOK, ICLOUD, CUSTOM,
    )

    /**
     * 按邮箱地址匹配预设（取 @ 之后部分，忽略大小写）。
     * 匹配不到时返回 null（UI 可视为"自定义"）。
     */
    fun matchByEmail(email: String): MailProviderPreset? {
        val domain = email.substringAfter('@', missingDelimiterValue = "")
            .trim()
            .lowercase()
        if (domain.isEmpty()) return null
        return ALL.firstOrNull { preset ->
            preset.emailDomains.any { it.equals(domain, ignoreCase = true) }
        }
    }
}
