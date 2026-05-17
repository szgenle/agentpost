package com.szgenle.agentpost.feature.settings.configio

import android.content.Context
import android.net.Uri
import com.szgenle.agentpost.core.common.zip.EncryptResult
import com.szgenle.agentpost.core.common.zip.ZipEncryptor
import com.szgenle.agentpost.core.data.MailRepository
import com.szgenle.agentpost.core.datastore.AppPreferences
import com.szgenle.agentpost.core.model.Account
import com.szgenle.agentpost.core.model.AccountType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 配置导出器。
 *
 * 工作流分两步：
 * 1. [build]：从 [MailRepository] 与 [AppPreferences] 读出当前配置，组装成 [ExportedConfig]；
 * 2. [writeEncryptedZip]：把 [ExportedConfig] 序列化成 JSON，用 [ZipEncryptor] 加密成
 *    单条目 ZIP，并通过 ContentResolver 写入 SAF 返回的 Uri。
 *
 * 临时文件统一落在 `cacheDir/configio/`，操作完成或异常退出时由 [cleanupCache] 清空，
 * 避免明文 JSON 在缓存目录长期残留。
 */
class ConfigExporter(
    private val repo: MailRepository,
    private val prefs: AppPreferences,
) {
    /** 收集当前所有可导出字段。 */
    suspend fun build(): ExportedConfig {
        val self = repo.getSelfAccount()
        val agent = repo.getAgentAccount()
        val accounts = listOfNotNull(self?.toExport(), agent?.toExport())
        val templates = prefs.getCommandTemplates().map {
            CommandTemplateExport(
                title = it.title,
                subject = it.subject,
                body = it.body,
                updatedAt = it.updatedAt,
            )
        }
        val intervals = prefs.getFetchIntervals().let {
            FetchIntervalsExport(it.foregroundSeconds, it.backgroundMinutes)
        }
        val crash = prefs.getCrashReportPref().name
        return ExportedConfig(
            version = ExportedConfig.CURRENT_VERSION,
            exportedAt = System.currentTimeMillis(),
            accounts = accounts,
            commandTemplates = templates,
            fetchIntervals = intervals,
            crashReportPref = crash,
        )
    }

    /**
     * 把 [config] 用 [password] 加密打包，写入 [outputUri]。
     *
     * 失败原因都吃掉变成 [ExportResult.Failure]，调用方按需展示 Snackbar。
     */
    suspend fun writeEncryptedZip(
        config: ExportedConfig,
        outputUri: Uri,
        password: String,
        ctx: Context,
    ): ExportResult = withContext(Dispatchers.IO) {
        val workDir = workDir(ctx)
        runCatching {
            workDir.mkdirs()
            val jsonFile = File(workDir, ENTRY_NAME).apply {
                writeText(JSON.encodeToString(config))
            }
            val zipFile = File(workDir, "export-${System.currentTimeMillis()}.zip")
            when (val r = ZipEncryptor.encrypt(listOf(jsonFile), zipFile, password)) {
                is EncryptResult.Success -> {
                    ctx.contentResolver.openOutputStream(outputUri, "wt")?.use { out ->
                        zipFile.inputStream().use { it.copyTo(out) }
                    } ?: throw java.io.IOException("ContentResolver.openOutputStream returned null")
                    ExportResult.Success
                }
                is EncryptResult.Failure -> ExportResult.Failure(r.error)
            }
        }.getOrElse { e ->
            ExportResult.Failure(e)
        }.also {
            cleanupCache(ctx)
        }
    }

    private fun Account.toExport(): AccountExport = AccountExport(
        type = type.name,
        displayName = displayName,
        email = email,
        imapHost = imapHost,
        imapPort = imapPort,
        imapUseSsl = imapUseSsl,
        smtpHost = smtpHost,
        smtpPort = smtpPort,
        smtpUseStartTls = smtpUseStartTls,
    )

    @Suppress("unused") // 留给将来的导入兜底；当前导出主链路不直接使用，但保留对称
    private fun AccountType.label(): String = name

    companion object {
        /** ZIP 内单一 JSON 条目名。 */
        const val ENTRY_NAME = "agentpost-config.json"

        internal val JSON: Json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        internal fun workDir(ctx: Context): File =
            File(ctx.cacheDir, "configio")

        /** 删除 `cacheDir/configio/` 下所有临时文件。 */
        fun cleanupCache(ctx: Context) {
            runCatching { workDir(ctx).deleteRecursively() }
        }
    }
}

/** 导出结果。 */
sealed class ExportResult {
    data object Success : ExportResult()
    data class Failure(val error: Throwable) : ExportResult()
}
