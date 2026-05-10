package com.szgenle.agentpost.core.mail

/**
 * 一封解析后的来信。mail 层的输出 DTO，**不是** Room Entity。
 *
 * 字段尽量保留邮件原貌，不做任何业务转换；路由、去 Re: 规范化、
 * 是否入库等决策都交给 core:data 的 Repository。
 *
 * 设计要点：
 * - [messageId] / [inReplyTo] 都已去掉尖括号，方便 equals / SQL 查询
 * - [references] 保留整条线程链（按邮件 header 顺序），首元素最老
 * - [attachmentParts] 只记附件 metadata + 一个读字节流的惰性函数，
 *   默认不把附件全部读进内存；下载时机由上层决定
 */
data class IncomingMail(
    val messageId: String,
    val inReplyTo: String?,
    val references: List<String>,
    val subject: String,
    val fromAddress: String,
    val toAddresses: List<String>,
    val sentAt: Long,
    /** 正文，优先 text/plain；没有才降级 text/html（简单剥 HTML）。 */
    val body: String,
    /** 附件元信息 + 读流回调。 */
    val attachmentParts: List<IncomingAttachment>,
    /** IMAP SEEN flag 是否已置。 */
    val seen: Boolean,
    /** IMAP UID，调试 / 调接口补拉时用。 */
    val imapUid: Long,
)

/**
 * 来信中的单个附件（不立刻下载）。
 */
data class IncomingAttachment(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /**
     * 懒加载：调用时才真正读字节流。
     * MVP 阶段 UI 点开时才下载，列表展示只用 metadata。
     */
    val openStream: () -> java.io.InputStream,
)
