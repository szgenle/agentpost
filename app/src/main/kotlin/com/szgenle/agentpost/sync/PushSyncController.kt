package com.szgenle.agentpost.sync

import android.content.Context
import com.szgenle.agentpost.core.common.logging.AppLog
import com.szgenle.agentpost.core.data.AppServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 实时推送总控。
 *
 * 监听 [com.szgenle.agentpost.core.datastore.AppPreferences.observeRealtimePush]：
 *  - 值为 true → [PushSyncService.start] 拉起前台服务
 *  - 值为 false → [PushSyncService.stop] 停掉
 *
 * 由 [com.szgenle.agentpost.AgentPostApp.onCreate] 调用 [install] 安装一次即可，
 * 进程存活期内始终订阅。用户在设置页切开关后，Controller 自动把 Service 拉起 / 停掉。
 *
 * 不与 [ForegroundSyncScheduler]（前台 30s 轮询）和 [SyncMailWorker]（15min）耦合：
 *  - Scheduler 会自查 `prefs.getRealtimePush()` 短路自己
 *  - SyncMailWorker 依旧无条件跑，作为 IDLE 失败时的兜底
 */
object PushSyncController {

    private const val TAG = "PushSyncController"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun install(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            AppServiceLocator.appPreferences.observeRealtimePush()
                .distinctUntilChanged()
                .collect { enabled ->
                    AppLog.i(TAG, "realtime push pref changed -> $enabled")
                    if (enabled) {
                        PushSyncService.start(appContext)
                    } else {
                        PushSyncService.stop(appContext)
                    }
                }
        }
    }
}
