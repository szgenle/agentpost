package com.szgenle.agentpost.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.szgenle.agentpost.MainActivity
import com.szgenle.agentpost.R
import com.szgenle.agentpost.core.data.SyncResult
import com.szgenle.agentpost.core.data.SystemIds

/**
 * 新邮件通知的唯一入口。
 *
 * 策略：
 * - 按 Task 分组推：同一个任务收到多封邮件只占一条通知，notificationId = taskId.hashCode()
 *   再次刷新会 update 同一条，不会堆叠
 * - 通知点击 → 深链到 [MainActivity]，extras 里带 taskId；未归类邮件不带 extras，仅唤起 App
 * - Android 13+ 未授通知权限时静默跳过（上层 Worker 不应因此失败）
 */
object NotificationController {

    const val EXTRA_DEEPLINK_TASK_ID = "deeplink_task_id"

    private const val CHANNEL_ID = "new_mail"
    const val PUSH_CHANNEL_ID = "push_service"
    const val PUSH_SERVICE_NOTIFICATION_ID = 1001

    /**
     * 按 [SyncResult] 逐 Task 发通知。`perTask` 为空直接返回。
     */
    fun notifyNewMessages(context: Context, result: SyncResult) {
        if (result.perTask.isEmpty()) return
        if (!hasPostPermission(context)) return
        ensureChannel(context)

        val nm = NotificationManagerCompat.from(context)
        for (item in result.perTask) {
            val notification = buildFor(context, item)
            val notificationId = item.taskId.hashCode()
            try {
                nm.notify(notificationId, notification)
            } catch (_: SecurityException) {
                // 罕见：权限状态在 post 之前被撤销，忽略
            }
        }
    }

    private fun buildFor(
        context: Context,
        item: com.szgenle.agentpost.core.data.TaskNewMessages,
    ): android.app.Notification {
        val isUnclassified = item.taskId == SystemIds.UNCLASSIFIED_TASK_ID
        val title = when {
            isUnclassified -> context.getString(R.string.notification_unclassified_title)
            item.taskTitle.isNotBlank() -> item.taskTitle
            else -> item.latestSubject.ifBlank { context.getString(R.string.app_name) }
        }
        val suffix = if (item.newCount > 1) {
            context.getString(R.string.notification_multi_count, item.newCount)
        } else ""
        val contentText = item.latestPreview

        val intent = Intent(context, MainActivity::class.java).apply {
            // CLEAR_TOP + SINGLE_TOP 让 MainActivity 复用实例，走 onNewIntent
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // 未归类不带 taskId，点击后停留在任务列表
            if (!isUnclassified) {
                putExtra(EXTRA_DEEPLINK_TASK_ID, item.taskId)
            }
        }
        val pi = PendingIntent.getActivity(
            context,
            item.taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title + suffix)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setAutoCancel(true)
            .setWhen(item.latestSentAt)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setContentIntent(pi)
            .build()
    }

    /**
     * 确保通知通道存在。Android 8+ 必须有通道才能 post，调多次幂等。
     */
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            }
            nm.createNotificationChannel(channel)
        }
        if (nm.getNotificationChannel(PUSH_CHANNEL_ID) == null) {
            // 前台服务常驻通知不需要声音/震动，LOW 级别避免打扰用户。
            val pushChannel = NotificationChannel(
                PUSH_CHANNEL_ID,
                context.getString(R.string.push_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.push_channel_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(pushChannel)
        }
    }

    /**
     * 构造实时推送前台服务的常驻通知（供 [com.szgenle.agentpost.sync.PushSyncService] startForeground 使用）。
     */
    fun buildPushServiceNotification(context: Context): android.app.Notification {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            PUSH_SERVICE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, PUSH_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.push_service_notification_title))
            .setContentText(context.getString(R.string.push_service_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()
    }

    private fun hasPostPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
