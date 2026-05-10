package com.szgenle.agentpost.core.common.logging

import android.util.Log

/**
 * 应用日志门面。
 *
 * 设计要点：
 * - 业务代码只依赖 [AppLog] 这个门面，不直接引用 android.util.Log；
 * - Application 启动时调用 [AppLog.install] 注入真正的 [Logger] 实现：
 *   - Debug → [LogcatLogger]（直达 logcat，本地调试）；
 *   - Release → [RollingFileLogger]（或 [CompositeLogger] 叠加 logcat + 文件），
 *     用户设备上出 Jakarta Mail 异常时，日志能落盘供事后排查。
 * - 未 install 时的默认 delegate 是 [NoopLogger]（例如单元测试），不会抛 NPE。
 */
interface Logger {
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)
}

enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

object AppLog {

    @Volatile
    private var delegate: Logger = NoopLogger

    fun install(logger: Logger) {
        delegate = logger
    }

    fun v(tag: String, message: String, throwable: Throwable? = null) {
        delegate.log(LogLevel.VERBOSE, tag, message, throwable)
    }

    fun d(tag: String, message: String, throwable: Throwable? = null) {
        delegate.log(LogLevel.DEBUG, tag, message, throwable)
    }

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        delegate.log(LogLevel.INFO, tag, message, throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        delegate.log(LogLevel.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        delegate.log(LogLevel.ERROR, tag, message, throwable)
    }
}

/** 默认空实现：未初始化时不抛异常、不产生副作用。 */
object NoopLogger : Logger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        // 故意留空
    }
}

/**
 * 同时分发到多个 [Logger]。用于 Release 下既写 logcat 又落文件。
 */
class CompositeLogger(private val loggers: List<Logger>) : Logger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        for (l in loggers) {
            runCatching { l.log(level, tag, message, throwable) }
        }
    }
}

/** 直达 [android.util.Log]。可用 [minLevel] 过滤过于冗余的 VERBOSE/DEBUG。 */
class LogcatLogger(
    private val minLevel: LogLevel = LogLevel.VERBOSE,
) : Logger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        when (level) {
            LogLevel.VERBOSE -> if (throwable == null) Log.v(tag, message) else Log.v(tag, message, throwable)
            LogLevel.DEBUG -> if (throwable == null) Log.d(tag, message) else Log.d(tag, message, throwable)
            LogLevel.INFO -> if (throwable == null) Log.i(tag, message) else Log.i(tag, message, throwable)
            LogLevel.WARN -> if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
            LogLevel.ERROR -> if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        }
    }
}
