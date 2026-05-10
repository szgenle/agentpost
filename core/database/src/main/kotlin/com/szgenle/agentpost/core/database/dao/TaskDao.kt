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

    @Query("UPDATE tasks SET lastActivityAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("UPDATE tasks SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)
}
