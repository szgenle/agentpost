package com.szgenle.agentpost

import android.app.Application
import com.szgenle.agentpost.core.data.AppServiceLocator

/**
 * Application 入口。唯一职责：启动时完成依赖装配（ServiceLocator.init）。
 */
class AgentPostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServiceLocator.init(this)
    }
}
