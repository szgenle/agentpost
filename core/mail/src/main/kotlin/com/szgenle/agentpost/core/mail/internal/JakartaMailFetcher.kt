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
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.ReceivedDateTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Properties

/**
 * Jakarta Mail 的 IMAP 实现。
 *
 * 每次调用独立打开 Store，用完就关；MVP 阶段不做长连接 / IDLE。
 * 15 min 调度的成本可接受，换取健壮（不用维护状态机）。
 */
internal class JakartaMailFetcher : MailFetcher {

    internal companion object {
        const val TAG = "JakartaMailFetcher"

        /**
         * 回溯窗口（天）：首次配置 / 换邮箱 / 手动重扫 / UIDVALIDITY 变化时，只往回捞
         * 最近这么多天收到的邮件，而非整箱全扫。日常增量同步不受此影响（永远只拉 UID > 水线）。
         * 7 天足以覆盖"刚配好就想收到近期回复"的场景，又能避开跨零点漏信。
         */
        const val BACKFILL_WINDOW_DAYS = 7

        /** 极少数服务器不支持 IMAP SINCE 搜索时的兜底：退化为"最近 N 封"，仍避免整箱全扫。 */
        const val BACKFILL_FALLBACK_COUNT = 200

        /**
         * 附件下载连接的 partial fetch 块大小。angus-mail 默认 16KB，拉 30MB 附件意味着
         * ~2000 次同步 FETCH 命令往返（每次都要等服务器定位 + 读盘 + base64），QQ 邮箱
         * 实测是分钟级等待且无进度可拖。加大到 512KB 后同样附件只需 ~60 次往返。
         */
        internal const val ATTACHMENT_FETCH_BLOCK_BYTES = 512 * 1024

        /** 附件下载连接的 socket 读超时：单块变大后单次 read 允许更久，比同步连接的 30s 宽松。 */
        internal const val ATTACHMENT_READ_TIMEOUT_MS = 120_000
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
                // UIDVALIDITY 不一致或本地未记录 → 需要回溯。回溯只捞最近窗口、不从 UID 1 全扫。
                val rescan = sinceUidValidity == null || sinceUidValidity != uidValidity
                val range: List<Message> = if (rescan) {
                    AppLog.w(
                        TAG,
                        "fetchNew: backfill recent ${BACKFILL_WINDOW_DAYS}d " +
                            "(local uidValidity=$sinceUidValidity server=$uidValidity), sinceUid=$sinceUid",
                    )
                    logAllFolders(store.store)
                    recentWindow(folder)
                } else {
                    folder.getMessagesByUID(sinceUid + 1, UIDFolder.LASTUID).filterNotNull()
                }
                val startUid = if (rescan) 1L else sinceUid + 1
                // 回溯后把水线一步推到当前顶端：窗口之前的历史一律不再回看，永不触发整箱全扫。
                val highWaterUid = if (rescan) topUid(folder) else null
                AppLog.i(
                    TAG,
                    "fetchNew: total=$total uidValidity=$uidValidity sinceUid=$sinceUid " +
                        "rescan=$rescan rangeSize=${range.size} highWaterUid=$highWaterUid",
                )
                val (mails, failedUids) = parseRange(folder, range, startUid, uidValidity, TAG)
                FetchBatch(
                    mails = mails,
                    failedUids = failedUids,
                    uidValidity = uidValidity,
                    highWaterUid = highWaterUid,
                )
            } finally {
                folder.close(false)
            }
        }
    }

    /**
     * 诊断：列出账号下所有 IMAP 文件夹及各自邮件数。
     * 仅在回溯(rescan)时打一次，用于定位“邮件被 QQ 收信规则归入了哪个文件夹”——
     * 本 app 只同步 [INBOX]，其它文件夹里的邮件不会进来。
     */
    private fun logAllFolders(store: Store) {
        runCatching {
            val folders = store.defaultFolder.list("*")
            AppLog.i(TAG, "folders: found ${folders.size} folder(s) — app only syncs [INBOX]")
            for (f in folders) {
                val name = f.fullName
                val count = if (name.equals("INBOX", ignoreCase = true)) {
                    // INBOX 已被 fetchNew 打开，直接读，避免误关调用方在用的连接。
                    runCatching { f.messageCount }.getOrDefault(-1)
                } else {
                    runCatching {
                        f.open(Folder.READ_ONLY)
                        val c = f.messageCount
                        f.close(false)
                        c
                    }.getOrDefault(-1)
                }
                AppLog.i(TAG, "folders: [$name] count=$count")
            }
        }.onFailure { e ->
            AppLog.w(TAG, "folders: list failed: ${e.message}")
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
        target: java.io.File,
        onProgress: (Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val targetIdx = partIndex.toIntOrNull()
            ?: error("invalid partIndex=$partIndex")
        // 下载专用连接参数：大块 partial fetch + 宽粒读超时（见常量注释）
        openStore(
            credentials,
            extraProps = mapOf(
                "mail.imap.fetchsize" to ATTACHMENT_FETCH_BLOCK_BYTES.toString(),
                "mail.imaps.fetchsize" to ATTACHMENT_FETCH_BLOCK_BYTES.toString(),
                "mail.imap.timeout" to ATTACHMENT_READ_TIMEOUT_MS.toString(),
                "mail.imaps.timeout" to ATTACHMENT_READ_TIMEOUT_MS.toString(),
            ),
        ).use { store ->
            val folder = store.store.getFolder("INBOX") as IMAPFolder
            folder.open(Folder.READ_ONLY)
            try {
                val msg = folder.getMessageByUID(imapUid) as? MimeMessage
                    ?: error("message not found for uid=$imapUid")
                // 用与 collectAttachments 一致的 walkParts 顺序重新算，定位到第 targetIdx 个附件
                var found: Part? = null
                var cursor = 0
                walkParts(msg) { p ->
                    if (found != null) return@walkParts
                    val disp = runCatching { p.disposition }.getOrNull()
                    val isAttachment = Part.ATTACHMENT.equals(disp, ignoreCase = true) ||
                        !p.fileName.isNullOrBlank()
                    if (isAttachment && !p.fileName.isNullOrBlank()) {
                        if (cursor == targetIdx) {
                            found = p
                        }
                        cursor++
                    }
                }
                val part = found ?: error("attachment part $partIndex not found on uid=$imapUid")
                // 流式直写磁盘：不再整包读进内存包 ByteArrayInputStream（大附件会把堆
                // 翻倍），边拉边写边报进度。
                part.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastReported = -1L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            copied += n
                            // 约每 128KB 上报一次，避免高频回调打满调用方状态流
                            if (copied - lastReported >= 128 * 1024) {
                                onProgress(copied)
                                lastReported = copied
                            }
                        }
                        onProgress(copied)
                    }
                }
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

    /**
     * 回溯窗口：返回 folder 中最近 [BACKFILL_WINDOW_DAYS] 天收到的邮件（IMAP SINCE 搜索）。
     * 供 [fetchNew] 与 IDLE 推送建连时的回溯共用，避免整箱全扫。
     * 极少数服务器不支持日期搜索时，退化为"最近 [BACKFILL_FALLBACK_COUNT] 封"兜底。
     */
    internal fun recentWindow(folder: IMAPFolder): List<Message> {
        val cutoff = Date(System.currentTimeMillis() - BACKFILL_WINDOW_DAYS * 24L * 60L * 60L * 1000L)
        return runCatching {
            folder.search(ReceivedDateTerm(ComparisonTerm.GE, cutoff)).filterNotNull()
        }.getOrElse { e ->
            AppLog.w(TAG, "recentWindow: date search failed (${e.message}), fallback to last $BACKFILL_FALLBACK_COUNT messages")
            val top = topUid(folder)
            if (top <= 0L) return emptyList()
            val start = (top - BACKFILL_FALLBACK_COUNT + 1).coerceAtLeast(1L)
            folder.getMessagesByUID(start, UIDFolder.LASTUID).filterNotNull()
        }
    }

    /** folder 当前最高 UID；空箱或取失败返回 0。回溯后据此把水线一步推到顶。 */
    internal fun topUid(folder: IMAPFolder): Long = runCatching {
        val n = folder.messageCount
        if (n > 0) folder.getUID(folder.getMessage(n)) else 0L
    }.getOrDefault(0L)

    /**
     * 逐封独立解析一段 IMAP 消息，绝不在保护外触碰 envelope（getUID / sentDate / cast）。
     * 单封损坏（如 "Failed to load IMAP envelope"）只记为 failedUid 跳过，不拖垮整批。
     * @return 成功解析的邮件（按 sentAt 升序）到解析失败 UID 列表
     */
    internal fun parseRange(
        folder: IMAPFolder,
        range: List<Message>,
        startUid: Long,
        uidValidity: Long,
        logTag: String,
    ): Pair<List<IncomingMail>, List<Long>> {
        val mails = mutableListOf<IncomingMail>()
        val failedUids = mutableListOf<Long>()
        for (msg in range) {
            val uid = runCatching { folder.getUID(msg) }.getOrNull()
            if (uid == null) {
                AppLog.w(logTag, "parseRange: getUID failed, skip one message")
                continue
            }
            if (uid < startUid) continue
            runCatching { parse(msg as MimeMessage, uid, uidValidity) }
                .onSuccess { mails += it }
                .onFailure { e ->
                    AppLog.w(logTag, "parseRange: parse failed uid=$uid: ${e.message}")
                    failedUids += uid
                }
        }
        mails.sortBy { it.sentAt }
        return mails to failedUids
    }

    private fun openStore(
        credentials: MailCredentials,
        extraProps: Map<String, String> = emptyMap(),
    ): StoreHandle {
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
            for ((k, v) in extraProps) put(k, v)
        }
        val session = Session.getInstance(props)
        val store = session.getStore(if (credentials.imapUseSsl) "imaps" else "imap")
        store.connect(credentials.imapHost, credentials.imapPort, credentials.username, credentials.password)
        return StoreHandle(store)
    }

    private fun parse(msg: MimeMessage, uid: Long, uidValidity: Long = 0L): IncomingMail {
        // 极少数邮件没有 Message-ID 头。过去直接 error() 会让它永久解析失败，并被
        // safeAdvance 当作"临时失败"钉死增量水线（停在首个失败 UID 之前，之后每轮重拉）。
        // 改为合成一个 epoch 内稳定的 ID（uidValidity+uid），照常入库、正常去重，水线得以越过它推进。
        val messageId = MimeUtils.stripAngleBrackets(msg.getHeader("Message-ID")?.firstOrNull())
            ?: "agentpost-synthetic-$uidValidity-$uid@localhost"
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
        // 只对 multipart 读 content 拿子结构；leaf part（正文 / 附件）一律不碰 content——
        // 否则每次遍历结构都会顺带触发正文 part 的网络 fetch（附件 part 虽是惰性流，
        // 同样不该在定位阶段被打开）。contentType 从 BODYSTRUCTURE 缓存判断，无网络开销。
        if (part.isMimeType("multipart/*")) {
            val content = runCatching { part.content }.getOrNull()
            if (content is Multipart) {
                for (i in 0 until content.count) {
                    walkParts(content.getBodyPart(i), visitor)
                }
                return
            }
        }
        visitor(part)
    }

    /** 轻量 AutoCloseable 包装，方便 use {} 自动关闭。 */
    private class StoreHandle(val store: jakarta.mail.Store) : AutoCloseable {
        override fun close() {
            runCatching { if (store.isConnected) store.close() }
        }
    }
}
