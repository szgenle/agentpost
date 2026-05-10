package com.szgenle.agentpost.core.mail

/**
 * 发送邮件。
 *
 * MVP 阶段由 core:data 的 Repository 直接调用，不走 WorkManager——
 * 用户点"发送"是前台动作，失败直接弹错；重试由用户手动触发。
 */
interface MailSender {

    /**
     * 发送一封邮件。
     *
     * 成功返回发出的 Message-ID（不带尖括号），上层需立刻持久化
     * 到 [com.szgenle.agentpost.core.model.TaskMessage.messageId]，
     * 用作后续路由回信的锚点。
     *
     * 所有失败统一抛异常。调用方负责切线程（IO）。
     */
    @Throws(Exception::class)
    suspend fun send(credentials: MailCredentials, mail: OutgoingMail): String
}
