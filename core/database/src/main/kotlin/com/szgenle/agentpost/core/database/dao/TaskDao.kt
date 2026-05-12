package com.szgenle.agentpost.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.szgenle.agentpost.core.model.Task
import kotlinx.coroutines.flow.Flow

/**
 * 任务 DAO。任务列表按 `lastActivityAt` 倒序展示。
 *
 * 两种路由命中方式（见 HANDOVER 第 6 节）：
 * - [getByRootMessageId]：主路，In-Reply-To / References 已反查到 rootMessageId
 * - [findByTitleAndAgent]：兜底，Subject 去 Re: 规范化后精确匹配
 */
@Dao
interface TaskDao {

    @Upsert
    suspend fun upsert(task: Task)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): Task?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeById(id: String): Flow<Task?>

    @Query("SELECT * FROM tasks WHERE rootMessageId = :rootMessageId LIMIT 1")
    suspend fun getByRootMessageId(rootMessageId: String): Task?

    @Query(
        """
        SELECT * FROM tasks
        WHERE title = :title AND agentAccountId = :agentAccountId
        LIMIT 1
        """
    )
    suspend fun findByTitleAndAgent(title: String, agentAccountId: String): Task?

    @Query(
        """
        SELECT * FROM tasks
        WHERE archived = 0
        ORDER BY lastActivityAt DESC
        """
    )
    fun observeActive(): Flow<List<Task>>

    @Query(
        """
        SELECT * FROM tasks
        ORDER BY lastActivityAt DESC
        """
    )
    fun observeAll(): Flow<List<Task>>

    /**
     * 任务列表摘要：每条任务带上最新一条消息的 body/sentAt 与未读计数。
     * 子查询限定在同任务内，archived=0 且按 lastActivityAt 倒序。
     */
    @Query(
        """
        SELECT t.*,
          (SELECT body   FROM task_messages WHERE taskId = t.id ORDER BY sentAt DESC LIMIT 1) AS lastBody,
          (SELECT sentAt FROM task_messages WHERE taskId = t.id ORDER BY sentAt DESC LIMIT 1) AS lastSentAt,
          (SELECT COUNT(*) FROM task_messages WHERE taskId = t.id AND fromAgent = 1 AND isRead = 0) AS unreadCount
        FROM tasks t
        WHERE t.archived = 0
        ORDER BY t.lastActivityAt DESC
        """
    )
    fun observeActiveBriefs(): Flow<List<TaskBriefRow>>

    /**
     * 已归档任务摘要列表。仓库层会再过滤掉 `__UNCLASSIFIED__` 占位任务——
     * 它本身也带 archived=1，不能泄露到归档页。
     */
    @Query(
        """
        SELECT t.*,
          (SELECT body   FROM task_messages WHERE taskId = t.id ORDER BY sentAt DESC LIMIT 1) AS lastBody,
          (SELECT sentAt FROM task_messages WHERE taskId = t.id ORDER BY sentAt DESC LIMIT 1) AS lastSentAt,
          (SELECT COUNT(*) FROM task_messages WHERE taskId = t.id AND fromAgent = 1 AND isRead = 0) AS unreadCount
        FROM tasks t
        WHERE t.archived = 1
        ORDER BY t.lastActivityAt DESC
        """
    )
    fun observeArchivedBriefs(): Flow<List<TaskBriefRow>>

    @Query("UPDATE tasks SET lastActivityAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("UPDATE tasks SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)
}
