package com.szgenle.agentpost.core.mail.internal

import com.szgenle.agentpost.core.mail.MailCredentials
import com.szgenle.agentpost.core.mail.MailSender
import com.szgenle.agentpost.core.mail.OutgoingAttachment
import com.szgenle.agentpost.core.mail.OutgoingMail
import jakarta.activation.DataHandler
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import java.util.UUID

/**
 * Jakarta Mail 的 SMTP 实现。
 *
 * 设计要点：
 * - Message-ID 由本实现**主动生成**（UUID@domain），发送前写 header；
 *   这样上层能立刻拿到 ID 入库，不用等 SMTP 往返完成再去解析
 * - In-Reply-To / References 按 RFC 2822 加尖括号
 * - 所有 IO 切到 [Dispatchers.IO]
 */
internal class JakartaMailSender : MailSender {

    override suspend fun send(
        credentials: MailCredentials,
        mail: OutgoingMail,
    ): String = withContext(Dispatchers.IO) {
        val props = Properties().apply {
            put("mail.smtp.host", credentials.smtpHost)
            put("mail.smtp.port", credentials.smtpPort.toString())
            put("mail.smtp.auth", "true")
            if (credentials.smtpUseStartTls) {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            } else {
                // 非 STARTTLS 场景下走隐式 TLS（如 465 端口）
                put("mail.smtp.ssl.enable", "true")
            }
            // 连接超时兜底，避免 ANR
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "30000")
            put("mail.smtp.writetimeout", "30000")
        }

        val session = Session.getInstance(
            props,
            object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(credentials.username, credentials.password)
            },
        )

        val domain = credentials.emailAddress.substringAfter('@', missingDelimiterValue = "agentpost.local")
        val messageId = "${UUID.randomUUID()}@$domain"

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(credentials.emailAddress))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(mail.toAddress))
            setSubject(mail.subject, "UTF-8")
            sentDate = java.util.Date()

            // Message-ID 主动写入；saveChanges() 不会覆盖已存在的 header
            setHeader("Message-ID", "<$messageId>")
            mail.inReplyToMessageId?.let { setHeader("In-Reply-To", "<$it>") }
            if (mail.references.isNotEmpty()) {
                setHeader(
                    "References",
                    mail.references.joinToString(separator = " ") { "<$it>" },
                )
            }

            if (mail.attachments.isEmpty()) {
                setText(mail.body, "UTF-8")
            } else {
                val multipart = MimeMultipart().apply {
                    addBodyPart(
                        MimeBodyPart().apply {
                            setText(mail.body, "UTF-8")
                        },
                    )
                    for (att in mail.attachments) {
                        addBodyPart(buildAttachmentPart(att))
                    }
                }
                setContent(multipart)
            }

            saveChanges()
        }

        Transport.send(message)
        messageId
    }

    private fun buildAttachmentPart(att: OutgoingAttachment): MimeBodyPart =
        MimeBodyPart().apply {
            val ds = ByteArrayDataSource(att.bytes, att.mimeType)
            dataHandler = DataHandler(ds)
            fileName = att.fileName
            disposition = "attachment"
        }
}
