package com.szgenle.agentpost

import android.app.Application
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.sync.SyncMailWorker

/**
 * Application 入口。职责：
 *  1. 启动时完成依赖装配（ServiceLocator.init）
 *  2. 注册周期性邮件同步 Worker
 */
class AgentPostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServiceLocator.init(this)
        SyncMailWorker.enqueuePeriodic(this)
    }
}
