package com.szgenle.agentpost.sync

import android.content.Context
import com.szgenle.agentpost.core.common.logging.AppLog
import com.szgenle.agentpost.core.data.AppServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 前台服务总控。
 *
 * 监听两个开关：
 *  - [实时推送] [com.szgenle.agentpost.core.datastore.AppPreferences.observeRealtimePush]
 *  - [局域网在场广播] [com.szgenle.agentpost.core.datastore.AppPreferences.observeLanPresence]
 *
 * 启动条件：任一开关为 ON → 启动 [PushSyncService]
 * 停止条件：两个开关均为 OFF → 停止 [PushSyncService]
 *
 * 将两个布尔值通过 Intent extras 传给 Service，Service 内部据此决定启停哪些子组件。
 *
 * 由 [com.szgenle.agentpost.AgentPostApp.onCreate] 调用 [install] 安装一次即可，
 * 进程存活期内始终订阅。用户在设置页切开关后，Controller 自动把 Service 拉起 / 停掉。
 *
 * 不与 [ForegroundSyncScheduler]（前台 30s 轮询）和 [SyncMailWorker]（15min）耦合：
 *  - Scheduler 会自查 `prefs.getRealtimePush()` 短路自己
 *  - SyncMailWorker 依旧无条件跑，作为 IDLE 失败时的兑底
 */
object PushSyncController {

    private const val TAG = "PushSyncController"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun install(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            combine(
                AppServiceLocator.appPreferences.observeRealtimePush(),
                AppServiceLocator.appPreferences.observeLanPresence(),
            ) { realtimePush, lanPresence -> Pair(realtimePush, lanPresence) }
                .distinctUntilChanged()
                .collect { (realtimePush, lanPresence) ->
                    AppLog.i(TAG, "pref changed -> realtimePush=$realtimePush, lanPresence=$lanPresence")
                    if (realtimePush || lanPresence) {
                        PushSyncService.start(appContext, realtimePush, lanPresence)
                    } else {
                        PushSyncService.stop(appContext)
                    }
                }
        }
    }
}
