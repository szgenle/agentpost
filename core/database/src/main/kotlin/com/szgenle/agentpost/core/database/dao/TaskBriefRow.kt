package com.szgenle.agentpost.core.database.dao

import androidx.room.Embedded
import com.szgenle.agentpost.core.model.Task

/**
 * 任务列表摘要的联合查询行。给 [TaskDao.observeActiveBriefs] 用。
 *
 * 子查询可能为 null（任务尚无消息时），由上层决定兜底。
 *
 * @property task 任务本体，@Embedded 将 `tasks` 表所有列映射到 [Task]
 * @property lastBody 该任务最新一条消息的正文；null 表示任务还没有消息
 * @property lastSentAt 该任务最新一条消息的发送时间；null 同上
 * @property unreadCount 该任务未读消息数（fromAgent=1 AND isRead=0）
 */
data class TaskBriefRow(
    @Embedded val task: Task,
    val lastBody: String?,
    val lastSentAt: Long?,
    val unreadCount: Int,
)
