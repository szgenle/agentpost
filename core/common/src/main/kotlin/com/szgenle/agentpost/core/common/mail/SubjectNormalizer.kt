package com.szgenle.agentpost.core.common.mail

/**
 * Subject 去 Re: 规范化。
 *
 * 循环剥离标准 / 中文常见前缀（大小写不敏感），用于邮件路由的"兜底精确匹配"。
 * 纯字符串工具，放在 core:common 以便 core:data 和 core:mail 都能用。
 *
 * 示例：
 * - "Re: Fwd: 回复：整理笔记  " -> "整理笔记"
 * - "回复: RE: 今日天气"         -> "今日天气"
 */
object SubjectNormalizer {

    private val PREFIXES = listOf(
        "re:", "re：",
        "fwd:", "fw:", "fwd：", "fw：",
        "回复:", "回复：",
        "答复:", "答复：",
        "转发:", "转发：",
        "转:", "转：",
    )

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim()
        while (true) {
            val lower = s.lowercase()
            val hit = PREFIXES.firstOrNull { lower.startsWith(it) }
                ?: break
            s = s.substring(hit.length).trimStart()
        }
        return s.trim()
    }
}
