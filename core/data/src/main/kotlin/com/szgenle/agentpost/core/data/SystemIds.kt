package com.szgenle.agentpost.core.data

/**
 * 跨模块共享的系统保留 ID。
 */
object SystemIds {

    /**
     * 未归类占位 Task 的 ID。
     *
     * 所有路由没命中任何 Task 的来信，都落在 `taskId = UNCLASSIFIED_TASK_ID` 下。
     * 对应的 Task 行由 [com.szgenle.agentpost.core.data.MailRepository]
     * 在首次同步前按需创建（`agentAccountId` 复用 SELF.id 以满足外键约束）。
     *
     * 列表 UI 读取任务时应主动过滤掉此 ID。
     */
    const val UNCLASSIFIED_TASK_ID: String = "__UNCLASSIFIED__"
}
