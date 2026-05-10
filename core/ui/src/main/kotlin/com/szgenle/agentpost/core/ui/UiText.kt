package com.szgenle.agentpost.core.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * 用于在非 UI 层（ViewModel / Repository）承载待渲染文案的轻量抽象。
 *
 * ViewModel 只产出 [UiText]，不直接持有本地化字符串，避免耦合 Android 资源；
 * Composable 层通过 [asString] 或 [stringValue] 在有 Context 的环境中渲染。
 *
 * 使用示例：
 * ```
 * // ViewModel:
 * state = state.copy(message = UiText.Resource(R.string.xxx_failed, listOf(e.message ?: "")))
 *
 * // Composable:
 * Text(state.message.stringValue())
 * ```
 */
sealed interface UiText {
    /** 动态字符串（已经本地化好的原始文本，例如来自服务端的错误信息）。 */
    data class Dynamic(val value: String) : UiText

    /** 资源字符串 + 可选的格式化参数。参数仅支持 String / Int / Long / Double / Float。 */
    data class Resource(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    /** 在非 Composable 上下文中渲染（如 Service、BroadcastReceiver）。 */
    fun asString(context: Context): String = when (this) {
        is Dynamic -> value
        is Resource -> if (args.isEmpty()) {
            context.getString(id)
        } else {
            context.getString(id, *args.toTypedArray())
        }
    }

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): UiText =
            Resource(id, args.toList())
    }
}

/** Composable 环境下的便捷渲染，自动感知 LocalContext 与 LocalConfiguration 变更。 */
@Composable
fun UiText.stringValue(): String = when (this) {
    is UiText.Dynamic -> value
    is UiText.Resource -> if (args.isEmpty()) {
        stringResource(id)
    } else {
        stringResource(id, *args.toTypedArray())
    }
}
