package com.szgenle.agentpost.core.mail

/**
 * 待发邮件。mail 层的输入 DTO。
 *
 * - 首封信：[inReplyToMessageId] 和 [references] 都为 null/空，
 *   Subject 由用户自由填写，不加任何前缀
 * - 回复：[inReplyToMessageId] = 被回复邮件的 Message-ID；
 *   [references] = 被回复邮件的 References 链 + 被回复邮件自身 Message-ID；
 *   Subject 建议由上层按标准邮件协议加 "Re: " 前缀（如未存在）
 *
 * 所有 Message-ID 传入时**不要带**尖括号，Sender 内部会补齐。
 */
data class OutgoingMail(
    val toAddress: String,
    val subject: String,
    /** Markdown 或纯文本；MVP 阶段以 text/plain 发送。 */
    val body: String,
    val attachments: List<OutgoingAttachment> = emptyList(),

    val inReplyToMessageId: String? = null,
    val references: List<String> = emptyList(),
)

/**
 * 待发附件。
 *
 * 用字节数组最省事但占内存；MVP 阶段上层直接把 ContentResolver
 * openInputStream 读出来的 bytes 塞进来即可。后续需要流式发送再拆。
 */
data class OutgoingAttachment(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    // ByteArray 不参与 equals 语义，避免 data class 默认实现把附件内容拿去 hash
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
