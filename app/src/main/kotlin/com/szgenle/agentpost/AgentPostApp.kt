package com.szgenle.agentpost

import android.app.Application
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.sync.ForegroundSyncScheduler
import com.szgenle.agentpost.sync.SyncMailWorker

/**
 * Application 入口。职责：
 *  1. 首次启动按系统语言选定应用语言（简体中文→zh-CN，其他→en）并 apply
 *  2. 启动时完成依赖装配（ServiceLocator.init）
 *  3. 注册周期性邮件同步 Worker（后台 15 分钟兑底）
 *  4. 安装前台快轮询（前台 30 秒一次）
 */
class AgentPostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 语言初始化必须在第一个 Activity 被创建前完成。
        LocaleController.initialize(this)
        AppServiceLocator.init(this)
        SyncMailWorker.enqueuePeriodic(this)
        ForegroundSyncScheduler.install()
    }
}
