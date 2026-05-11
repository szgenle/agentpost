package com.szgenle.agentpost.core.model

/**
 * 命令模板：用户预先固化的常用指令文本，可一键填入「新建任务」页面的 subject / body。
 *
 * v1 特意不做：
 * - 占位符（`{{today}}` / `{{input}}`）替换
 * - 内置模板预置、导入导出
 * - 标签 / 分组
 *
 * 存储选型见 [com.szgenle.agentpost.core.datastore.AppPreferences]：
 * 整张列表序列化为 JSON 字符串放入 DataStore Preferences，由 UI 层负责维护顺序。
 */
data class CommandTemplate(
    /** 本地 UUID，仅用于列表 key / 更新时匹配。不参与邮件 header。 */
    val id: String,
    /** 显示名：模板选择器里作为 headline。 */
    val title: String,
    /** 填入 NewTaskRoute 的 subject 字段。 */
    val subject: String,
    /** 填入 NewTaskRoute 的 body 字段。 */
    val body: String,
    /** 最近一次新建或编辑时间（epoch ms），便于后续排序 / 审计。 */
    val updatedAt: Long,
)
