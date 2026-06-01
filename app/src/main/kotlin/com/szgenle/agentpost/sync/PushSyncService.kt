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
import com.szgenle.lanbeacon.LanPresenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 多职责前台服务。
 *
 * 承载两个独立子组件，各自按开关独立启停：
 *  1. **IMAP IDLE 实时推送**：用户开启「实时通知」后建立 IDLE 长连
 *  2. **局域网在场广播**：用户开启后启动 HTTP server + mDNS
 *
 * 生命周期由 [PushSyncController] 统一管控：
 *  - 任一开关 ON → start service（带 extras 指明哪些功能启用）
 *  - 两个开关均 OFF → stop service
 *
 * 设计约束：
 *  - 只有一个常驻通知（复用同一个 notification ID）
 *  - 不直接感知开关状态，生命周期完全受 Controller 控制
 */
class PushSyncService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var session: MailPushSession? = null
    private var bootstrapJob: Job? = null
    private var lanManager: LanPresenceManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.i(TAG, "PushSyncService onCreate")
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统在异常杀死后重建 Service 时也要重新进入前台（防止 ANR）
        startAsForeground()

        val wantRealtimePush = intent?.getBooleanExtra(EXTRA_REALTIME_PUSH, false) ?: false
        val wantLanPresence = intent?.getBooleanExtra(EXTRA_LAN_PRESENCE, false) ?: false
        AppLog.i(TAG, "onStartCommand: realtimePush=$wantRealtimePush, lanPresence=$wantLanPresence")

        // --- IDLE Push 子组件 ---
        if (wantRealtimePush && session == null) {
            bootstrapJob = scope.launch { bootstrapPushSession() }
        } else if (!wantRealtimePush && session != null) {
            AppLog.i(TAG, "stopping IDLE session")
            runCatching { session?.stop() }
            session = null
            runCatching { bootstrapJob?.cancel() }
            bootstrapJob = null
        }

        // --- LAN Presence 子组件 ---
        if (wantLanPresence && lanManager == null) {
            val port = runBlocking { AppServiceLocator.appPreferences.getLanPresencePort() }
            val appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            lanManager = LanPresenceManager(this).also {
                it.start(
                    port = port,
                    appName = "agentpost",
                    appVersion = appVersion,
                    serviceType = "_agentpost._tcp.",
                    serviceName = "AgentPost",
                )
            }
        } else if (!wantLanPresence && lanManager != null) {
            AppLog.i(TAG, "stopping LAN presence")
            lanManager?.stop()
            lanManager = null
        }

        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.i(TAG, "PushSyncService onDestroy")
        runCatching { session?.stop() }
        session = null
        runCatching { bootstrapJob?.cancel() }
        lanManager?.stop()
        lanManager = null
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
                AppLog.w(TAG, "startInboxPush returned null (account not ready)")
                // 不 stop self：可能 LAN presence 仍在运行
                return
            }
            session = created
            AppLog.i(TAG, "IDLE session started")
        } catch (t: Throwable) {
            AppLog.w(TAG, "bootstrap push failed", t)
            // 不 stop self：可能 LAN presence 仍在运行
        }
    }

    /** CoroutineScope.cancel() 便捷扩展。*/
    private fun CoroutineScope.cancel() {
        runCatching { (coroutineContext[Job] as? Job)?.cancel() }
    }

    companion object {
        private const val TAG = "PushSyncService"
        const val EXTRA_REALTIME_PUSH = "extra_realtime_push"
        const val EXTRA_LAN_PRESENCE = "extra_lan_presence"

        fun start(context: Context, realtimePush: Boolean, lanPresence: Boolean) {
            val intent = Intent(context, PushSyncService::class.java).apply {
                putExtra(EXTRA_REALTIME_PUSH, realtimePush)
                putExtra(EXTRA_LAN_PRESENCE, lanPresence)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushSyncService::class.java))
        }
    }
}
