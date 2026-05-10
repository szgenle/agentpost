package com.szgenle.agentpost.core.mail.internal

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility

/**
 * MIME 解析 / 组装的小工具集。
 *
 * 只负责纯字符串 / MIME 结构的处理，不碰 IMAP/SMTP 会话。
 */
internal object MimeUtils {

    /**
     * 常见回复 / 转发前缀（大小写不敏感）。
     * 顺序不重要，循环剥离到收敛为止。
     */
    private val REPLY_PREFIXES = listOf(
        "re:", "re：",
        "fwd:", "fw:",
        "回复:", "回复：",
        "答复:", "答复：",
        "转发:", "转发：",
        "转:", "转：",
    )

    /**
     * Subject 去 Re: 规范化。
     *
     * 循环剥离所有识别到的前缀，合并首尾空白后返回。
     * 示例："Re: Fwd: 回复：整理笔记  " -> "整理笔记"
     */
    fun normalizeSubject(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim()
        var changed = true
        while (changed) {
            changed = false
            for (p in REPLY_PREFIXES) {
                if (s.length >= p.length && s.substring(0, p.length).equals(p, ignoreCase = true)) {
                    s = s.substring(p.length).trimStart()
                    changed = true
                }
            }
        }
        return s
    }

    /**
     * 剥掉 Message-ID 外层的尖括号。
     * 输入 "<abc@x>" -> "abc@x"；已无尖括号的原样返回。
     */
    fun stripAngleBrackets(value: String?): String? {
        if (value == null) return null
        val t = value.trim()
        if (t.isEmpty()) return null
        val s = if (t.startsWith("<")) t.drop(1) else t
        val e = if (s.endsWith(">")) s.dropLast(1) else s
        return e.trim().ifEmpty { null }
    }

    /**
     * 解析 References header，返回去尖括号的 Message-ID 列表。
     * References 以空白分隔，可能跨行。
     */
    fun parseReferences(header: String?): List<String> {
        if (header.isNullOrBlank()) return emptyList()
        return header.split(Regex("\\s+"))
            .mapNotNull { stripAngleBrackets(it) }
    }

    /**
     * 从 MimeMessage 抽取纯文本正文。
     *
     * 策略（优先级由高到低）：
     * 1. text/plain part
     * 2. text/html part（做最朴素的去标签，MVP 够用）
     * 3. message.content 本身是 String
     *
     * 找不到时返回空串，不抛异常。
     */
    fun extractPlainBody(message: MimeMessage): String {
        val plain = findFirstPart(message, "text/plain")
        if (plain != null) return partToString(plain)

        val html = findFirstPart(message, "text/html")
        if (html != null) return stripHtml(partToString(html))

        val content = runCatching { message.content }.getOrNull()
        if (content is String) return content
        return ""
    }

    /**
     * 递归遍历 multipart 找首个指定 mimeType 的 part。
     * 跳过 disposition=attachment 的部分，避免把附件当正文读。
     */
    private fun findFirstPart(part: Part, mimeType: String): Part? {
        val disp = runCatching { part.disposition }.getOrNull()
        val isAttachment = Part.ATTACHMENT.equals(disp, ignoreCase = true)

        if (!isAttachment && part.isMimeType(mimeType)) return part

        val content = runCatching { part.content }.getOrNull()
        if (content is Multipart) {
            for (i in 0 until content.count) {
                val found = findFirstPart(content.getBodyPart(i), mimeType)
                if (found != null) return found
            }
        }
        return null
    }

    private fun partToString(part: Part): String {
        val c = runCatching { part.content }.getOrNull() ?: return ""
        return when (c) {
            is String -> c
            is java.io.InputStream -> c.bufferedReader(Charsets.UTF_8).use { it.readText() }
            else -> c.toString()
        }
    }

    /**
     * 最朴素的 HTML 去标签：只保留可见文本。
     * 不追求完美，MVP 只保底展示。
     */
    private fun stripHtml(html: String): String =
        html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .trim()

    /**
     * 解码 MIME encoded-word（=?UTF-8?B?...?=），Subject / 文件名常见。
     * 解码失败时原样返回。
     */
    fun decodeMime(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return runCatching { MimeUtility.decodeText(value) }.getOrDefault(value)
    }
}
