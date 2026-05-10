package com.szgenle.agentpost.core.ui.time

import android.content.Context
import com.szgenle.agentpost.core.ui.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 相对时间格式化工具。
 *
 * 规则（按本地时区 & 当前 Locale）：
 * - ts <= 0 → 空串（表示"无效时间"）
 * - < 60 秒                → "刚刚"
 * - < 60 分钟              → "N 分钟前"
 * - 同一自然日              → "N 小时前"
 * - 昨天（自然日 -1）       → "昨天 HH:mm"
 * - 同一自然年              → "MM-dd"
 * - 不同年                  → "yyyy-MM-dd"
 *
 * 所有文案走 strings.xml，多语言友好；日期 pattern 交给 [SimpleDateFormat] 直接本地化输出。
 */
object RelativeTime {

    fun format(context: Context, ts: Long, now: Long = System.currentTimeMillis()): String {
        if (ts <= 0L) return ""
        val diff = (now - ts).coerceAtLeast(0L)

        val seconds = TimeUnit.MILLISECONDS.toSeconds(diff)
        if (seconds < 60) {
            return context.getString(R.string.relative_time_just_now)
        }

        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        if (minutes < 60) {
            return context.getString(R.string.relative_time_minutes_ago, minutes.toInt())
        }

        val tsCal = Calendar.getInstance().apply { timeInMillis = ts }
        val nowCal = Calendar.getInstance().apply { timeInMillis = now }

        if (isSameDay(tsCal, nowCal)) {
            val hours = TimeUnit.MILLISECONDS.toHours(diff).toInt().coerceAtLeast(1)
            return context.getString(R.string.relative_time_hours_ago, hours)
        }
        if (isYesterday(tsCal, nowCal)) {
            val time = hourMinuteFormat().format(Date(ts))
            return context.getString(R.string.relative_time_yesterday, time)
        }
        val formatter = if (tsCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)) {
            monthDayFormat()
        } else {
            fullDateFormat()
        }
        return formatter.format(Date(ts))
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun isYesterday(ts: Calendar, now: Calendar): Boolean {
        val y = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return isSameDay(ts, y)
    }

    // 每次都 new：SimpleDateFormat 非线程安全，format 频率低，不做缓存
    private fun hourMinuteFormat() = SimpleDateFormat("HH:mm", Locale.getDefault())
    private fun monthDayFormat() = SimpleDateFormat("MM-dd", Locale.getDefault())
    private fun fullDateFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
}
