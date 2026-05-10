package com.szgenle.agentpost.core.common.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SubjectNormalizer] 行为回归测试。
 *
 * 覆盖：
 * - 英文 Re: / Fwd: / Fw: 及大小写变体
 * - 中文 回复: / 答复: / 转发: / 转: 及全角冒号变体
 * - 多级嵌套前缀循环剥离
 * - 前缀与正文之间的空白保留
 * - 空 / null / 空白输入的边界
 * - 前缀仅出现在开头才剥离
 */
class SubjectNormalizerTest {

    // ---- 基础：单前缀 ----

    @Test
    fun `re prefix is stripped`() {
        assertEquals("整理笔记", SubjectNormalizer.normalize("Re: 整理笔记"))
    }

    @Test
    fun `re prefix is case insensitive`() {
        assertEquals("today", SubjectNormalizer.normalize("RE: today"))
        assertEquals("today", SubjectNormalizer.normalize("rE: today"))
    }

    @Test
    fun `fwd prefix is stripped`() {
        assertEquals("整理笔记", SubjectNormalizer.normalize("Fwd: 整理笔记"))
    }

    @Test
    fun `fw prefix is stripped`() {
        assertEquals("周报", SubjectNormalizer.normalize("FW: 周报"))
    }

    @Test
    fun `chinese huifu prefix is stripped`() {
        assertEquals("整理笔记", SubjectNormalizer.normalize("回复: 整理笔记"))
    }

    @Test
    fun `chinese dafu prefix is stripped`() {
        assertEquals("确认事项", SubjectNormalizer.normalize("答复: 确认事项"))
    }

    @Test
    fun `chinese zhuanfa prefix is stripped`() {
        assertEquals("周报", SubjectNormalizer.normalize("转发: 周报"))
    }

    @Test
    fun `chinese zhuan prefix is stripped`() {
        assertEquals("周报", SubjectNormalizer.normalize("转: 周报"))
    }

    // ---- 全角冒号 ----

    @Test
    fun `re with fullwidth colon is stripped`() {
        assertEquals("整理笔记", SubjectNormalizer.normalize("Re：整理笔记"))
    }

    @Test
    fun `chinese prefix with fullwidth colon is stripped`() {
        assertEquals("整理笔记", SubjectNormalizer.normalize("回复：整理笔记"))
    }

    // ---- 嵌套 / 循环剥离 ----

    @Test
    fun `nested prefixes are stripped in loop`() {
        assertEquals("整理笔记", SubjectNormalizer.normalize("Re: Fwd: 回复：整理笔记"))
    }

    @Test
    fun `deeply nested chinese and english prefixes`() {
        assertEquals("今日天气", SubjectNormalizer.normalize("回复: RE: 今日天气"))
    }

    @Test
    fun `nested with fullwidth and halfwidth colons`() {
        assertEquals("周报", SubjectNormalizer.normalize("转发：Fwd: 转: 周报"))
    }

    // ---- 空白处理 ----

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("整理笔记", SubjectNormalizer.normalize("   Re: 整理笔记   "))
    }

    @Test
    fun `extra whitespace between prefix and body is trimmed`() {
        assertEquals("整理笔记", SubjectNormalizer.normalize("Re:    整理笔记"))
    }

    @Test
    fun `whitespace between nested prefixes is tolerated`() {
        assertEquals("整理笔记", SubjectNormalizer.normalize("Re:   Fwd:   整理笔记"))
    }

    // ---- 边界 ----

    @Test
    fun `null returns empty string`() {
        assertEquals("", SubjectNormalizer.normalize(null))
    }

    @Test
    fun `empty returns empty string`() {
        assertEquals("", SubjectNormalizer.normalize(""))
    }

    @Test
    fun `blank returns empty string`() {
        assertEquals("", SubjectNormalizer.normalize("   "))
    }

    @Test
    fun `subject without prefix is returned as-is trimmed`() {
        assertEquals("整理笔记", SubjectNormalizer.normalize("整理笔记"))
        assertEquals("hello", SubjectNormalizer.normalize("  hello  "))
    }

    // ---- 前缀只剥开头 ----

    @Test
    fun `prefix in middle of subject is kept`() {
        // "今日 Re: 天气" 不是标准前缀，应原样保留
        assertEquals("今日 Re: 天气", SubjectNormalizer.normalize("今日 Re: 天气"))
    }

    @Test
    fun `word starting with re but not followed by colon is kept`() {
        // "Reply 整理笔记" 首个 token 不带冒号，不算前缀
        assertEquals("Reply 整理笔记", SubjectNormalizer.normalize("Reply 整理笔记"))
    }

    @Test
    fun `subject that is only prefix returns empty`() {
        // 纯前缀场景（极端输入）：剥完应为空
        assertEquals("", SubjectNormalizer.normalize("Re:"))
        assertEquals("", SubjectNormalizer.normalize("回复："))
        assertEquals("", SubjectNormalizer.normalize("Re: Fwd: 回复："))
    }
}
