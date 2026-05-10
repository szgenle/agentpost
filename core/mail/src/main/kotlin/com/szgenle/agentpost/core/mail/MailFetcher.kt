package com.szgenle.agentpost.core.mail

/**
 * 拉取邮件（IMAP 轮询）。
 *
 * MVP 阶段不启用 IDLE，靠 [WorkManager] 15 分钟一次调度；
 * 前台停留时前台 Coroutine 再起一个 30s 轮询，复用同一个 Fetcher。
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
}
