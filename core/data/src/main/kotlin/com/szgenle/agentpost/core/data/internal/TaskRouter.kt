package com.szgenle.agentpost.core.data.internal

import com.szgenle.agentpost.core.common.mail.SubjectNormalizer
import com.szgenle.agentpost.core.data.SystemIds
import com.szgenle.agentpost.core.database.dao.TaskDao
import com.szgenle.agentpost.core.database.dao.TaskMessageDao
import com.szgenle.agentpost.core.mail.IncomingMail

/**
 * 来信路由到 Task 的策略实现（见 HANDOVER 第 6 节）。
 *
 * 优先级（命中即返回）：
 * 1. In-Reply-To → 已存 TaskMessage.externalMessageId → taskId
 * 2. References 倒序遍历 → 任一命中已存 TaskMessage.externalMessageId → taskId
 * 3. References 首元素 → 匹配 Task.rootMessageId → taskId
 * 4. 兜底：Subject 去 Re: 后精确匹配 Task.title + agentAccountId
 * 5. 全未命中：返回 [SystemIds.UNCLASSIFIED_TASK_ID]
 */
internal class TaskRouter(
    private val taskDao: TaskDao,
    private val messageDao: TaskMessageDao,
) {

    suspend fun route(
        mail: IncomingMail,
        agentAccountId: String,
    ): String {
        // 1. In-Reply-To
        mail.inReplyTo?.let { inReplyTo ->
            messageDao.getByExternalMessageId(inReplyTo)?.let { return it.taskId }
        }

        // 2. References 倒序（最近的先查）
        for (ref in mail.references.asReversed()) {
            messageDao.getByExternalMessageId(ref)?.let { return it.taskId }
        }

        // 3. References 首元素 vs rootMessageId
        mail.references.firstOrNull()?.let { rootRef ->
            taskDao.getByRootMessageId(rootRef)?.let { return it.id }
        }

        // 4. 兜底：Subject 规范化 + agentAccountId
        val normalizedTitle = SubjectNormalizer.normalize(mail.subject)
        if (normalizedTitle.isNotEmpty()) {
            taskDao.findByTitleAndAgent(normalizedTitle, agentAccountId)?.let { return it.id }
        }

        // 5. 未归类
        return SystemIds.UNCLASSIFIED_TASK_ID
    }
}
