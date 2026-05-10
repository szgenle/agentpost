package com.szgenle.agentpost.core.mail.internal

import com.szgenle.agentpost.core.mail.IncomingAttachment
import com.szgenle.agentpost.core.mail.IncomingMail
import com.szgenle.agentpost.core.mail.MailCredentials
import com.szgenle.agentpost.core.mail.MailFetcher
import org.eclipse.angus.mail.imap.IMAPFolder
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.FlagTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

/**
 * Jakarta Mail 的 IMAP 实现。
 *
 * 每次调用独立打开 Store，用完就关；MVP 阶段不做长连接 / IDLE。
 * 15 min 调度的成本可接受，换取健壮（不用维护状态机）。
 */
internal class JakartaMailFetcher : MailFetcher {

    override suspend fun fetchNew(
        credentials: MailCredentials,
        sinceUid: Long,
    ): List<IncomingMail> = withContext(Dispatchers.IO) {
        openStore(credentials).use { store ->
            val folder = store.store.getFolder("INBOX") as IMAPFolder
            folder.open(Folder.READ_ONLY)
            try {
                // 只拉未读；对端有时不置 SEEN，也能通过 sinceUid 兜底去重
                val unseen = folder.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
                unseen
                    .map { folder.getUID(it) to (it as MimeMessage) }
                    .filter { (uid, _) -> uid > sinceUid }
                    .sortedBy { (_, msg) -> msg.sentDate?.time ?: 0L }
                    .mapNotNull { (uid, msg) -> runCatching { parse(msg, uid) }.getOrNull() }
            } finally {
                folder.close(false)
            }
        }
    }

    override suspend fun markSeen(
        credentials: MailCredentials,
        imapUids: List<Long>,
    ) = withContext(Dispatchers.IO) {
        if (imapUids.isEmpty()) return@withContext
        openStore(credentials).use { store ->
            val folder = store.store.getFolder("INBOX") as IMAPFolder
            folder.open(Folder.READ_WRITE)
            try {
                val msgs = folder.getMessagesByUID(imapUids.toLongArray())
                    .filterNotNull()
                    .toTypedArray()
                if (msgs.isNotEmpty()) {
                    folder.setFlags(msgs, Flags(Flags.Flag.SEEN), true)
                }
            } finally {
                folder.close(false)
            }
        }
    }

    // ---------------- private ----------------

    private fun openStore(credentials: MailCredentials): StoreHandle {
        val props = Properties().apply {
            put("mail.store.protocol", if (credentials.imapUseSsl) "imaps" else "imap")
            put("mail.imaps.host", credentials.imapHost)
            put("mail.imaps.port", credentials.imapPort.toString())
            put("mail.imap.host", credentials.imapHost)
            put("mail.imap.port", credentials.imapPort.toString())
            // 连接超时兜底
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "30000")
            put("mail.imap.connectiontimeout", "15000")
            put("mail.imap.timeout", "30000")
        }
        val session = Session.getInstance(props)
        val store = session.getStore(if (credentials.imapUseSsl) "imaps" else "imap")
        store.connect(credentials.imapHost, credentials.imapPort, credentials.username, credentials.password)
        return StoreHandle(store)
    }

    private fun parse(msg: MimeMessage, uid: Long): IncomingMail {
        val messageId = MimeUtils.stripAngleBrackets(msg.getHeader("Message-ID")?.firstOrNull())
            ?: error("missing Message-ID on uid=$uid")
        val inReplyTo = MimeUtils.stripAngleBrackets(msg.getHeader("In-Reply-To")?.firstOrNull())
        val references = MimeUtils.parseReferences(msg.getHeader("References")?.joinToString(" "))

        val subject = MimeUtils.decodeMime(msg.subject)
        val from = (msg.from?.firstOrNull()?.toString()).orEmpty()
        val to = msg.getRecipients(jakarta.mail.Message.RecipientType.TO)
            ?.map { it.toString() }
            ?: emptyList()
        val sentAt = msg.sentDate?.time ?: msg.receivedDate?.time ?: System.currentTimeMillis()

        val body = MimeUtils.extractPlainBody(msg)
        val attachments = collectAttachments(msg)
        val seen = msg.flags?.contains(Flags.Flag.SEEN) ?: false

        return IncomingMail(
            messageId = messageId,
            inReplyTo = inReplyTo,
            references = references,
            subject = subject,
            fromAddress = from,
            toAddresses = to,
            sentAt = sentAt,
            body = body,
            attachmentParts = attachments,
            seen = seen,
            imapUid = uid,
        )
    }

    private fun collectAttachments(part: Part): List<IncomingAttachment> {
        val result = mutableListOf<IncomingAttachment>()
        walkParts(part) { p ->
            val disp = runCatching { p.disposition }.getOrNull()
            val isAttachment = Part.ATTACHMENT.equals(disp, ignoreCase = true) ||
                !p.fileName.isNullOrBlank()
            if (isAttachment && !p.fileName.isNullOrBlank()) {
                result += IncomingAttachment(
                    fileName = MimeUtils.decodeMime(p.fileName),
                    mimeType = p.contentType.substringBefore(';').trim().ifEmpty { "application/octet-stream" },
                    sizeBytes = p.size.toLong(),
                    openStream = { p.inputStream },
                )
            }
        }
        return result
    }

    private fun walkParts(part: Part, visitor: (Part) -> Unit) {
        val content = runCatching { part.content }.getOrNull()
        if (content is Multipart) {
            for (i in 0 until content.count) {
                walkParts(content.getBodyPart(i), visitor)
            }
        } else {
            visitor(part)
        }
    }

    /** 轻量 AutoCloseable 包装，方便 use {} 自动关闭。 */
    private class StoreHandle(val store: jakarta.mail.Store) : AutoCloseable {
        override fun close() {
            runCatching { if (store.isConnected) store.close() }
        }
    }
}
