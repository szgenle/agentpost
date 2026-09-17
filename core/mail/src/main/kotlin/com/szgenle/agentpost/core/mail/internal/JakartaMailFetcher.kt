package com.szgenle.agentpost.core.mail.internal

import com.szgenle.agentpost.core.common.logging.AppLog
import com.szgenle.agentpost.core.mail.FetchBatch
import com.szgenle.agentpost.core.mail.IncomingAttachment
import com.szgenle.agentpost.core.mail.IncomingMail
import com.szgenle.agentpost.core.mail.MailCredentials
import com.szgenle.agentpost.core.mail.MailFetcher
import com.szgenle.agentpost.core.mail.MailPushSession
import org.eclipse.angus.mail.imap.IMAPFolder
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeMessage
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

    private companion object {
        const val TAG = "JakartaMailFetcher"
    }

    override suspend fun fetchNew(
        credentials: MailCredentials,
        sinceUid: Long,
        sinceUidValidity: Long?,
    ): FetchBatch = withContext(Dispatchers.IO) {
        openStore(credentials).use { store ->
            val folder = store.store.getFolder("INBOX") as IMAPFolder
            folder.open(Folder.READ_ONLY)
            try {
                val total = folder.messageCount
                val uidValidity = folder.uidValidity
                // UID 只在同一 UIDVALIDITY epoch 内有意义：不一致或本地未记录 → 服务器
                // 重建过 INBOX、UID 已重新编号，旧水位会永久过滤新邮件，降级为全量重扫。
                val rescan = sinceUidValidity == null || sinceUidValidity != uidValidity
                if (rescan) {
                    AppLog.w(
                        TAG,
                        "fetchNew: uidValidity changed (local=$sinceUidValidity server=$uidValidity), " +
                            "full rescan instead of incremental sinceUid=$sinceUid",
                    )
                }
                val startUid = if (rescan) 1L else sinceUid + 1
                val range = folder.getMessagesByUID(startUid, UIDFolder.LASTUID)
                    .filterNotNull()
                AppLog.i(
                    TAG,
                    "fetchNew: total=$total uidValidity=$uidValidity sinceUid=$sinceUid " +
                        "rescan=$rescan range=[$startUid..LASTUID] rangeSize=${range.size}",
                )
                val mails = mutableListOf<IncomingMail>()
                val failedUids = mutableListOf<Long>()
                range
                    .map { folder.getUID(it) to (it as MimeMessage) }
                    .filter { (uid, _) -> uid >= startUid }
                    .sortedBy { (_, msg) -> msg.sentDate?.time ?: 0L }
                    .forEach { (uid, msg) ->
                        runCatching { parse(msg, uid) }
                            .onSuccess { mails += it }
                            .onFailure { e ->
                                AppLog.w(TAG, "fetchNew: parse failed uid=$uid: ${e.message}")
                                failedUids += uid
                            }
                    }
                FetchBatch(mails = mails, failedUids = failedUids, uidValidity = uidValidity)
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

    override suspend fun fetchAttachment(
        credentials: MailCredentials,
        imapUid: Long,
        partIndex: String,
    ): java.io.InputStream = withContext(Dispatchers.IO) {
        val target = partIndex.toIntOrNull()
            ?: error("invalid partIndex=$partIndex")
        openStore(credentials).use { store ->
            val folder = store.store.getFolder("INBOX") as IMAPFolder
            folder.open(Folder.READ_ONLY)
            try {
                val msg = folder.getMessageByUID(imapUid) as? MimeMessage
                    ?: error("message not found for uid=$imapUid")
                // 用与 collectAttachments 一致的 walkParts 顺序重新算，定位到第 target 个附件
                var found: Part? = null
                var cursor = 0
                walkParts(msg) { p ->
                    if (found != null) return@walkParts
                    val disp = runCatching { p.disposition }.getOrNull()
                    val isAttachment = Part.ATTACHMENT.equals(disp, ignoreCase = true) ||
                        !p.fileName.isNullOrBlank()
                    if (isAttachment && !p.fileName.isNullOrBlank()) {
                        if (cursor == target) {
                            found = p
                        }
                        cursor++
                    }
                }
                val part = found ?: error("attachment part $partIndex not found on uid=$imapUid")
                // 注意：Store 关闭后流也会失效，调用方需在本方法回前读完。
                // 我们直接读成字节再给 ByteArrayInputStream，避免生命周期问题。
                val bytes = part.inputStream.use { it.readBytes() }
                java.io.ByteArrayInputStream(bytes)
            } finally {
                folder.close(false)
            }
        }
    }

    override fun startPush(
        credentials: MailCredentials,
        initialUid: Long,
        initialUidValidity: Long?,
        onIncoming: suspend (FetchBatch) -> Unit,
        onError: (Throwable) -> Unit,
        onUidValidityChanged: suspend (Long) -> Unit,
    ): MailPushSession {
        return JakartaMailPushSession(
            fetcher = this,
            credentials = credentials,
            initialUid = initialUid,
            initialUidValidity = initialUidValidity,
            onIncoming = onIncoming,
            onError = onError,
            onUidValidityChanged = onUidValidityChanged,
        ).also { it.start() }
    }

    // ---------------- private ----------------

    internal fun openStoreInternal(credentials: MailCredentials): Store = openStore(credentials).store

    internal fun parseInternal(msg: MimeMessage, uid: Long): IncomingMail = parse(msg, uid)

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
            // 开 TCP keep-alive，让 OS 层更早检测到 idle 长连被 NAT/MIUI 静默断开。
            // Jakarta Mail 的 SO_KEEPALIVE 属性名，同时适配 imap 和 imaps。
            put("mail.imap.socketKeepAlive", "true")
            put("mail.imaps.socketKeepAlive", "true")
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
        var index = 0
        walkParts(part) { p ->
            val disp = runCatching { p.disposition }.getOrNull()
            val isAttachment = Part.ATTACHMENT.equals(disp, ignoreCase = true) ||
                !p.fileName.isNullOrBlank()
            if (isAttachment && !p.fileName.isNullOrBlank()) {
                result += IncomingAttachment(
                    fileName = MimeUtils.decodeMime(p.fileName),
                    mimeType = p.contentType.substringBefore(';').trim().ifEmpty { "application/octet-stream" },
                    sizeBytes = p.size.toLong(),
                    partIndex = index.toString(),
                    openStream = { p.inputStream },
                )
                index++
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
