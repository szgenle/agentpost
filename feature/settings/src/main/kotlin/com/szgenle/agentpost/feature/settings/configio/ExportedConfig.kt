package com.szgenle.agentpost.feature.settings.configio

import kotlinx.serialization.Serializable

/**
 * 配置导入导出顶层数据结构。
 *
 * 设计取向：
 * - 不直接序列化 Room/DataStore 里的内部模型，而是单独定义 Export 系列数据类，
 *   把"导出/导入对外的字段"与"内部存储字段"解耦，便于未来内部模型演进。
 * - 显式不含敏感字段：邮箱密码（明文）、credentialKey（Vault 中的引用 key）
 *   都不进 JSON。
 * - [version] 用作 schema 版本；当前 v1。导入时若版本高于本端能处理的最大值，
 *   立即返回 VersionUnsupported，避免吞下未识别字段。
 */
@Serializable
data class ExportedConfig(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long,
    val accounts: List<AccountExport>,
    val commandTemplates: List<CommandTemplateExport>,
    val fetchIntervals: FetchIntervalsExport,
    /** [com.szgenle.agentpost.core.datastore.CrashReportPref] 的枚举名。 */
    val crashReportPref: String,
) {
    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

/**
 * 账户导出结构。
 *
 * 显式剥离的敏感字段：
 * - id：本地 UUID，导入时按 type 匹配重新分配；
 * - credentialKey：指向 EncryptedSharedPreferences 的引用 key，泄露后可推断存储布局；
 * - 邮箱密码本身：永不导出，导入后由用户在"邮箱设置"补填。
 *
 * AGENT 账户的 IMAP/SMTP 字段在 MVP 阶段为空字符串/0，导出时按现状原样落盘；
 * 导入时 Importer 会忽略这些空字段，仅恢复 displayName + email。
 */
@Serializable
data class AccountExport(
    /** "SELF" / "AGENT"。导入时按本字符串映射回 [com.szgenle.agentpost.core.model.AccountType]。 */
    val type: String,
    val displayName: String,
    val email: String,
    val imapHost: String,
    val imapPort: Int,
    val imapUseSsl: Boolean,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpUseStartTls: Boolean,
)

/**
 * 命令模板导出结构。
 *
 * 显式不含 id：本地 UUID 在导出方与导入方之间无关联意义，导入时由 Importer 统一
 * 用 [java.util.UUID.randomUUID] 重新生成，避免与本地已有模板 id 冲突。
 */
@Serializable
data class CommandTemplateExport(
    val title: String,
    val subject: String,
    val body: String,
    val updatedAt: Long,
)

/** 收件间隔导出结构，与 [com.szgenle.agentpost.core.datastore.FetchIntervals] 字段一致。 */
@Serializable
data class FetchIntervalsExport(
    val foregroundSeconds: Int,
    val backgroundMinutes: Int,
)

/**
 * 导入时的合并策略。一次导入只能选一种，应用于所有可合并的数据类别。
 *
 * - [OVERWRITE]：账户/模板均覆盖；偏好（间隔、崩溃上报）覆盖。
 * - [MERGE_NEW_ONLY]：仅当本地不存在同 type 账户、不存在同 (title,subject) 模板时新增；
 *   偏好类（间隔、崩溃上报）整体跳过，保持本地现状。
 * - [SKIP_EXISTING]：与 [MERGE_NEW_ONLY] 在数据合并语义上等价；偏好类同样不动。
 *   保留语义独立的枚举值，便于未来对"已存在的项做提示"时区分意图。
 */
enum class MergeStrategy { OVERWRITE, MERGE_NEW_ONLY, SKIP_EXISTING }
