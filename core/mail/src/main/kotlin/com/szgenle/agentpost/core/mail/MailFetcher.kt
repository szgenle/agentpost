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
     * - 不一致或本地未保存（null）→ 视为需要回溯。回溯**不再从 UID 1 整箱全扫**（历史
     *   老邮件对本 app 无意义，全扫既慢又容易撞上畸形老信拖垮整批），而是只捞最近
     *   若干天收到的邮件（IMAP SINCE），并把 [FetchBatch.highWaterUid] 设为当前邮箱最高
     *   UID——上层据此把水线一步推到顶，窗口之前的历史一律不再回看，之后只增量拉新邮件。
     *
     * @param credentials       账户凭据
     * @param sinceUid          只拉 UID 大于此值的邮件；0 = 从头（配合回溯窗口使用）
     * @param sinceUidValidity  本地保存的 UIDVALIDITY；null 表示尚未记录
     * @return [FetchBatch]，含成功解析的邮件（按 sentAt 升序）、解析失败的 UID、本次 UIDVALIDITY，
     *         以及回溯批次的 [FetchBatch.highWaterUid]
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
     * 按 UID + 附件在 walkParts 顺序中的序号拉取附件，流式写入 [target] 文件。
     *
     * 用于附件懒下载：syncInbox 时只存元数据，用户点击查看时再重开 IMAP 拉。
     * 序号语义必须与 [IncomingAttachment.partIndex] 一致（同一 walkParts 算法）。
     *
     * 内部用大块 partial fetch 直写磁盘（不整包进内存），下载过程中按已落盘字节数
     * 回调 [onProgress]（IO 线程，约每 128KB 一次），供 UI 显示百分比。
     *
     * @param target 落盘目标文件（调用方保证父目录存在；失败时可能留下半截文件，
     *               由调用方按需清理或直接覆盖重下）
     */
    @Throws(Exception::class)
    suspend fun fetchAttachment(
        credentials: MailCredentials,
        imapUid: Long,
        partIndex: String,
        target: java.io.File,
        onProgress: (Long) -> Unit = {},
    )

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
 * @property highWaterUid 仅"回溯窗口"批次（首次 / 换邮箱 / 手动重扫 / epoch 变化）非 null：
 *                      本次抓取时邮箱的最高 UID。上层据此把增量水线一步推到顶——回溯只捞
 *                      最近窗口，窗口之前的历史一律不再回看，因此水线直接落到顶端，后续增量
 *                      只拉真正新到达的邮件，永不触发整箱全扫。为 null 时按 [UidWatermarks.safeAdvance] 推进。
 */
data class FetchBatch(
    val mails: List<IncomingMail>,
    val failedUids: List<Long>,
    val uidValidity: Long,
    val highWaterUid: Long? = null,
)

/**
 * IDLE 推送会话句柄，调用方持有。stop() 安全关闭长连接、取消内部协程。
 */
interface MailPushSession {
    fun stop()
    val isRunning: Boolean
}
