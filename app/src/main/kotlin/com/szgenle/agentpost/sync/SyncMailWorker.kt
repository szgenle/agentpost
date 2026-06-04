package com.szgenle.agentpost.sync

import android.app.ActivityManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.szgenle.agentpost.core.common.logging.AppLog
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.notification.NotificationController
import java.util.concurrent.TimeUnit

/**
 * 周期性拉取邮件的后台 Worker。
 *
 * 策略：
 * - 15 分钟一次（WorkManager 最小周期）
 * - 需要联网
 * - SELF 未配置 / 凭据缺失 / SMTP 网络异常 → 都返回 Result.success()，避免 retry 风暴耗电。
 *   15 分钟后下一轮会再试。
 */
class SyncMailWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 看门狗：若实时推送开关为 ON 但 Service 不在跑，强制拉起
        ensurePushServiceAlive()

        return try {
            val repo = AppServiceLocator.mailRepository
            val result = repo.syncInbox()
            result.fold(
                onSuccess = { r ->
                    AppLog.i(TAG, "syncInbox ok, new messages = ${r.totalNew}")
                    if (r.totalNew > 0) {
                        // 后台同步拉到新邮件 → 按 Task 分组推通知
                        NotificationController.notifyNewMessages(applicationContext, r)
                    }
                    Result.success()
                },
                onFailure = { err ->
                    AppLog.w(TAG, "syncInbox failed (swallowed, wait next period)", err)
                    Result.success()
                },
            )
        } catch (t: Throwable) {
            AppLog.w(TAG, "SyncMailWorker unexpected error", t)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "SyncMailWorker"
        private const val UNIQUE_WORK_NAME = "agentpost_sync_mail"

        /**
         * 注册（或保留）周期性同步任务。重复调用幂等：KEEP 策略下不会覆盖已有队列。
         */
        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SyncMailWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
        }
    }

    /**
     * 看门狗：检查前台服务是否在运行，不在则重新拉起。
     *
     * 国产 ROM（小米/OPPO/vivo）即使 stopWithTask=false + START_STICKY，
     * 仍可能在内存紧张时强杀前台服务且不重建。
     * WorkManager 是系统级调度，存活率远高于普通 Service，
     * 利用它做定期健康检查可大幅提升推送可靠性。
     */
    private suspend fun ensurePushServiceAlive() {
        val prefs = AppServiceLocator.appPreferences
        if (!prefs.getRealtimePush()) return

        if (!isServiceRunning(applicationContext, PushSyncService::class.java)) {
            AppLog.i(TAG, "watchdog: PushSyncService not running, restarting")
            PushSyncService.start(applicationContext)
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }
}
