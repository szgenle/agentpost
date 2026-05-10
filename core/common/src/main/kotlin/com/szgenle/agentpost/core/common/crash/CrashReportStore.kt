package com.szgenle.agentpost.core.common.crash

import android.content.Context
import android.os.Build
import com.szgenle.agentpost.core.common.logging.LogSnapshot
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃报告本地存储。
 *
 * 每次崩溃落盘一份纯文本文件到 `filesDir/crashes/crash_{yyyyMMdd_HHmmss}_{class}.log`，
 * 下次启动时由上层扫描、根据用户偏好决定是否邮件回传。
 *
 * 文件内容：
 *  - 元信息：app 版本、Android 版本、机型、崩溃线程
 *  - 完整堆栈（含 cause 链）
 *  - 近期日志尾巴（[LogSnapshot] 读出的 64KB 文本）
 *
 * 所有 IO 失败都被吸掉——Crash 路径不应再扔异常污染主流程。
 */
class CrashReportStore(private val appContext: Context) {

    private val dir: File = File(appContext.filesDir, DIR_NAME)

    /** 写入一份崩溃报告。异常被吸掉。 */
    fun write(thread: Thread, throwable: Throwable) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val ts = FILE_TIME_FORMAT.format(Date())
            val exName = throwable.javaClass.simpleName.ifBlank { "Throwable" }
            val file = File(dir, "crash_${ts}_$exName.log")
            file.writeText(compose(thread, throwable))
        }
    }

    /** 列出所有待处理的崩溃文件，按文件名（含时间戳）升序。 */
    fun list(): List<File> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f ->
            f.isFile && f.name.startsWith("crash_") && f.name.endsWith(".log")
        }?.sortedBy { it.name }.orEmpty()
    }

    /** 批量删除，吸掉失败。 */
    fun delete(files: List<File>) {
        for (f in files) runCatching { f.delete() }
    }

    /** 清理超过 [maxAgeMillis] 未处理的残留文件（默认 7 天）。 */
    fun pruneOld(maxAgeMillis: Long = DEFAULT_MAX_AGE_MS) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        val outdated = list().filter { it.lastModified() < cutoff }
        if (outdated.isNotEmpty()) delete(outdated)
    }

    private fun compose(thread: Thread, throwable: Throwable): String {
        val now = DISPLAY_TIME_FORMAT.format(Date())
        val pkg = appContext.packageName
        val (versionName, versionCode) = readVersion(pkg)
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val logTail = LogSnapshot.readRecent(File(appContext.filesDir, "logs"))

        return buildString {
            appendLine("=== AgentPost Crash Report ===")
            appendLine("time: $now")
            appendLine("package: $pkg")
            appendLine("versionName: $versionName")
            appendLine("versionCode: $versionCode")
            appendLine("thread: ${thread.name}")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("manufacturer: ${Build.MANUFACTURER}")
            appendLine("model: ${Build.MODEL}")
            appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine()
            appendLine("--- stacktrace ---")
            append(sw.toString())
            if (logTail.isNotEmpty()) {
                appendLine()
                appendLine("--- recent logs (tail) ---")
                append(logTail)
            }
        }
    }

    private fun readVersion(pkg: String): Pair<String, Long> {
        val info = runCatching { appContext.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
            ?: return "?" to -1L
        val name = info.versionName ?: "?"
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return name to code
    }

    companion object {
        private const val DIR_NAME = "crashes"
        private const val DEFAULT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private val FILE_TIME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        private val DISPLAY_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
