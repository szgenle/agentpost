package com.szgenle.agentpost.core.model

/**
 * 消息发送状态机。
 *
 * 状态流转：
 * - 收到的邮件（fromAgent=true）：直接入库 [SENT]（默认值）。
 * - 本机发出：[PENDING] → [SENDING] → [SENT]（成功） 或 [FAILED]（SMTP 抛错）。
 * - 失败重试：[FAILED] → [SENDING] → [SENT] 或 [FAILED]。
 *
 * 说明：本枚举只负责"消息是否已进邮件系统"，与 IMAP 的 SEEN flag（[TaskMessage.isRead]）解耦。
 */
enum class SendStatus {
    /** 收到的邮件，或本机发出已成功的消息。默认态，绝大多数数据库行都是它。 */
    SENT,

    /** 本地草稿已落库，等待发送循环拉起。MVP 阶段暂不调度，立即进 SENDING。 */
    PENDING,

    /** 正在调用 SMTP。UI 显示为灰色气泡或菊花。 */
    SENDING,

    /** SMTP 发送失败。UI 显示红色气泡 + 重试按钮。 */
    FAILED,
}
