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
     * 拉一次未读邮件。
     *
     * @param credentials 账户凭据
     * @param sinceUid    只拉 UID 大于此值的邮件；首次传 0 = 全量 INBOX UNSEEN
     * @return 成功解析的来信列表（按 sentAt 升序）
     */
    @Throws(Exception::class)
    suspend fun fetchNew(credentials: MailCredentials, sinceUid: Long = 0L): List<IncomingMail>

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
     * 1. 独立 Store，长连接 folder.open(READ_ONLY)
     * 2. 主循环：folder.idle() 阻塞 → 只拉 UID > 上次追踪到的邮件 → 回调 [onIncoming]
     * 3. 心跳：每 9 分钟在辅助协程读一次 folder.messageCount，让 jakarta mail 退出 IDLE
     *    再重新进入，避免 NAT / 服务器 IDLE 超时（RFC 3501 建议 ≤ 29 min）
     * 4. 异常：指数退避 1→2→4→8→30s 封顶重连，错误透到 [onError]
     *
     * 返回 [MailPushSession]，调 [MailPushSession.stop] 可安全终止。
     */
    fun startPush(
        credentials: MailCredentials,
        initialUid: Long,
        onIncoming: suspend (List<IncomingMail>) -> Unit,
        onError: (Throwable) -> Unit,
    ): MailPushSession
}

/**
 * IDLE 推送会话句柄，调用方持有。stop() 安全关闭长连接、取消内部协程。
 */
interface MailPushSession {
    fun stop()
    val isRunning: Boolean
}
