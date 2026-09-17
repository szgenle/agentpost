package com.szgenle.agentpost.core.mail

/**
 * 拉取邮件。
 *
 * 提供两种模式：
 * - 轮询：[fetchNew]，WorkManager / 前台 30s Coroutine 都用这个。用完即关，无长连接。
 * - IDLE 长连：[startPush]，供 PushSyncService 在用户开启「实时通知」开关后调用，
 *   秒级感知新邮件。
 */
interface MailFetcher {

    /**
     * 拉一批新邮件（纯 UID 增量，不筛选已读/未读）。
     *
     * IMAP UID 只在同一个 UIDVALIDITY epoch 内有意义，本方法内部先比对：
     * - [sinceUidValidity] 与服务端当前 UIDVALIDITY 一致 → 只拉 UID > [sinceUid] 的邮件；
     * - 不一致或本地未保存（null）→ 服务端重建过 INBOX、UID 已重新编号，旧水位
     *   会永久过滤掉新邮件，因此降级为从 UID 1 全量重扫，新 epoch 经
     *   [FetchBatch.uidValidity] 回传给上层保存。重复入库由上层 Message-ID 去重兜底。
     *
     * @param credentials       账户凭据
     * @param sinceUid          只拉 UID 大于此值的邮件；0 = 全量
     * @param sinceUidValidity  本地保存的 UIDVALIDITY；null 表示尚未记录
     * @return [FetchBatch]，含成功解析的邮件（按 sentAt 升序）、解析失败的 UID 与本次 UIDVALIDITY
     */
    @Throws(Exception::class)
    suspend fun fetchNew(
        credentials: MailCredentials,
        sinceUid: Long = 0L,
        sinceUidValidity: Long? = null,
    ): FetchBatch

    /**
     * 标记一批邮件为已读（置 IMAP SEEN flag）。
     *
     * 供详情页"打开即已读"场景使用。失败时抛异常，调用方自己决定
     * 是否重试，不影响本地 `TaskMessage.isRead` 的独立更新。
     */
    @Throws(Exception::class)
    suspend fun markSeen(credentials: MailCredentials, imapUids: List<Long>)

    /**
     * 按 UID + 附件在 walkParts 顺序中的序号重新拉附件字节流。
     *
     * 用于附件懒下载：syncInbox 时只存元数据，用户点击查看时再重开 IMAP 拉。
     * 序号语义必须与 [IncomingAttachment.partIndex] 一致（同一 walkParts 算法）。
     *
     * @return 字节流，由调用方负责关闭
     */
    @Throws(Exception::class)
    suspend fun fetchAttachment(
        credentials: MailCredentials,
        imapUid: Long,
        partIndex: String,
    ): java.io.InputStream

    /**
     * 启动 IMAP IDLE 长连接推送会话。
     *
     * 内部以自管理的协程作业运行主循环：
     * 1. 独立 Store，长连接 folder.open(READ_ONLY)；每次建连校验 UIDVALIDITY，
     *    epoch 变化则内部水位归零做全量重扫（与 [fetchNew] 同一套规则）
     * 2. 主循环：folder.idle() 阻塞 → 只拉 UID > 上次追踪到的邮件（不筛选已读/未读）
     *    → 回调 [onIncoming]
     * 3. 心跳：每 9 分钟在辅助协程读一次 folder.messageCount，让 jakarta mail 退出 IDLE
     *    再重新进入，避免 NAT / 服务器 IDLE 超时（RFC 3501 建议 ≤ 29 min）
     * 4. 异常：指数退避 1→2→4→8→30s 封顶重连，错误透到 [onError]
     *
     * 水位推进语义：只有 [onIncoming] 成功返回（上层入库成功）且批内没有解析失败的
     * UID 时才推进（见 [UidWatermarks.safeAdvance]）；入库异常会抛回本层阻止推进，
     * 下轮重拉同一区间，Message-ID 去重保证幂等。
     *
     * 返回 [MailPushSession]，调 [MailPushSession.stop] 可安全终止。
     */
    fun startPush(
        credentials: MailCredentials,
        initialUid: Long,
        initialUidValidity: Long? = null,
        onIncoming: suspend (FetchBatch) -> Unit,
        onError: (Throwable) -> Unit,
        onUidValidityChanged: suspend (Long) -> Unit = {},
    ): MailPushSession
}

/**
 * 单次增量拉取的结果。
 *
 * @property mails      成功解析、待上层入库的邮件（按 sentAt 升序；可能含上层已见过的，由 Message-ID 去重）
 * @property failedUids 本次 UID 区间内解析失败的邮件 UID。水线推进不得跨过它们，
 *                      否则对应邮件会被永久漏掉（见 [UidWatermarks.safeAdvance]）
 * @property uidValidity 本次 folder 的 IMAP UIDVALIDITY，上层据此检测 epoch 变化
 */
data class FetchBatch(
    val mails: List<IncomingMail>,
    val failedUids: List<Long>,
    val uidValidity: Long,
)

/**
 * IDLE 推送会话句柄，调用方持有。stop() 安全关闭长连接、取消内部协程。
 */
interface MailPushSession {
    fun stop()
    val isRunning: Boolean
}
