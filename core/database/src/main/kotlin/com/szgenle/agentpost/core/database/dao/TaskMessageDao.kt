package com.szgenle.agentpost.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.szgenle.agentpost.core.model.SendStatus
import com.szgenle.agentpost.core.model.TaskMessage
import kotlinx.coroutines.flow.Flow

/**
 * 消息 DAO。
 *
 * 主键 `id` = 本地 UUID，永远存在；`externalMessageId` = 邮件原生 Message-ID，
 * 仅当消息走过 SMTP 并拿到回执、或从 IMAP 收取时才会被填入，带唯一索引用于去重。
 */
@Dao
interface TaskMessageDao {

    @Upsert
    suspend fun upsert(message: TaskMessage)

    @Upsert
    suspend fun upsertAll(messages: List<TaskMessage>)

    @Query("SELECT * FROM task_messages WHERE id = :id")
    suspend fun getById(id: String): TaskMessage?

    @Query("SELECT * FROM task_messages WHERE externalMessageId = :externalMessageId")
    suspend fun getByExternalMessageId(externalMessageId: String): TaskMessage?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM task_messages WHERE externalMessageId = :externalMessageId)"
    )
    suspend fun existsByExternalMessageId(externalMessageId: String): Boolean

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

    @Query("UPDATE task_messages SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE task_messages SET isRead = 1 WHERE taskId = :taskId")
    suspend fun markAllReadInTask(taskId: String)

    /**
     * 取指定 Task 下按时间升序的已送达 Message-ID，用于拼回复 References 链。
     * 草稿 / SENDING / FAILED 尚无 Message-ID，被 WHERE 过滤掉。
     */
    @Query(
        """
        SELECT externalMessageId FROM task_messages
        WHERE taskId = :taskId AND externalMessageId IS NOT NULL
        ORDER BY sentAt ASC
        """
    )
    suspend fun externalMessageIdsByTaskOrderBySentAtAsc(taskId: String): List<String>

    /**
     * 单步更新发送状态。
     *
     * - 发送开始：status=SENDING，externalMessageId/sendError 维持 null
     * - 发送成功：status=SENT，externalMessageId=<Message-ID>，sendError=null，sentAt 可校准
     * - 发送失败：status=FAILED，externalMessageId=null，sendError=<原因>
     */
    @Query(
        """
        UPDATE task_messages
        SET sendStatus = :status,
            externalMessageId = :externalMessageId,
            sendError = :sendError,
            sentAt = :sentAt
        WHERE id = :id
        """
    )
    suspend fun updateSendStatus(
        id: String,
        status: SendStatus,
        externalMessageId: String?,
        sendError: String?,
        sentAt: Long,
    )
}
