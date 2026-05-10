package com.szgenle.agentpost.core.common.logging

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 滚动文件日志：按文件大小分段，保留固定数量历史。
 *
 * 典型用法：落盘到 `filesDir/logs/app.log`；超出 [maxBytes] 就轮转：
 * `app.log.3` 丢弃，`app.log.2 → app.log.3`，…，`app.log → app.log.1`，然后新建 `app.log`。
 *
 * 设计取舍：
 * - 单条 append 为 atomic unit：若写入点刚好跨越轮转阈值，当前条目会落到新文件头；
 *   MVP 阶段可接受。
 * - 写入在调用线程内同步完成，外层持 [lock] 串行化；日志量不大，不额外引入线程池。
 * - 格式：`yyyy-MM-dd HH:mm:ss.SSS LEVEL/tag: message\n  at ...`（堆栈全量展开）。
 *
 * 不做：日志等级的动态调整、远程上报（后续工单 #10 Crash 上报时再考虑）。
 */
class RollingFileLogger(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxBackups: Int = DEFAULT_MAX_BACKUPS,
    private val minLevel: LogLevel = LogLevel.INFO,
    private val fileName: String = DEFAULT_FILE_NAME,
) : Logger {

    private val lock = Any()

    private val currentFile: File
        get() = File(dir, fileName)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        val line = format(level, tag, message, throwable)
        synchronized(lock) {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                val file = currentFile
                if (file.exists() && file.length() + line.length > maxBytes) {
                    rotate()
                }
                file.appendText(line)
            }
            // 落盘失败时故意静默：日志基础设施自身不应再扔异常污染主流程。
        }
    }

    private fun format(level: LogLevel, tag: String, message: String, throwable: Throwable?): String {
        val time = dateFormat.format(Date())
        val prefix = "$time ${level.shortName()}/$tag: $message"
        return if (throwable == null) {
            prefix + "\n"
        } else {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            prefix + "\n" + sw.toString()
        }
    }

    /**
     * 轮转策略：从最老一档开始依次重命名，最后把当前 `app.log` 改名为 `app.log.1`。
     * 不抛异常：任何一步失败都继续往下走，保证下次写入不会被卡住。
     */
    private fun rotate() {
        for (i in maxBackups downTo 1) {
            val src = File(dir, "$fileName.${i - 1}".let { if (i - 1 == 0) fileName else it })
            val dst = File(dir, "$fileName.$i")
            if (src.exists()) {
                if (dst.exists()) runCatching { dst.delete() }
                runCatching { src.renameTo(dst) }
            }
        }
    }

    private fun LogLevel.shortName(): String = when (this) {
        LogLevel.VERBOSE -> "V"
        LogLevel.DEBUG -> "D"
        LogLevel.INFO -> "I"
        LogLevel.WARN -> "W"
        LogLevel.ERROR -> "E"
    }

    companion object {
        const val DEFAULT_FILE_NAME = "app.log"
        const val DEFAULT_MAX_BYTES: Long = 512 * 1024L
        const val DEFAULT_MAX_BACKUPS: Int = 3
    }
}
