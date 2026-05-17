package com.szgenle.agentpost.feature.settings.configio

import android.content.Context
import android.net.Uri
import com.szgenle.agentpost.core.common.zip.DecryptResult
import com.szgenle.agentpost.core.common.zip.ZipDecryptor
import com.szgenle.agentpost.core.data.MailRepository
import com.szgenle.agentpost.core.datastore.AppPreferences
import com.szgenle.agentpost.core.datastore.CrashReportPref
import com.szgenle.agentpost.core.model.AccountType
import com.szgenle.agentpost.core.model.CommandTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.util.UUID

/**
 * 配置导入器。两段式工作流：
 * 1. [readAndDecrypt]：从 SAF Uri 读 ZIP → 解密 → 反序列化 → 校验版本/字段；
 * 2. [apply]：按用户选定的 [MergeStrategy] 把 [ExportedConfig] 落到本地存储。
 *
 * 解析阶段不修改任何本地状态，便于在 UI 上先弹合并策略对话框再写入。
 */
class ConfigImporter(
    private val repo: MailRepository,
    private val prefs: AppPreferences,
) {

    /**
     * 读取 [inputUri] 指向的加密 ZIP，使用 [password] 解密并解析为 [ExportedConfig]。
     *
     * - 密码错 → [ImportResult.WrongPassword]；
     * - JSON 格式无效 / 必需字段缺失 / 字段值非法 → [ImportResult.Malformed]；
     * - 文件版本高于本端能处理的最大值 → [ImportResult.VersionUnsupported]；
     * - 其他 IO 异常 → [ImportResult.Failure]。
     */
    suspend fun readAndDecrypt(
        inputUri: Uri,
        password: String,
        ctx: Context,
    ): ImportResult = withContext(Dispatchers.IO) {
        val workDir = ConfigExporter.workDir(ctx)
        runCatching {
            workDir.mkdirs()
            val tmpZip = File(workDir, "import-${System.currentTimeMillis()}.zip")
            ctx.contentResolver.openInputStream(inputUri)?.use { input ->
                tmpZip.outputStream().use { input.copyTo(it) }
            } ?: throw java.io.IOException("ContentResolver.openInputStream returned null")

            val outDir = File(workDir, "extract-${System.currentTimeMillis()}")
            when (val r = ZipDecryptor.decrypt(tmpZip, outDir, password)) {
                is DecryptResult.WrongPassword -> ImportResult.WrongPassword
                is DecryptResult.Failure -> ImportResult.Failure(r.error)
                is DecryptResult.Success -> {
                    val jsonFile = r.files.firstOrNull { it.name == ConfigExporter.ENTRY_NAME }
                        ?: r.files.firstOrNull { it.extension.equals("json", ignoreCase = true) }
                    if (jsonFile == null || !jsonFile.isFile) {
                        ImportResult.Malformed("missing ${ConfigExporter.ENTRY_NAME}")
                    } else {
                        parse(jsonFile.readText())
                    }
                }
            }
        }.getOrElse { e ->
            ImportResult.Failure(e)
        }
    }

    /**
     * 把已解析的 [ExportedConfig] 按 [strategy] 写入本地存储。
     *
     * 三类数据的处理：
     * - 账户：按 [AccountType] 唯一性比对；OVERWRITE 覆盖非密码字段，MERGE/SKIP
     *   仅在该 type 缺失时新建；密码字段一律不动。
     * - 命令模板：按 (title, subject) 二元组判定"已存在"；新模板分配新 UUID。
     * - 偏好（间隔 / 崩溃上报）：仅 OVERWRITE 时覆盖；其他策略保持本地现状。
     */
    suspend fun apply(config: ExportedConfig, strategy: MergeStrategy) {
        applyAccounts(config.accounts, strategy)
        applyTemplates(config.commandTemplates, strategy)
        if (strategy == MergeStrategy.OVERWRITE) {
            prefs.setFetchIntervals(
                foregroundSeconds = config.fetchIntervals.foregroundSeconds,
                backgroundMinutes = config.fetchIntervals.backgroundMinutes,
            )
            val crash = runCatching { CrashReportPref.valueOf(config.crashReportPref) }
                .getOrDefault(CrashReportPref.ASK_EACH_TIME)
            prefs.setCrashReportPref(crash)
        }
    }

    /**
     * 计算"如果以 [strategy] 应用 [config]，将实际产生写入的账户/模板条数"。
     * 用于在策略对话框上向用户展示影响范围摘要。
     */
    suspend fun summarize(config: ExportedConfig, strategy: MergeStrategy): ImportSummary {
        val existingTemplates = prefs.getCommandTemplates()
        val existingKeys = existingTemplates.map { it.title to it.subject }.toSet()
        val accountsToWrite = when (strategy) {
            MergeStrategy.OVERWRITE -> config.accounts.size
            MergeStrategy.MERGE_NEW_ONLY, MergeStrategy.SKIP_EXISTING -> {
                var count = 0
                for (a in config.accounts) {
                    val type = parseType(a.type) ?: continue
                    val existing = when (type) {
                        AccountType.SELF -> repo.getSelfAccount()
                        AccountType.AGENT -> repo.getAgentAccount()
                    }
                    if (existing == null) count++
                }
                count
            }
        }
        val templatesToWrite = when (strategy) {
            MergeStrategy.OVERWRITE -> config.commandTemplates.size
            MergeStrategy.MERGE_NEW_ONLY, MergeStrategy.SKIP_EXISTING ->
                config.commandTemplates.count { (it.title to it.subject) !in existingKeys }
        }
        return ImportSummary(
            totalAccounts = config.accounts.size,
            totalTemplates = config.commandTemplates.size,
            accountsToWrite = accountsToWrite,
            templatesToWrite = templatesToWrite,
        )
    }

    private suspend fun applyAccounts(list: List<AccountExport>, strategy: MergeStrategy) {
        for (a in list) {
            val type = parseType(a.type) ?: continue
            when (type) {
                AccountType.SELF -> {
                    val existing = repo.getSelfAccount()
                    val shouldWrite = when (strategy) {
                        MergeStrategy.OVERWRITE -> true
                        MergeStrategy.MERGE_NEW_ONLY, MergeStrategy.SKIP_EXISTING -> existing == null
                    }
                    if (!shouldWrite) continue
                    repo.applyImportedSelfAccount(
                        displayName = a.displayName,
                        email = a.email,
                        imapHost = a.imapHost,
                        imapPort = a.imapPort,
                        imapUseSsl = a.imapUseSsl,
                        smtpHost = a.smtpHost,
                        smtpPort = a.smtpPort,
                        smtpUseStartTls = a.smtpUseStartTls,
                    )
                }
                AccountType.AGENT -> {
                    val existing = repo.getAgentAccount()
                    val shouldWrite = when (strategy) {
                        MergeStrategy.OVERWRITE -> true
                        MergeStrategy.MERGE_NEW_ONLY, MergeStrategy.SKIP_EXISTING -> existing == null
                    }
                    if (!shouldWrite) continue
                    repo.saveAgentAccount(
                        displayName = a.displayName.ifBlank { a.email },
                        email = a.email,
                    )
                }
            }
        }
    }

    private suspend fun applyTemplates(list: List<CommandTemplateExport>, strategy: MergeStrategy) {
        if (list.isEmpty() && strategy != MergeStrategy.OVERWRITE) return
        val existing = prefs.getCommandTemplates()
        val now = System.currentTimeMillis()
        val merged: List<CommandTemplate> = when (strategy) {
            MergeStrategy.OVERWRITE -> list.map {
                CommandTemplate(
                    id = UUID.randomUUID().toString(),
                    title = it.title,
                    subject = it.subject,
                    body = it.body,
                    updatedAt = if (it.updatedAt > 0) it.updatedAt else now,
                )
            }
            MergeStrategy.MERGE_NEW_ONLY, MergeStrategy.SKIP_EXISTING -> {
                val keys = existing.map { it.title to it.subject }.toMutableSet()
                val appended = list.mapNotNull { item ->
                    val key = item.title to item.subject
                    if (key in keys) {
                        null
                    } else {
                        keys.add(key)
                        CommandTemplate(
                            id = UUID.randomUUID().toString(),
                            title = item.title,
                            subject = item.subject,
                            body = item.body,
                            updatedAt = if (item.updatedAt > 0) item.updatedAt else now,
                        )
                    }
                }
                existing + appended
            }
        }
        prefs.setCommandTemplates(merged)
    }

    private fun parseType(raw: String): AccountType? =
        runCatching { AccountType.valueOf(raw) }.getOrNull()

    private fun parse(raw: String): ImportResult {
        val parsed = try {
            ConfigExporter.JSON.decodeFromString<ExportedConfig>(raw)
        } catch (e: SerializationException) {
            return ImportResult.Malformed(e.message ?: "deserialization failed")
        } catch (e: IllegalArgumentException) {
            return ImportResult.Malformed(e.message ?: "invalid argument")
        }
        if (parsed.version > ExportedConfig.CURRENT_VERSION) {
            return ImportResult.VersionUnsupported(parsed.version)
        }
        // 字段合理性兜底校验：账户类型与端口范围
        for (a in parsed.accounts) {
            parseType(a.type) ?: return ImportResult.Malformed("unknown account type: ${a.type}")
            if (a.imapPort !in 0..65535 || a.smtpPort !in 0..65535) {
                return ImportResult.Malformed("port out of range")
            }
            if (a.email.isBlank()) return ImportResult.Malformed("empty email")
        }
        return ImportResult.Loaded(parsed)
    }
}

/** 导入摘要：用于策略对话框上展示"本次将影响多少条数据"。 */
data class ImportSummary(
    val totalAccounts: Int,
    val totalTemplates: Int,
    val accountsToWrite: Int,
    val templatesToWrite: Int,
)

/** 导入解析阶段的结果。Loaded 之后由上层弹策略框决定 [ConfigImporter.apply]。 */
sealed class ImportResult {
    data class Loaded(val config: ExportedConfig) : ImportResult()
    data object WrongPassword : ImportResult()
    data class Malformed(val reason: String) : ImportResult()
    data class VersionUnsupported(val version: Int) : ImportResult()
    data class Failure(val error: Throwable) : ImportResult()
}
