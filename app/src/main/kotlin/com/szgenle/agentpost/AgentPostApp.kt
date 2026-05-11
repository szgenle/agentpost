package com.szgenle.agentpost

import android.app.Application
import android.content.pm.ApplicationInfo
import com.szgenle.agentpost.core.common.crash.CrashHandler
import com.szgenle.agentpost.core.common.logging.AppLog
import com.szgenle.agentpost.core.common.logging.CompositeLogger
import com.szgenle.agentpost.core.common.logging.LogLevel
import com.szgenle.agentpost.core.common.logging.LogcatLogger
import com.szgenle.agentpost.core.common.logging.RollingFileLogger
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.notification.NotificationController
import com.szgenle.agentpost.sync.ForegroundSyncScheduler
import com.szgenle.agentpost.sync.PushSyncController
import com.szgenle.agentpost.sync.SyncMailWorker
import java.io.File

/**
 * Application 入口。职责：
 *  1. 安装日志基础设施（Debug → logcat；Release → rolling file，排查 Jakarta Mail 异常用）
 *  2. 注册全局崩溃捕捉器（崩溃时落盘到 filesDir/crashes/，下次启动询问用户是否邮件回传）
 *  3. 首次启动按系统语言选定应用语言（简体中文→zh-CN，其他→en）并 apply
 *  4. 启动时完成依赖装配（ServiceLocator.init）
 *  5. 创建通知通道（系统“应用设置→通知”即可看到并调整）
 *  6. 注册周期性邮件同步 Worker（后台 15 分钟免底）
 *  7. 安装前台快轮询（前台 30 秒一次）
 */
class AgentPostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 日志必须最先装上，后续初始化流程（ServiceLocator / Worker）出问题时才能留痕。
        installLogger()
        // 崩溃捕捉器紧随其后：之后的任何初始化错误都会写一份崩溃报告到 filesDir/crashes/。
        CrashHandler.install(this)
        // 语言初始化必须在第一个 Activity 被创建前完成。
        LocaleController.initialize(this)
        AppServiceLocator.init(this)
        NotificationController.ensureChannel(this)
        SyncMailWorker.enqueuePeriodic(this)
        ForegroundSyncScheduler.install()
        // 实时推送总控：订阅偏好开关，为 true 时拉起 PushSyncService。
        // 默认 false，仅在用户主动开启后才出现前台服务 + IDLE 长连。
        PushSyncController.install(this)
        // 实时推送总控：订阅偏好开关。开关 false（默认）时不会拉起 Service，
        // 用户在设置页打开后再让前台服务带着 IDLE 长连进驻。
        PushSyncController.install(this)
        // 启动时整体清除加密 zip 解压产物：保证上次会话的明文残留不跨生命周期泄露。
        // 放在 Worker/前台轮询启动后，纯 IO 并不阻塞界面拉起。
        runCatching { File(cacheDir, "decrypted").deleteRecursively() }
            .onFailure { AppLog.w(TAG, "clear cacheDir/decrypted failed: ${it.message}") }
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
