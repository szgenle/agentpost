package com.szgenle.agentpost.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.szgenle.agentpost.core.common.logging.AppLog
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.core.mail.MailPushSession
import com.szgenle.agentpost.notification.NotificationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 实时推送前台服务。
 *
 * 只在用户显式打开「实时通知」开关后由 [com.szgenle.agentpost.sync.PushSyncController] 拉起。
 * 本身不感知开关状态，生命周期完全受 Controller 控制（start/stopService）。
 *
 * 职责：
 *  1. startForeground 带常驻通知（[NotificationController.buildPushServiceNotification]），
 *     Android 14+ 通过 `FOREGROUND_SERVICE_TYPE_DATA_SYNC` 声明子类型
 *  2. 调 [com.szgenle.agentpost.core.data.MailRepository.startInboxPush] 建立 IDLE 长连
 *  3. 收到新邮件 → [NotificationController.notifyNewMessages]
 *  4. onDestroy 安全关闭 session、取消协程、撤销前台
 *
 * 注意：Service 不直接处理账户未配场景——MailRepository.startInboxPush 返回 null 时
 * 说明配置未完备，此处记录日志并 stopSelf，让 Controller 在下次配置就绪时再拉起。
 */
class PushSyncService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var session: MailPushSession? = null
    private var bootstrapJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.i(TAG, "PushSyncService onCreate")
        startAsForeground()
        bootstrapJob = scope.launch { bootstrapPushSession() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统在异常杀死后重建 Service 时也要重新进入前台（防止 ANR）
        startAsForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.i(TAG, "PushSyncService onDestroy")
        runCatching { session?.stop() }
        session = null
        runCatching { bootstrapJob?.cancel() }
        runCatching { scope.cancel() }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = NotificationController.buildPushServiceNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationController.PUSH_SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(
                NotificationController.PUSH_SERVICE_NOTIFICATION_ID,
                notification,
            )
        }
    }

    private suspend fun bootstrapPushSession() {
        try {
            val created = AppServiceLocator.mailRepository.startInboxPush(
                onSynced = { result ->
                    if (result.perTask.isNotEmpty()) {
                        NotificationController.notifyNewMessages(this@PushSyncService, result)
                    }
                    AppLog.i(TAG, "push synced new=${result.totalNew}")
                },
                onError = { t ->
                    AppLog.w(TAG, "idle session error: ${t.message}", t)
                },
            )
            if (created == null) {
                AppLog.w(TAG, "startInboxPush returned null (account not ready), stopping")
                stopSelf()
                return
            }
            session = created
            AppLog.i(TAG, "IDLE session started")
        } catch (t: Throwable) {
            AppLog.w(TAG, "bootstrap push failed, stopping", t)
            stopSelf()
        }
    }

    /** CoroutineScope.cancel() 便捷扩展。*/
    private fun CoroutineScope.cancel() {
        runCatching { (coroutineContext[Job] as? Job)?.cancel() }
    }

    companion object {
        private const val TAG = "PushSyncService"

        fun start(context: Context) {
            val intent = Intent(context, PushSyncService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushSyncService::class.java))
        }
    }
}
