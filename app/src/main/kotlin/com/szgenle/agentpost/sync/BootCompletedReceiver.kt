package com.szgenle.agentpost.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.szgenle.agentpost.core.common.logging.AppLog
import com.szgenle.agentpost.core.data.AppServiceLocator
import kotlinx.coroutines.runBlocking

/**
 * 开机自启广播接收器。
 *
 * 手机重启后系统不会自动恢复 Foreground Service，需通过 BOOT_COMPLETED
 * 广播重新拉起 [PushSyncService]。仅当用户打开了「实时推送」或「局域网在场」
 * 开关时才启动，不会额外耗电。
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        AppLog.i(TAG, "BOOT_COMPLETED received, checking prefs")
        val prefs = AppServiceLocator.appPreferences
        val realtimePush = runBlocking { prefs.getRealtimePush() }
        val lanPresence = runBlocking { prefs.getLanPresence() }

        if (realtimePush || lanPresence) {
            AppLog.i(TAG, "restarting PushSyncService: push=$realtimePush, lan=$lanPresence")
            PushSyncService.start(context, realtimePush, lanPresence)
        } else {
            AppLog.i(TAG, "no active features, skip starting service")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
