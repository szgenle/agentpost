package com.szgenle.agentpost.core.common.crash

import android.content.Context

/**
 * 全局未捕获异常处理器。
 *
 * 行为：崩溃时先把堆栈 + 环境 + 近期日志通过 [CrashReportStore] 落盘，
 * 然后把异常交回给系统原本的 handler（通常是 Android 的 KillApplicationHandler，
 * 会弹"应用已停止"并杀掉进程）。这样既保留了系统默认行为，也拿到了事后上报
 * 需要的原始材料。
 *
 * 由 `Application.onCreate` 安装一次即可，多次调用不会重复注册处理器——
 * 底层 [Thread.setDefaultUncaughtExceptionHandler] 会覆盖，但我们把"原 handler"
 * 捕获到新 handler 的闭包里，保证异常链完整。
 */
object CrashHandler {

    fun install(context: Context) {
        val appContext = context.applicationContext
        val store = CrashReportStore(appContext)
        // 启动时顺手清理一下过期的崩溃文件，避免无限占空间。
        runCatching { store.pruneOld() }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { store.write(thread, throwable) }
            // 交回系统：该弹"已停止"就弹，该杀进程就杀。
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // 罕见情况：没有上一层 handler。保底杀进程，避免"崩溃了但进程还活着"的假死。
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }
}
