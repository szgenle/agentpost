package com.szgenle.agentpost.core.data.internal

import com.szgenle.agentpost.core.data.SystemIds
import com.szgenle.agentpost.core.database.dao.TaskBriefRow
import com.szgenle.agentpost.core.database.dao.TaskDao
import com.szgenle.agentpost.core.database.dao.TaskMessageDao
import com.szgenle.agentpost.core.mail.IncomingMail
import com.szgenle.agentpost.core.model.SendStatus
import com.szgenle.agentpost.core.model.Task
import com.szgenle.agentpost.core.model.TaskMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TaskRouter] 五级路由规则回归测试。
 *
 * 优先级（命中即返回）：
 * 1. In-Reply-To → 已存 TaskMessage.externalMessageId
 * 2. References 倒序遍历 → 任一命中已存 TaskMessage.externalMessageId
 * 3. References 首元素 → 匹配 Task.rootMessageId
 * 4. Subject 规范化 + agentAccountId 精确匹配 Task.title
 * 5. 全未命中 → UNCLASSIFIED_TASK_ID
 *
 * 用 fake DAO 隔离 Room，验证决策逻辑 + 规则优先级。
 */
class TaskRouterTest {

    private val agentId = "agent-1"

    // ---- 规则 1：In-Reply-To ----

    @Test
    fun `rule 1 - inReplyTo matches existing message`() = runBlocking {
        val existing = message(id = "m-existing", externalId = "ext-1", taskId = "t-1")
        val messageDao = FakeTaskMessageDao(byExternal = mapOf("ext-1" to existing))
        val taskDao = FakeTaskDao()
        val router = TaskRouter(taskDao, messageDao)

        val mail = incoming(inReplyTo = "ext-1", references = emptyList())

        assertEquals("t-1", router.route(mail, agentId))
    }

    // ---- 规则 2：References 倒序 ----

    @Test
    fun `rule 2 - references are probed in reverse order`() = runBlocking {
        // references = [oldest, middle, newest]；倒序遍历应先命中 newest
        val msgNewest = message(id = "m-new", externalId = "ref-newest", taskId = "t-new")
        val msgOldest = message(id = "m-old", externalId = "ref-oldest", taskId = "t-old")
        val messageDao = FakeTaskMessageDao(
            byExternal = mapOf(
                "ref-newest" to msgNewest,
                "ref-oldest" to msgOldest,
            )
        )
        val taskDao = FakeTaskDao()
        val router = TaskRouter(taskDao, messageDao)

        val mail = incoming(
            inReplyTo = null,
            references = listOf("ref-oldest", "ref-middle", "ref-newest"),
        )

        // 倒序：先查 newest → 命中即返回 t-new
        assertEquals("t-new", router.route(mail, agentId))
    }

    @Test
    fun `rule 2 - falls through when newer references miss but an older one hits`() = runBlocking {
        // 只有 ref-oldest 命中，倒序遍历要一直退到最后才命中
        val msgOldest = message(id = "m-old", externalId = "ref-oldest", taskId = "t-old")
        val messageDao = FakeTaskMessageDao(
            byExternal = mapOf("ref-oldest" to msgOldest),
        )
        val taskDao = FakeTaskDao()
        val router = TaskRouter(taskDao, messageDao)

        val mail = incoming(
            inReplyTo = null,
            references = listOf("ref-oldest", "ref-middle", "ref-newest"),
        )

        assertEquals("t-old", router.route(mail, agentId))
    }

    // ---- 规则 3：References 首元素 → rootMessageId ----

    @Test
    fun `rule 3 - references first element matches task rootMessageId`() = runBlocking {
        // 所有 reference 都没入库（规则 2 全空），但首元素匹配 Task.rootMessageId
        val task = task(id = "t-root", root = "ref-root")
        val taskDao = FakeTaskDao(byRoot = mapOf("ref-root" to task))
        val messageDao = FakeTaskMessageDao()
        val router = TaskRouter(taskDao, messageDao)

        val mail = incoming(
            inReplyTo = null,
            references = listOf("ref-root", "ref-other"),
        )

        assertEquals("t-root", router.route(mail, agentId))
    }

    // ---- 规则 4：Subject 规范化兜底 ----

    @Test
    fun `rule 4 - subject normalized and matched against task title plus agent`() = runBlocking {
        val task = task(id = "t-fallback", title = "整理笔记", agentId = agentId)
        val taskDao = FakeTaskDao(byTitleAgent = mapOf(("整理笔记" to agentId) to task))
        val messageDao = FakeTaskMessageDao()
        val router = TaskRouter(taskDao, messageDao)

        val mail = incoming(
            inReplyTo = null,
            references = emptyList(),
            subject = "Re: Fwd: 整理笔记",
        )

        assertEquals("t-fallback", router.route(mail, agentId))
    }

    @Test
    fun `rule 4 - subject match is scoped by agentAccountId`() = runBlocking {
        // 同名 Task 归属另一个 agent，不应被命中
        val task = task(id = "t-other-agent", title = "整理笔记", agentId = "agent-2")
        val taskDao = FakeTaskDao(byTitleAgent = mapOf(("整理笔记" to "agent-2") to task))
        val messageDao = FakeTaskMessageDao()
        val router = TaskRouter(taskDao, messageDao)

        val mail = incoming(
            inReplyTo = null,
            references = emptyList(),
            subject = "Re: 整理笔记",
        )

        assertEquals(SystemIds.UNCLASSIFIED_TASK_ID, router.route(mail, agentId))
    }

    @Test
    fun `rule 4 - skipped when normalized subject is empty`() = runBlocking {
        // subject 全由前缀构成 → normalize 后为空串 → 规则 4 不触发
        val taskDao = FakeTaskDao()
        val messageDao = FakeTaskMessageDao()
        val router = TaskRouter(taskDao, messageDao)

        val mail = incoming(
            inReplyTo = null,
            references = emptyList(),
            subject = "Re: Fwd:",
        )

        assertEquals(SystemIds.UNCLASSIFIED_TASK_ID, router.route(mail, agentId))
        // 确认 DAO 的 title 查询没被调用（normalize 为空时应直接跳过）
        assertEquals(0, taskDao.findByTitleAndAgentCallCount)
    }

    // ---- 规则 5：未归类兜底 ----

    @Test
    fun `rule 5 - returns unclassified when nothing matches`() = runBlocking {
        val taskDao = FakeTaskDao()
        val messageDao = FakeTaskMessageDao()
        val router = TaskRouter(taskDao, messageDao)

        val mail = incoming(
            inReplyTo = "unknown-in-reply-to",
            references = listOf("unknown-ref"),
            subject = "陌生任务",
        )

        assertEquals(SystemIds.UNCLASSIFIED_TASK_ID, router.route(mail, agentId))
    }

    @Test
    fun `rule 5 - empty references and no inReplyTo falls through to unclassified`() = runBlocking {
        val taskDao = FakeTaskDao()
        val messageDao = FakeTaskMessageDao()
        val router = TaskRouter(taskDao, messageDao)

        val mail = incoming(inReplyTo = null, references = emptyList(), subject = "新任务")

        assertEquals(SystemIds.UNCLASSIFIED_TASK_ID, router.route(mail, agentId))
    }

    // ---- 优先级：高优先级命中时低优先级不被调用 ----

    @Test
    fun `priority - rule 1 short-circuits rule 2 and below`() = runBlocking {
        val msgIn = message(id = "m-1", externalId = "ext-in", taskId = "t-in")
        val msgRef = message(id = "m-2", externalId = "ref-1", taskId = "t-ref")
        val task = task(id = "t-root", root = "ref-1")
        val taskDao = FakeTaskDao(
            byRoot = mapOf("ref-1" to task),
            byTitleAgent = mapOf(("整理笔记" to agentId) to task),
        )
        val messageDao = FakeTaskMessageDao(
            byExternal = mapOf(
                "ext-in" to msgIn,
                "ref-1" to msgRef,
            )
        )
        val router = TaskRouter(taskDao, messageDao)

        val mail = incoming(
            inReplyTo = "ext-in",
            references = listOf("ref-1"),
            subject = "Re: 整理笔记",
        )

        // 规则 1 最先命中，后续 DAO 不该再被查
        assertEquals("t-in", router.route(mail, agentId))
        // 规则 1 已返回 → 规则 3 / 4 的查询均未发生
        assertEquals(0, taskDao.getByRootMessageIdCallCount)
        assertEquals(0, taskDao.findByTitleAndAgentCallCount)
        // 规则 2 的 references 也不该被遍历
        assertTrue(
            "references lookup should not happen when rule 1 hits",
            "ref-1" !in messageDao.externalIdLookups.filter { it != "ext-in" },
        )
    }

    @Test
    fun `priority - rule 2 short-circuits rule 3 and 4`() = runBlocking {
        val msgRef = message(id = "m-ref", externalId = "ref-1", taskId = "t-ref")
        val task = task(id = "t-root", root = "ref-1", title = "整理笔记", agentId = agentId)
        val taskDao = FakeTaskDao(
            byRoot = mapOf("ref-1" to task),
            byTitleAgent = mapOf(("整理笔记" to agentId) to task),
        )
        val messageDao = FakeTaskMessageDao(byExternal = mapOf("ref-1" to msgRef))
        val router = TaskRouter(taskDao, messageDao)

        val mail = incoming(
            inReplyTo = null,
            references = listOf("ref-1"),
            subject = "Re: 整理笔记",
        )

        assertEquals("t-ref", router.route(mail, agentId))
        // 规则 2 命中 → 规则 3、4 不查
        assertEquals(0, taskDao.getByRootMessageIdCallCount)
        assertEquals(0, taskDao.findByTitleAndAgentCallCount)
    }

    @Test
    fun `priority - rule 3 short-circuits rule 4`() = runBlocking {
        val task = task(id = "t-root", root = "ref-root", title = "整理笔记", agentId = agentId)
        val taskDao = FakeTaskDao(
            byRoot = mapOf("ref-root" to task),
            byTitleAgent = mapOf(("整理笔记" to agentId) to task),
        )
        val messageDao = FakeTaskMessageDao()
        val router = TaskRouter(taskDao, messageDao)

        val mail = incoming(
            inReplyTo = null,
            references = listOf("ref-root"),
            subject = "Re: 整理笔记",
        )

        assertEquals("t-root", router.route(mail, agentId))
        // 规则 3 命中 → 规则 4 不查
        assertEquals(0, taskDao.findByTitleAndAgentCallCount)
    }

    // ============================================================
    // Helpers
    // ============================================================

    private fun incoming(
        inReplyTo: String?,
        references: List<String>,
        subject: String = "untitled",
        messageId: String = "incoming-${System.nanoTime()}",
    ) = IncomingMail(
        messageId = messageId,
        inReplyTo = inReplyTo,
        references = references,
        subject = subject,
        fromAddress = "agent@example.com",
        toAddresses = listOf("me@example.com"),
        sentAt = 0L,
        body = "body",
        attachmentParts = emptyList(),
        seen = false,
        imapUid = 1L,
    )

    private fun task(
        id: String,
        root: String = "root-$id",
        title: String = "title-$id",
        agentId: String = this.agentId,
    ) = Task(
        id = id,
        rootMessageId = root,
        title = title,
        agentAccountId = agentId,
        createdAt = 0L,
        lastActivityAt = 0L,
    )

    private fun message(
        id: String,
        externalId: String?,
        taskId: String,
    ) = TaskMessage(
        id = id,
        externalMessageId = externalId,
        taskId = taskId,
        fromAgent = true,
        subject = "s",
        body = "b",
        attachments = emptyList(),
        inReplyTo = null,
        sentAt = 0L,
        isRead = false,
        sendStatus = SendStatus.SENT,
        sendError = null,
    )
}

/**
 * 仅实现 [TaskRouter] 用到的 DAO 方法。其余未使用方法抛错以尽早暴露误用。
 */
private class FakeTaskDao(
    private val byRoot: Map<String, Task> = emptyMap(),
    private val byTitleAgent: Map<Pair<String, String>, Task> = emptyMap(),
) : TaskDao {

    var getByRootMessageIdCallCount = 0
        private set
    var findByTitleAndAgentCallCount = 0
        private set

    override suspend fun getByRootMessageId(rootMessageId: String): Task? {
        getByRootMessageIdCallCount++
        return byRoot[rootMessageId]
    }

    override suspend fun findByTitleAndAgent(title: String, agentAccountId: String): Task? {
        findByTitleAndAgentCallCount++
        return byTitleAgent[title to agentAccountId]
    }

    // 以下方法 Router 不会触达
    override suspend fun upsert(task: Task) = error("unused by TaskRouter")
    override suspend fun getById(id: String): Task? = error("unused by TaskRouter")
    override fun observeById(id: String): Flow<Task?> = error("unused by TaskRouter")
    override fun observeActive(): Flow<List<Task>> = error("unused by TaskRouter")
    override fun observeAll(): Flow<List<Task>> = error("unused by TaskRouter")
    override fun observeActiveBriefs(): Flow<List<TaskBriefRow>> = error("unused by TaskRouter")
    override suspend fun touch(id: String, at: Long) = error("unused by TaskRouter")
    override suspend fun setArchived(id: String, archived: Boolean) = error("unused by TaskRouter")
}

private class FakeTaskMessageDao(
    private val byExternal: Map<String, TaskMessage> = emptyMap(),
) : TaskMessageDao {

    // 记录每次按 externalMessageId 的查询，用于断言规则优先级
    val externalIdLookups = mutableListOf<String>()

    override suspend fun getByExternalMessageId(externalMessageId: String): TaskMessage? {
        externalIdLookups += externalMessageId
        return byExternal[externalMessageId]
    }

    // 以下方法 Router 不会触达
    override suspend fun upsert(message: TaskMessage) = error("unused by TaskRouter")
    override suspend fun upsertAll(messages: List<TaskMessage>) = error("unused by TaskRouter")
    override suspend fun getById(id: String): TaskMessage? = error("unused by TaskRouter")
    override suspend fun existsByExternalMessageId(externalMessageId: String): Boolean =
        error("unused by TaskRouter")
    override fun observeByTaskId(taskId: String): Flow<List<TaskMessage>> =
        error("unused by TaskRouter")
    override fun observeLatestByTaskId(taskId: String): Flow<TaskMessage?> =
        error("unused by TaskRouter")
    override fun observeUnreadCount(taskId: String): Flow<Int> = error("unused by TaskRouter")
    override suspend fun markRead(id: String) = error("unused by TaskRouter")
    override suspend fun markAllReadInTask(taskId: String) = error("unused by TaskRouter")
    override suspend fun externalMessageIdsByTaskOrderBySentAtAsc(taskId: String): List<String> =
        error("unused by TaskRouter")
    override suspend fun updateSendStatus(
        id: String,
        status: SendStatus,
        externalMessageId: String?,
        sendError: String?,
        sentAt: Long,
    ) = error("unused by TaskRouter")
}
