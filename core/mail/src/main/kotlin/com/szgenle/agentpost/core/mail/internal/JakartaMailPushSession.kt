package com.szgenle.agentpost.core.mail.internal

import com.szgenle.agentpost.core.common.logging.AppLog
import com.szgenle.agentpost.core.mail.IncomingMail
import com.szgenle.agentpost.core.mail.MailCredentials
import com.szgenle.agentpost.core.mail.MailPushSession
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Store
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.FlagTerm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.angus.mail.imap.IMAPFolder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * IMAP IDLE 推送会话（自适应学习模式）。
 *
 * 线程模型：
 *  - 主循环协程：Dispatchers.IO，持续打开 Store→INBOX→idle() 阻塞→被唤醒后跑增量搜索→回调；
 *    一旦抛异常，指数退避后重连。
 *  - 心跳协程：按自适应 [currentHeartbeatMs] 周期跨线程读一次 folder.messageCount，
 *    触发 jakarta mail 内部发 DONE 结束当前 IDLE，命令执行完再由主循环重新进入 idle()。
 *
 * 自适应策略：
 *  每轮 idle() 返回时区分"服务器自主唤醒"vs"心跳 kick 唤醒"，统计是否 drain 到新邮件：
 *   - 服务器自主 + 有新邮件  → 推送真 work，心跳 ×2（上限 9min，接近纯 IDLE）
 *   - 心跳 kick + 有新邮件   → 推送没 work，心跳 ÷2（下限 30s，接近 30s 轮询）
 *   - 服务器自主 + 无新邮件  → 可能是 FLAGS 变化，略延长 ×1.5
 *   - 心跳 kick + 无新邮件   → 邮箱空闲，缓慢延长 ×1.2（避免在完全没邮件时一直卡在下限）
 *
 * 停止：[stop] 通过同样的 messageCount() "踢"一下把 IDLE 线程唤醒，然后 cancel 协程、关 folder/store。
 */
internal class JakartaMailPushSession(
    private val fetcher: JakartaMailFetcher,
    private val credentials: MailCredentials,
    initialUid: Long,
    private val onIncoming: suspend (List<IncomingMail>) -> Unit,
    private val onError: (Throwable) -> Unit,
) : MailPushSession {

    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var currentFolder: IMAPFolder? = null

    @Volatile
    private var currentStore: Store? = null

    @Volatile
    private var lastUid: Long = initialUid

    private var mainJob: Job? = null
    private var heartbeatJob: Job? = null

    // 自适应学习状态：当前心跳间隔；心跳协程 kick 前置 true，idle() 返回时读一次并清零。
    private val currentHeartbeatMs = AtomicLong(INITIAL_HEARTBEAT_MS)
    private val heartbeatKickInFlight = AtomicBoolean(false)

    override val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        AppLog.d(TAG, "start: initialUid=$lastUid host=${credentials.imapHost}:${credentials.imapPort} ssl=${credentials.imapUseSsl} user=${credentials.username}")
        mainJob = scope.launch { runMainLoop() }
        heartbeatJob = scope.launch { runHeartbeat() }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        AppLog.d(TAG, "stop requested")
        // 先踢一下 idle()，让主循环的 isActive/running 检查及时退出
        runCatching { currentFolder?.messageCount }
        runCatching { heartbeatJob?.cancel() }
        runCatching { mainJob?.cancel() }
        runCatching { scope.cancel() }
        closeQuietly()
    }

    // ---------------- private ----------------

    private suspend fun runMainLoop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (scope.isActive && running.get()) {
            try {
                AppLog.d(TAG, "mainLoop: opening store & entering idle, lastUid=$lastUid")
                openAndIdleOnce()
                // 正常退出一次 idle 循环 = running 被外部置为 false 或主动 stop，
                // 不再按错误重试。
                AppLog.d(TAG, "mainLoop: idle loop returned normally, running=${running.get()}")
                backoffMs = INITIAL_BACKOFF_MS
            } catch (ce: CancellationException) {
                AppLog.d(TAG, "mainLoop: cancelled")
                throw ce
            } catch (t: Throwable) {
                AppLog.w(TAG, "mainLoop: idle broken (${t.javaClass.simpleName}: ${t.message}), backoff=${backoffMs}ms", t)
                runCatching { onError(t) }
                closeQuietly()
                if (!running.get()) return
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            } finally {
                closeQuietly()
            }
        }
        AppLog.d(TAG, "mainLoop: exit (scope.isActive=${scope.isActive}, running=${running.get()})")
    }

    private suspend fun openAndIdleOnce() {
        val store = fetcher.openStoreInternal(credentials)
        currentStore = store
        AppLog.d(TAG, "openAndIdleOnce: store connected")
        val folder = store.getFolder("INBOX") as IMAPFolder
        folder.open(Folder.READ_ONLY)
        currentFolder = folder
        AppLog.d(TAG, "openAndIdleOnce: INBOX opened, messageCount=${runCatching { folder.messageCount }.getOrDefault(-1)}")

        // 追赶：刚建立长连时先把 lastUid → 最新 UNSEEN 拉一遍，补上连接间隙漏掉的
        runCatchingDrain(folder)

        var idleRound = 0
        while (scope.isActive && running.get() && folder.isOpen) {
            idleRound++
            AppLog.d(TAG, "idle() enter round=$idleRound heartbeatMs=${currentHeartbeatMs.get()}")
            // 清零 kick 标志：接下来 idle() 若被心跳打断，心跳协程会把它置为 true
            heartbeatKickInFlight.set(false)
            // 阻塞调用：服务器或其他线程 kick（messageCount）后返回
            folder.idle()
            val viaHeartbeat = heartbeatKickInFlight.getAndSet(false)
            AppLog.d(TAG, "idle() returned round=$idleRound viaHeartbeat=$viaHeartbeat running=${running.get()} folderOpen=${folder.isOpen}")
            if (!running.get()) break
            val hadNew = runCatchingDrain(folder)
            adaptHeartbeat(viaHeartbeat, hadNew)
        }
        AppLog.d(TAG, "openAndIdleOnce: exit idle loop, totalRounds=$idleRound")
    }

    /** @return 本次 drain 是否捞到新邮件（供自适应心跳调参使用）。 */
    private suspend fun runCatchingDrain(folder: IMAPFolder): Boolean {
        return try {
            val newItems = drainNew(folder)
            AppLog.d(TAG, "drain: newItems=${newItems.size} lastUid=$lastUid")
            if (newItems.isNotEmpty()) {
                newItems.maxOfOrNull { it.imapUid }?.let { maxUid ->
                    if (maxUid > lastUid) lastUid = maxUid
                }
                onIncoming(newItems)
            }
            newItems.isNotEmpty()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AppLog.w(TAG, "drain failed: ${t.message}", t)
            // 单次 drain 失败不直接掐连接，交给 onError 观察，主循环继续 idle
            runCatching { onError(t) }
            false
        }
    }

    /**
     * 根据上一轮 idle() 的返回来源 + 是否 drain 到新邮件，动态调整心跳间隔。
     * 把 Long 乘法放大以保持精度后再截断。
     */
    private fun adaptHeartbeat(viaHeartbeat: Boolean, hadNewMail: Boolean) {
        val old = currentHeartbeatMs.get()
        val new = when {
            !viaHeartbeat && hadNewMail -> (old * 2).coerceAtMost(MAX_HEARTBEAT_MS)          // 推送 work：放心延长
            viaHeartbeat && hadNewMail -> (old / 2).coerceAtLeast(MIN_HEARTBEAT_MS)          // 推送失效：缩短，退化为轮询
            !viaHeartbeat && !hadNewMail -> (old * 3 / 2).coerceAtMost(MAX_HEARTBEAT_MS)     // 非推送型唤醒：略延长
            else -> (old * 6 / 5).coerceAtMost(MAX_HEARTBEAT_MS)                             // 心跳+空邮箱：缓慢延长
        }
        if (new != old) {
            currentHeartbeatMs.set(new)
            AppLog.d(TAG, "adaptHeartbeat: ${old}ms -> ${new}ms via=$viaHeartbeat new=$hadNewMail")
        }
    }

    private fun drainNew(folder: IMAPFolder): List<IncomingMail> {
        val unseen = folder.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
        if (unseen.isEmpty()) return emptyList()
        return unseen
            .map { folder.getUID(it) to (it as MimeMessage) }
            .filter { (uid, _) -> uid > lastUid }
            .sortedBy { (_, msg) -> msg.sentDate?.time ?: 0L }
            .mapNotNull { (uid, msg) -> runCatching { fetcher.parseInternal(msg, uid) }.getOrNull() }
    }

    private suspend fun runHeartbeat() {
        while (scope.isActive && running.get()) {
            delay(currentHeartbeatMs.get())
            if (!running.get()) break
            // 打标：让主循环判定本次 idle() 返回是"心跳 kick 触发"而非"服务器自主唤醒"
            heartbeatKickInFlight.set(true)
            // 跨线程调 messageCount 会让 jakarta mail 自动先 DONE 结束当前 IDLE，
            // 等同于一次 NOOP 兜底；失败时让主循环自己感知并重连。
            val r = runCatching { currentFolder?.messageCount }
            AppLog.d(TAG, "heartbeat kick: count=${r.getOrNull()} err=${r.exceptionOrNull()?.message} nextDelayMs=${currentHeartbeatMs.get()}")
        }
    }

    private fun closeQuietly() {
        val folder = currentFolder
        val store = currentStore
        currentFolder = null
        currentStore = null
        runCatching { if (folder != null && folder.isOpen) folder.close(false) }
        runCatching { if (store != null && store.isConnected) store.close() }
    }

    private companion object {
        const val TAG = "JakartaIdle"
        // 自适应心跳档位：
        //   - 起步 60s：不知道服务器推送是否可用，先给 60s 试一轮
        //   - 下限 30s：推送完全失效时，相当于 30s 轮询（QQ 实测档）
        //   - 上限 9min：推送真 work 时，长连接近似纯 IDLE（Gmail 等）
        const val INITIAL_HEARTBEAT_MS = 60L * 1000L
        const val MIN_HEARTBEAT_MS = 30L * 1000L
        const val MAX_HEARTBEAT_MS = 9L * 60L * 1000L
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
