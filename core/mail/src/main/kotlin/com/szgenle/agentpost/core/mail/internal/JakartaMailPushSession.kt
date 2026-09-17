package com.szgenle.agentpost.core.mail.internal

import com.szgenle.agentpost.core.common.logging.AppLog
import com.szgenle.agentpost.core.mail.FetchBatch
import com.szgenle.agentpost.core.mail.IncomingMail
import com.szgenle.agentpost.core.mail.MailCredentials
import com.szgenle.agentpost.core.mail.MailPushSession
import com.szgenle.agentpost.core.mail.UidWatermarks
import jakarta.mail.Folder
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeMessage
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
    initialUidValidity: Long?,
    private val onIncoming: suspend (FetchBatch) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onUidValidityChanged: suspend (Long) -> Unit,
) : MailPushSession {

    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var currentFolder: IMAPFolder? = null

    @Volatile
    private var currentStore: Store? = null

    @Volatile
    private var lastUid: Long = initialUid

    @Volatile
    private var uidValidity: Long? = initialUidValidity

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

        // 校验 UIDVALIDITY：不一致或本地未记录 → 服务器重建过 INBOX，旧 lastUid 在
        // 新编号空间会永久过滤新邮件，归零做全量重扫（上层 Message-ID 去重兜底）。
        // 注意 onUidValidityChanged 需放在追赶 drain 之后：上层在 onIncoming 里按
        // "本地 validity vs 本批 validity" 决定是否重置持久化水线，若先写 validity
        // 会让上层误判 epoch 未变、残留旧高水位。
        val serverUidValidity = folder.uidValidity
        val knownUidValidity = uidValidity
        var epochChanged = false
        if (knownUidValidity != serverUidValidity) {
            AppLog.w(
                TAG,
                "openAndIdleOnce: uidValidity changed (local=$knownUidValidity server=$serverUidValidity), " +
                    "reset lastUid $lastUid -> 0, full rescan",
            )
            lastUid = 0L
            uidValidity = serverUidValidity
            epochChanged = true
        }

        // 追赶：刚建立长连时先把 (lastUid, LASTUID] 拉一遍，补上连接间隙漏掉的
        runCatchingDrain(folder)

        if (epochChanged) {
            runCatching { onUidValidityChanged(serverUidValidity) }
                .onFailure { AppLog.w(TAG, "onUidValidityChanged persist failed: ${it.message}") }
        }

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
            val batch = drainNew(folder)
            AppLog.d(
                TAG,
                "drain: newMails=${batch.mails.size} failedUids=${batch.failedUids.size} lastUid=$lastUid",
            )
            if (batch.mails.isNotEmpty() || batch.failedUids.isNotEmpty()) {
                // 先让上层入库；抛错则走 catch，水位不推进，下轮重拉同一区间（Message-ID 去重幂等）
                onIncoming(batch)
                val newWatermark = UidWatermarks.safeAdvance(
                    parsedUids = batch.mails.map { it.imapUid },
                    failedUids = batch.failedUids,
                    current = lastUid,
                )
                if (newWatermark > lastUid) {
                    AppLog.d(TAG, "drain: advance lastUid $lastUid -> $newWatermark")
                    lastUid = newWatermark
                }
            }
            batch.mails.isNotEmpty()
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

    /**
     * 拉取 (lastUid, LASTUID] 区间的新邮件（纯 UID 增量，不按 UNSEEN 过滤——
     * 已读状态全端同步，网页版/其他客户端标过已读的信件若按未读过滤会永远拉不到）。
     * 解析失败的 UID 记入 [FetchBatch.failedUids]，水位不得跨过它们。
     */
    private fun drainNew(folder: IMAPFolder): FetchBatch {
        val startUid = lastUid + 1
        val range = folder.getMessagesByUID(startUid, UIDFolder.LASTUID)
            .filterNotNull()
        AppLog.d(TAG, "drainNew: uidRange=[$startUid..LASTUID] hit=${range.size} lastUid=$lastUid")
        if (range.isEmpty()) {
            return FetchBatch(mails = emptyList(), failedUids = emptyList(), uidValidity = folder.uidValidity)
        }
        val mails = mutableListOf<IncomingMail>()
        val failedUids = mutableListOf<Long>()
        range
            .map { folder.getUID(it) to (it as MimeMessage) }
            .filter { (uid, _) -> uid >= startUid }
            .sortedBy { (_, msg) -> msg.sentDate?.time ?: 0L }
            .forEach { (uid, msg) ->
                runCatching { fetcher.parseInternal(msg, uid) }
                    .onSuccess { mails += it }
                    .onFailure { e ->
                        AppLog.w(TAG, "drainNew: parse failed uid=$uid: ${e.message}")
                        failedUids += uid
                    }
            }
        return FetchBatch(mails = mails, failedUids = failedUids, uidValidity = folder.uidValidity)
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
