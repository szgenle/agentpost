package com.szgenle.agentpost.core.mail

/**
 * IMAP UID 水线（lastSyncUid）推进规则。
 *
 * 水线的语义是「UID ≤ 水线的邮件都已处理完毕」，因此只有在一个 UID 区间内
 * 没有任何失败时，水线才能推进到该批最大 UID；只要存在解析/入库失败的 UID，
 * 水线必须停在第一个失败 UID 之前，让下一轮从水线 + 1 重拉，失败的邮件才有机会重试。
 *
 * IMAP UID 严格递增但允许跳号，这里的"连续"指没有失败 UID 挡路，而非数值相邻。
 */
object UidWatermarks {

    /**
     * 计算可安全推进到的水线。
     *
     * @param parsedUids 本批成功解析（视为已处理，含上层去重跳过的）的 UID
     * @param failedUids 本批解析失败的 UID
     * @param current    当前水线；结果不低于它（水线单调不回退）
     * @return 无失败 → max(parsedUids, current)；有失败 → 第一个失败 UID 之前的
     *         最大已处理 UID，不足则保持 current
     */
    fun safeAdvance(parsedUids: Collection<Long>, failedUids: Collection<Long>, current: Long): Long {
        if (failedUids.isEmpty()) {
            return parsedUids.maxOrNull()?.coerceAtLeast(current) ?: current
        }
        val firstFailure = failedUids.min()
        return parsedUids
            .filter { it < firstFailure }
            .maxOrNull()
            ?.coerceAtLeast(current)
            ?: current
    }
}
