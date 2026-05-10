package com.szgenle.agentpost.core.mail

import com.szgenle.agentpost.core.mail.internal.JakartaMailFetcher
import com.szgenle.agentpost.core.mail.internal.JakartaMailSender

/**
 * mail 层对外的工厂入口。
 *
 * core:data 层通过这里拿到 Sender / Fetcher 实例，不直接 new 实现类——
 * 内部类保持 `internal`，MVP 阶段切换实现或加装饰器都不影响上层。
 */
object MailProviders {

    fun sender(): MailSender = JakartaMailSender()

    fun fetcher(): MailFetcher = JakartaMailFetcher()
}
