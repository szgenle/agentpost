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
import jakarta.mail.AuthenticationFailedException
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * 周期性拉取邮件的后台 Worker。
 *
 * 策略：
 * - 15 分钟一次（WorkManager 最小周期）
 * - 需要联网
 * - 失败分级处理：
 *    - 永久性错误（IMAP/SMTP 鉴权失败、SELF/AGENT 未配置等本地配置问题）→ Result.failure()，
 *      重试无意义，等用户改配置或下一轮周期再说；
 *    - 临时性错误（网络超时、连接中断、服务器抖动等）→ Result.retry()，按 WorkManager
 *      默认退避尽快补拉，避免邮件长时间滞留服务器。
 */
class SyncMailWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 看门狗：若实时推送/局域网在场开关为 ON 但 Service 不在跑，强制拉起
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
                onFailure = { err -> classifyFailure("syncInbox failed", err) },
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            classifyFailure("SyncMailWorker unexpected error", t)
        }
    }

    /**
     * 按错误性质决定重试策略：
     * - 永久性（鉴权失败 / 本地配置缺失）：重试必然同样失败，直接 failure；
     * - 其他一律视为临时性（网络/超时/连接类）：retry 让 WorkManager 退避后补拉。
     */
    private fun classifyFailure(msg: String, err: Throwable): Result {
        return if (err.isPermanent()) {
            AppLog.w(TAG, "$msg (permanent, no retry): ${err.message}", err)
            Result.failure()
        } else {
            AppLog.w(TAG, "$msg (temporary, retry): ${err.message}", err)
            Result.retry()
        }
    }

    /** 沿 cause 链判断是否为永久性错误。 */
    private fun Throwable.isPermanent(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            when (cause) {
                // IMAP/SMTP 用户名密码被服务器拒绝：改密码前重试无意义
                is AuthenticationFailedException -> return true
                // 本地配置缺失（SELF/AGENT 未配、凭据缺、host 为空等 requireXxx/error() 抛出）
                is IllegalStateException, is IllegalArgumentException -> return true
            }
            cause = cause.cause
        }
        return false
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
     *
     * 除 Service 存活外还检查 IDLE session 本身：Service 被保活但 IDLE 协程
     * 已被掐（session.isRunning=false）时同样踢一脚，让 Service 侧重建长连。
     */
    private suspend fun ensurePushServiceAlive() {
        val prefs = AppServiceLocator.appPreferences
        val wantRealtimePush = prefs.getRealtimePush()
        val wantLanPresence = prefs.getLanPresence()
        if (!wantRealtimePush && !wantLanPresence) return

        if (!isServiceRunning(applicationContext, PushSyncService::class.java)) {
            AppLog.i(TAG, "watchdog: PushSyncService not running, restarting (push=$wantRealtimePush, lan=$wantLanPresence)")
            PushSyncService.start(applicationContext, wantRealtimePush, wantLanPresence)
            return
        }
        if (wantRealtimePush && !PushSyncService.isPushSessionRunning()) {
            AppLog.w(TAG, "watchdog: PushSyncService alive but IDLE session not running, re-kicking to rebuild")
            PushSyncService.start(applicationContext, wantRealtimePush, wantLanPresence)
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }
}
