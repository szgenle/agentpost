package com.szgenle.agentpost

import android.app.Application
import android.content.pm.ApplicationInfo
import com.szgenle.agentpost.core.common.logging.AppLog
import com.szgenle.agentpost.core.common.logging.CompositeLogger
import com.szgenle.agentpost.core.common.logging.LogLevel
import com.szgenle.agentpost.core.common.logging.LogcatLogger
import com.szgenle.agentpost.core.common.logging.RollingFileLogger
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.notification.NotificationController
import com.szgenle.agentpost.sync.ForegroundSyncScheduler
import com.szgenle.agentpost.sync.SyncMailWorker
import java.io.File

/**
 * Application 入口。职责：
 *  1. 安装日志基础设施（Debug → logcat；Release → rolling file，排查 Jakarta Mail 异常用）
 *  2. 首次启动按系统语言选定应用语言（简体中文→zh-CN，其他→en）并 apply
 *  3. 启动时完成依赖装配（ServiceLocator.init）
 *  4. 创建通知通道（系统“应用设置→通知”即可看到并调整）
 *  5. 注册周期性邮件同步 Worker（后台 15 分钟兑底）
 *  6. 安装前台快轮询（前台 30 秒一次）
 */
class AgentPostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 日志必须最先装上，后续初始化流程（ServiceLocator / Worker）出问题时才能留痕。
        installLogger()
        // 语言初始化必须在第一个 Activity 被创建前完成。
        LocaleController.initialize(this)
        AppServiceLocator.init(this)
        NotificationController.ensureChannel(this)
        SyncMailWorker.enqueuePeriodic(this)
        ForegroundSyncScheduler.install()
    }

    private fun installLogger() {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val logger = if (debuggable) {
            // Debug 包：只打 logcat，便于本地 IDE/adb logcat 实时观察。
            LogcatLogger(minLevel = LogLevel.VERBOSE)
        } else {
            // Release 包：落盘 rolling file 供事后排查；同时仍然发一份到 logcat，
            // 用户若愿意连 adb 也能取到即时流。
            val logsDir = File(filesDir, "logs")
            CompositeLogger(
                listOf(
                    LogcatLogger(minLevel = LogLevel.INFO),
                    RollingFileLogger(dir = logsDir, minLevel = LogLevel.INFO),
                ),
            )
        }
        AppLog.install(logger)
        AppLog.i(TAG, "logger installed, debuggable=$debuggable")
    }

    companion object {
        private const val TAG = "AgentPostApp"
    }
}
