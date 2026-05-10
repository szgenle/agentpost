package com.szgenle.agentpost.crash

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.szgenle.agentpost.R
import com.szgenle.agentpost.core.common.crash.CrashReportStore
import com.szgenle.agentpost.core.common.logging.AppLog
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.core.datastore.CrashReportPref
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 启动期崩溃上报流程（纯 UI 组件，无状态持有）。
 *
 * 流程：
 *  1. 进入 Composition 时扫一遍 `filesDir/crashes/`
 *  2. 读用户偏好 [CrashReportPref]：
 *     - NEVER：静默删除所有待上报文件
 *     - AUTO：静默调 [com.szgenle.agentpost.core.data.MailRepository.sendCrashReport] 给自己发邮件，成功后删
 *     - ASK_EACH_TIME（默认）：弹 AlertDialog 让用户选「发送 / 跳过 / 永不上报」
 *  3. 「跳过」只删本轮文件，保留偏好；「永不上报」把偏好写成 NEVER 再删
 *
 * 发送失败不重试也不报错——文件保留给下次启动再试，[CrashReportStore.pruneOld] 7 天兜底清理。
 */
@Composable
fun CrashReportPrompt() {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val store = remember(appContext) { CrashReportStore(appContext) }
    val scope = rememberCoroutineScope()

    var pending by remember { mutableStateOf<List<File>>(emptyList()) }
    var showDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val files = withContext(Dispatchers.IO) { store.list() }
        if (files.isEmpty()) return@LaunchedEffect
        val pref = runCatching { AppServiceLocator.appPreferences.getCrashReportPref() }
            .getOrDefault(CrashReportPref.ASK_EACH_TIME)
        when (pref) {
            CrashReportPref.NEVER -> withContext(Dispatchers.IO) { store.delete(files) }
            CrashReportPref.AUTO -> sendAndCleanup(appContext, store, files, notifyUser = false)
            CrashReportPref.ASK_EACH_TIME -> {
                pending = files
                showDialog = true
            }
        }
    }

    if (!showDialog) return
    val files = pending
    if (files.isEmpty()) {
        showDialog = false
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) showDialog = false },
        title = { Text(stringResource(R.string.crash_prompt_title)) },
        text = {
            Text(stringResource(R.string.crash_prompt_message, files.size))
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        sendAndCleanup(appContext, store, files, notifyUser = true)
                        busy = false
                        showDialog = false
                    }
                },
            ) { Text(stringResource(R.string.crash_prompt_send)) }
        },
        dismissButton = {
            // 两个并列按钮：跳过本次 / 永不上报。Material3 AlertDialog 的 dismissButton 容器
            // 支持放多个子元素，从右向左排列。
            androidx.compose.foundation.layout.Row {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching {
                                AppServiceLocator.appPreferences.setCrashReportPref(CrashReportPref.NEVER)
                            }
                            withContext(Dispatchers.IO) { store.delete(files) }
                            busy = false
                            showDialog = false
                        }
                    },
                ) { Text(stringResource(R.string.crash_prompt_never)) }
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            withContext(Dispatchers.IO) { store.delete(files) }
                            busy = false
                            showDialog = false
                        }
                    },
                ) { Text(stringResource(R.string.crash_prompt_skip)) }
            }
        },
    )
}

/**
 * 发送给 SELF 邮箱，成功则删除文件；失败保留（交给 [CrashReportStore.pruneOld] 7 天兜底）。
 *
 * @param notifyUser 为 true 时弹 Toast 反馈成功/失败（用户主动点“发送”），
 *                   为 false 时静默运行（AUTO 启动自动上传）。
 */
private suspend fun sendAndCleanup(
    context: Context,
    store: CrashReportStore,
    files: List<File>,
    notifyUser: Boolean,
) {
    val bodyAndSubject = withContext(Dispatchers.IO) { buildMail(files) } ?: return
    val (subject, body) = bodyAndSubject
    val result = runCatching {
        AppServiceLocator.mailRepository.sendCrashReport(subject, body)
    }.getOrElse {
        AppLog.w(TAG, "crash prompt: sendCrashReport threw", it)
        Result.failure(it)
    }
    if (result.isSuccess) {
        withContext(Dispatchers.IO) { store.delete(files) }
        if (notifyUser) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.crash_prompt_toast_sent, Toast.LENGTH_SHORT).show()
            }
        }
    } else if (notifyUser) {
        val reason = result.exceptionOrNull()?.message.orEmpty().ifBlank {
            result.exceptionOrNull()?.javaClass?.simpleName.orEmpty()
        }.ifBlank { "unknown" }
        withContext(Dispatchers.Main) {
            val msg = context.getString(R.string.crash_prompt_toast_failed, reason)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
}

/** 读所有崩溃文件拼成一封邮件。若全部读失败则返回 null。 */
private fun buildMail(files: List<File>): Pair<String, String>? {
    val parts = files.mapNotNull { f ->
        runCatching { f.readText() }.getOrNull()?.let { f.name to it }
    }
    if (parts.isEmpty()) return null
    val subject = "[AgentPost Crash] ${parts.size} report(s)"
    val body = buildString {
        appendLine("AgentPost crash reports (${parts.size} file(s)).")
        appendLine()
        parts.forEachIndexed { index, (name, text) ->
            appendLine("================ #${index + 1} $name ================")
            append(text)
            if (!text.endsWith("\n")) appendLine()
            appendLine()
        }
    }
    return subject to body
}

private const val TAG = "CrashReportPrompt"
