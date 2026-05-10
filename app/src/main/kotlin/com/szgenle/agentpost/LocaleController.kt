package com.szgenle.agentpost

import android.content.Context
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.szgenle.agentpost.core.datastore.AppPreferences
import kotlinx.coroutines.runBlocking

/**
 * 应用语言初始化与应用。
 *
 * 策略：
 *  - 首次启动（DataStore 里 languageTag 为 null）：按系统语言判定，简体中文 → `zh-CN`，其余 → `en`；
 *    把结果写回 DataStore 并通过 [AppCompatDelegate.setApplicationLocales] 生效。
 *  - 非首次：直接 apply 保存的 tag（幂等；AppCompat 自身也会在 pre-33 上从 SharedPreferences 恢复，
 *    这里主动 apply 只是为了行为一致，便于推理）。
 *
 * 调用点：[AgentPostApp.onCreate]。
 */
internal object LocaleController {

    /** 首次启动时支持的语言 tag（也是设置界面对用户暴露的两个选项）。 */
    const val TAG_ZH_CN = "zh-CN"
    const val TAG_EN = "en"

    /**
     * Application 启动时一次性执行。必须同步（Application.onCreate 不能挂起），
     * 所以用 [runBlocking] 读 DataStore 首值——偏好量很小，不会阻塞。
     */
    fun initialize(context: Context) {
        val prefs = AppPreferences(context.applicationContext)
        val stored = runBlocking { prefs.getLanguageTag() }
        val tag = stored ?: detectSystemDefaultTag().also { picked ->
            runBlocking { prefs.setLanguageTag(picked) }
        }
        apply(tag)
    }

    /** 切换语言：主线程调用。由 [AppCompatDelegate] 自动触发当前 Activity recreate。 */
    fun apply(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(tag),
        )
    }

    /**
     * 根据系统 Locale 判定默认应该使用哪一种语言。
     * 规则：简体中文（含 CN / SG / script=Hans）→ 中文，其他（含繁体中文）→ 英文。
     */
    private fun detectSystemDefaultTag(): String {
        val sysLocale = Resources.getSystem().configuration.locales[0]
        val language = sysLocale.language
        val country = sysLocale.country
        val script = sysLocale.script
        val isSimplifiedChinese = language == "zh" && (
            script == "Hans" ||
                country == "CN" ||
                country == "SG" ||
                // 无 script 且未指定繁体区域（TW/HK/MO），按简体处理
                (script.isEmpty() && country !in TRADITIONAL_REGIONS)
            )
        return if (isSimplifiedChinese) TAG_ZH_CN else TAG_EN
    }

    private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")
}
