package com.szgenle.agentpost.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.szgenle.agentpost.core.common.logging.AppLog
import com.szgenle.agentpost.core.data.AppServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * App 处于前台时每 30 秒拉一次邮件，等 AI 回复时能及时看到。
 *
 * 原理：
 * - 订阅 ProcessLifecycleOwner（全进程前台/后台切换）
 * - ON_START 启动一个循环 Job，每 30 秒 syncInbox()
 * - ON_STOP 取消该 Job
 *
 * 与 SyncMailWorker 的关系：
 * - 前台：此轮询生效（30 秒）
 * - 后台：WorkManager 兜底（15 分钟）
 * - 两者互不冲突，Repository.syncInbox() 本身幂等（基于 lastSyncUid 增量拉取）
 */
object ForegroundSyncScheduler : DefaultLifecycleObserver {

    private const val TAG = "FgSyncScheduler"
    private const val INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        // 切到前台：启动轮询
        if (job?.isActive == true) return
        job = scope.launch {
            AppLog.i(TAG, "foreground sync loop start")
            while (isActive) {
                // 实时推送开启时，IDLE 长连已负责秒级推送；
                // 此处短路避免双拉。SyncMailWorker 仍继续管理 15min 兜底。
                val realtimeOn = runCatching {
                    AppServiceLocator.appPreferences.getRealtimePush()
                }.getOrDefault(false)
                if (!realtimeOn) {
                    runCatching {
                        AppServiceLocator.mailRepository.syncInbox()
                    }.onSuccess { result ->
                        result.fold(
                            onSuccess = { r -> if (r.totalNew > 0) AppLog.i(TAG, "fg sync new=${r.totalNew}") },
                            onFailure = { e -> AppLog.w(TAG, "fg sync failed: ${e.message}", e) },
                        )
                    }.onFailure { t ->
                        AppLog.w(TAG, "fg sync unexpected", t)
                    }
                }
                delay(INTERVAL_MS)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        // 切到后台：停轮询，交给 WorkManager
        AppLog.i(TAG, "foreground sync loop stop")
        job?.cancel()
        job = null
    }
}
