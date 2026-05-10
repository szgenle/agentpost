package com.szgenle.agentpost.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.szgenle.agentpost.core.model.TaskMessage
import kotlinx.coroutines.flow.Flow

/**
 * 消息 DAO。主键 `messageId` = 邮件原生 Message-ID，天然去重。
 */
@Dao
interface TaskMessageDao {

    @Upsert
    suspend fun upsert(message: TaskMessage)

    @Upsert
    suspend fun upsertAll(messages: List<TaskMessage>)

    @Query("SELECT * FROM task_messages WHERE messageId = :messageId")
    suspend fun getByMessageId(messageId: String): TaskMessage?

    @Query("SELECT EXISTS(SELECT 1 FROM task_messages WHERE messageId = :messageId)")
    suspend fun exists(messageId: String): Boolean

    @Query(
        """
        SELECT * FROM task_messages
        WHERE taskId = :taskId
        ORDER BY sentAt ASC
        """
    )
    fun observeByTaskId(taskId: String): Flow<List<TaskMessage>>

    @Query(
        """
        SELECT * FROM task_messages
        WHERE taskId = :taskId
        ORDER BY sentAt DESC
        LIMIT 1
        """
    )
    fun observeLatestByTaskId(taskId: String): Flow<TaskMessage?>

    @Query(
        """
        SELECT COUNT(*) FROM task_messages
        WHERE taskId = :taskId AND fromAgent = 1 AND isRead = 0
        """
    )
    fun observeUnreadCount(taskId: String): Flow<Int>

    @Query("UPDATE task_messages SET isRead = 1 WHERE messageId = :messageId")
    suspend fun markRead(messageId: String)

    @Query("UPDATE task_messages SET isRead = 1 WHERE taskId = :taskId")
    suspend fun markAllReadInTask(taskId: String)

    /**
     * 取指定 Task 下按时间升序的所有 Message-ID。
     * 用于回复时拼 References 链。没有消息时返回空列表。
     */
    @Query(
        """
        SELECT messageId FROM task_messages
        WHERE taskId = :taskId
        ORDER BY sentAt ASC
        """
    )
    suspend fun messageIdsByTaskOrderBySentAtAscOrNull(taskId: String): List<String>
}
