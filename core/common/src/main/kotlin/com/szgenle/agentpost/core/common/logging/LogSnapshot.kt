package com.szgenle.agentpost.core.common.logging

import java.io.File

/**
 * 读取 [RollingFileLogger] 目录下的最近日志片段。
 *
 * 用途：Crash 上报时把近期日志一并打包回传，方便排查崩溃前的现场。
 *
 * 读取规则：
 *  - 从最老的历史文件（app.log.3）到当前活跃文件（app.log）按时间先后拼接
 *  - 只保留末尾不超过 [maxChars] 的字符，超过部分从头部截断
 *  - 任何 IO 异常都被吸掉并视作"没日志"，保证 Crash 上报路径足够健壮
 */
object LogSnapshot {

    /**
     * @param logsDir 通常为 `filesDir/logs`
     * @param maxChars 返回字符数上限，默认 64 KB 文本
     */
    fun readRecent(logsDir: File, maxChars: Int = DEFAULT_MAX_CHARS): String {
        if (!logsDir.exists() || !logsDir.isDirectory) return ""

        // 收集实际存在的文件，按时间升序（app.log.3 最老，app.log 最新）。
        val ordered = buildList {
            for (i in 3 downTo 1) {
                val f = File(logsDir, "app.log.$i")
                if (f.exists()) add(f)
            }
            val cur = File(logsDir, "app.log")
            if (cur.exists()) add(cur)
        }
        if (ordered.isEmpty()) return ""

        // 反向遍历（从最新往老），累计字符数达到上限即停。
        val parts = ArrayDeque<String>()
        var remaining = maxChars
        for (f in ordered.reversed()) {
            if (remaining <= 0) break
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (text.length <= remaining) {
                parts.addFirst(text)
                remaining -= text.length
            } else {
                // 当前文件超出剩余配额：只保留末尾 remaining 个字符
                parts.addFirst(text.takeLast(remaining))
                remaining = 0
            }
        }
        return parts.joinToString(separator = "")
    }

    private const val DEFAULT_MAX_CHARS = 64 * 1024
}
