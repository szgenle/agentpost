package com.szgenle.agentpost.core.mail.internal

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

/**
 * IMAP IDLE 推送会话。
 *
 * 线程模型：
 *  - 主循环协程：Dispatchers.IO，持续打开 Store→INBOX→idle() 阻塞→被唤醒后跑增量搜索→回调；
 *    一旦抛异常，指数退避后重连。
 *  - 心跳协程：每 9 分钟（< RFC 3501 建议的 29 min）在辅助协程里读一次 folder.messageCount。
 *    jakarta mail 的 IMAPFolder 实现里，这种跨线程调用会先内部发 DONE 结束当前 IDLE，
 *    命令执行完再由主循环重新进入 idle()，等于刷新 NAT/服务端超时。
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

    override val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        mainJob = scope.launch { runMainLoop() }
        heartbeatJob = scope.launch { runHeartbeat() }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
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
                openAndIdleOnce()
                // 正常退出一次 idle 循环 = running 被外部置为 false 或主动 stop，
                // 不再按错误重试。
                backoffMs = INITIAL_BACKOFF_MS
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                runCatching { onError(t) }
                closeQuietly()
                if (!running.get()) return
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            } finally {
                closeQuietly()
            }
        }
    }

    private suspend fun openAndIdleOnce() {
        val store = fetcher.openStoreInternal(credentials)
        currentStore = store
        val folder = store.getFolder("INBOX") as IMAPFolder
        folder.open(Folder.READ_ONLY)
        currentFolder = folder

        // 追赶：刚建立长连时先把 lastUid → 最新 UNSEEN 拉一遍，补上连接间隙漏掉的
        runCatchingDrain(folder)

        while (scope.isActive && running.get() && folder.isOpen) {
            // 阻塞调用：服务器或其他线程 kick（messageCount）后返回
            folder.idle()
            if (!running.get()) break
            runCatchingDrain(folder)
        }
    }

    private suspend fun runCatchingDrain(folder: IMAPFolder) {
        try {
            val newItems = drainNew(folder)
            if (newItems.isNotEmpty()) {
                newItems.maxOfOrNull { it.imapUid }?.let { maxUid ->
                    if (maxUid > lastUid) lastUid = maxUid
                }
                onIncoming(newItems)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // 单次 drain 失败不直接掐连接，交给 onError 观察，主循环继续 idle
            runCatching { onError(t) }
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
            delay(HEARTBEAT_INTERVAL_MS)
            if (!running.get()) break
            // 跨线程调 messageCount 会让 jakarta mail 自动先 DONE 结束当前 IDLE，
            // 等同于一次 NOOP 兜底；失败时让主循环自己感知并重连。
            runCatching { currentFolder?.messageCount }
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
        const val HEARTBEAT_INTERVAL_MS = 9L * 60L * 1000L        // 9 分钟
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
