package com.zero.common.components.util

import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 日期时间操作扩展工具类
 */
object DateOperationUtils {

    /**
     * 计算两个时间戳之间的时间差（友好显示）
     */
    fun getTimeDifference(
        startTimestamp: Long,
        endTimestamp: Long = System.currentTimeMillis()
    ): String {
        val diff = endTimestamp - startTimestamp
        val diffAbs = Math.abs(diff)

        return when {
            diffAbs < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
            diffAbs < TimeUnit.HOURS.toMillis(1) -> {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(diffAbs)
                "${minutes}分钟${if (diff < 0) "后" else "前"}"
            }

            diffAbs < TimeUnit.DAYS.toMillis(1) -> {
                val hours = TimeUnit.MILLISECONDS.toHours(diffAbs)
                "${hours}小时${if (diff < 0) "后" else "前"}"
            }

            diffAbs < TimeUnit.DAYS.toMillis(30) -> {
                val days = TimeUnit.MILLISECONDS.toDays(diffAbs)
                "${days}天${if (diff < 0) "后" else "前"}"
            }

            else -> {
                val months = diffAbs / (TimeUnit.DAYS.toMillis(30))
                "${months}个月${if (diff < 0) "后" else "前"}"
            }
        }
    }

    /**
     * 检查时间戳是否是今天
     */
    fun isToday(timestamp: Long): Boolean {
        val calendarNow = Calendar.getInstance()
        val calendarTarget = Calendar.getInstance().apply { timeInMillis = timestamp }

        return calendarNow.get(Calendar.YEAR) == calendarTarget.get(Calendar.YEAR) &&
                calendarNow.get(Calendar.MONTH) == calendarTarget.get(Calendar.MONTH) &&
                calendarNow.get(Calendar.DAY_OF_MONTH) == calendarTarget.get(Calendar.DAY_OF_MONTH)
    }

    /**
     * 检查时间戳是否是今年
     */
    fun isThisYear(timestamp: Long): Boolean {
        val calendarNow = Calendar.getInstance()
        val calendarTarget = Calendar.getInstance().apply { timeInMillis = timestamp }

        return calendarNow.get(Calendar.YEAR) == calendarTarget.get(Calendar.YEAR)
    }

    /**
     * 获取时间戳对应的星期几
     */
    fun getDayOfWeek(timestamp: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "星期日"
            Calendar.MONDAY -> "星期一"
            Calendar.TUESDAY -> "星期二"
            Calendar.WEDNESDAY -> "星期三"
            Calendar.THURSDAY -> "星期四"
            Calendar.FRIDAY -> "星期五"
            Calendar.SATURDAY -> "星期六"
            else -> ""
        }
    }
}