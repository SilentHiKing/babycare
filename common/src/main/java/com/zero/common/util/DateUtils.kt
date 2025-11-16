package com.zero.common.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 日期时间操作扩展工具类
 */
object DateUtils {

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

    // 工具方法：计算当天起止时间
    fun getDayRange(date: Date): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val end = cal.timeInMillis - 1
        return Pair(start, end)
    }

    // 工具方法：获取一天中某个时间点的毫秒数
    fun getTimeOfDayMillis(hour: Int, minute: Int): Long {
        return TimeUnit.HOURS.toMillis(hour.toLong()) + TimeUnit.MINUTES.toMillis(minute.toLong())
    }


    fun diffFromNow(timeStr: String): Long {
        // 定义格式
        val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        // 当前年份补上，否则只有月日时分会默认用 1970 年
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val fullTimeStr = "$currentYear-$timeStr"  // 变成 "2025-11-13 02:23"
        val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        // 解析成时间戳
        val date = fullFormat.parse(fullTimeStr)
        val targetTime = date?.time ?: 0L

        // 当前时间戳
        val now = System.currentTimeMillis()

        // 返回间隔（毫秒）
        return now - targetTime
    }


    fun getDiffFromNow(timeStr: String): Long? {
        // 输入必须是 MM-dd HH:mm 格式，比如 "11-13 02:23"
        val regex = Regex("""\d{2}-\d{2} \d{2}:\d{2}""")
        if (!regex.matches(timeStr)) return null  // 格式不对，直接返回 null

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val fullStr = "$currentYear-$timeStr"  // 拼接成 "2025-11-13 02:23"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sdf.isLenient = false  // 禁止宽松解析，比如 13月 或 25:99 都会报错

        return try {
            val date = sdf.parse(fullStr) ?: return null
            val timestamp = date.time
            System.currentTimeMillis() - timestamp
        } catch (e: Exception) {
            null // 解析失败返回 null
        }
    }

    fun timestampToMMddHHmm(timestamp: Long, isSeconds: Boolean = false): String {
        val actualTimestamp = if (isSeconds) timestamp * 1000 else timestamp
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(actualTimestamp))
    }

    fun timestampToMMddHHmmss(timestamp: Long): String {
        val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * 将 MM-dd HH:mm:ss 格式转换为时间戳（年份为当年）
     * @param dateStr 日期字符串，格式：MM-dd HH:mm:ss
     * @return 时间戳，解析失败返回null
     */
    fun parseToTimestamp(dateStr: String): Long? {
        return try {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val fullDateStr = "$currentYear-$dateStr"

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.parse(fullDateStr)?.time
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将 MM-dd HH:mm:ss 格式转换为时间戳（年份为当年）
     * @param dateStr 日期字符串，格式：MM-dd HH:mm:ss
     * @param default 解析失败时的默认值
     * @return 时间戳
     */
    fun parseToTimestamp(dateStr: String, default: Long): Long {
        return parseToTimestamp(dateStr) ?: default
    }

    /**
     * 将毫秒转换为分钟，智能格式化输出
     * @param milliseconds 毫秒数
     * @return 格式化后的分钟字符串
     */
    fun millisecondsToSmartMinutes(milliseconds: Long): String {
        val totalMinutes = milliseconds / 60000.0

        return when {
            // 正好是整数分钟
            milliseconds % 60000 == 0L -> {
                totalMinutes.toInt().toString()
            }
            // 需要保留两位小数的情况
            else -> {
                // 格式化并移除末尾的0
                val formatted = "%.2f".format(totalMinutes)
                formatted.removeSuffix(".00").removeSuffix("0")
            }
        }
    }


}