package com.zero.common.components.util

import android.annotation.SuppressLint
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 兼容性日期时间工具类
 * 支持多种日期格式解析和转换，兼容 Android 各个版本
 */
object CompatDateUtils {

    // 支持的各种日期格式
    private val SUPPORTED_FORMATS = arrayOf(
        "yyyy年MM月dd日 HH:mm:ss",
        "yyyy年M月d日 HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy.MM.dd HH:mm:ss",
        "yyyy年MM月dd日 HH:mm",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm",
        "yyyy年MM月dd日",
        "yyyy-MM-dd",
        "yyyy/MM/dd",
        "HH:mm:ss",
        "HH:mm"
    )

    // 线程安全的 SimpleDateFormat 缓存
    private val formatCache = ConcurrentHashMap<String, ThreadLocal<SimpleDateFormat>>()

    /**
     * 获取 SimpleDateFormat 实例（线程安全）
     */
    private fun getDateFormat(pattern: String): SimpleDateFormat {
        var threadLocal = formatCache[pattern]
        if (threadLocal == null) {
            threadLocal = ThreadLocal.withInitial {
                SimpleDateFormat(pattern, Locale.getDefault())
            }
            formatCache[pattern] = threadLocal
        }
        return threadLocal.get()!!
    }

    /**
     * 将日期字符串转换为时间戳（自动识别格式）
     */
    fun stringToTimestamp(dateString: String): Long? {
        if (dateString.isBlank()) return null

        // 按格式优先级尝试解析
        for (format in SUPPORTED_FORMATS) {
            try {
                val sdf = getDateFormat(format)
                synchronized(sdf) {
                    val date = sdf.parse(dateString)
                    return date?.time
                }
            } catch (e: ParseException) {
                // 尝试下一种格式
                continue
            } catch (e: Exception) {
                // 其他异常也继续尝试
                continue
            }
        }

        // 如果标准格式都失败，尝试智能解析
        return trySmartParse(dateString)
    }

    /**
     * 智能解析日期字符串
     */
    private fun trySmartParse(dateString: String): Long? {
        return try {
            // 移除可能的中文描述
            var cleanedString = dateString
                .replace("上午", "")
                .replace("下午", "")
                .replace("凌晨", "")
                .replace("晚上", "")
                .trim()

            // 尝试常见的分隔符组合
            val patterns = arrayOf(
                "yyyy-MM-dd HH:mm:ss",
                "yyyy/MM/dd HH:mm:ss",
                "yyyy.MM.dd HH:mm:ss",
                "yyyyMMdd HH:mm:ss",
                "yyyy-MM-dd",
                "yyyy/MM/dd"
            )

            for (pattern in patterns) {
                try {
                    val sdf = getDateFormat(pattern)
                    synchronized(sdf) {
                        val date = sdf.parse(cleanedString)
                        return date?.time
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将时间戳转换为指定格式的字符串
     */
    fun timestampToString(timestamp: Long, pattern: String = "yyyy年MM月dd日 HH:mm:ss"): String {
        return try {
            val sdf = getDateFormat(pattern)
            synchronized(sdf) {
                sdf.format(Date(timestamp))
            }
        } catch (e: Exception) {
            // 降级处理：使用默认格式
            try {
                val defaultSdf = getDateFormat("yyyy-MM-dd HH:mm:ss")
                synchronized(defaultSdf) {
                    defaultSdf.format(Date(timestamp))
                }
            } catch (e2: Exception) {
                timestamp.toString() // 最终降级
            }
        }
    }

    /**
     * 获取当前时间的中文日期字符串
     */
    fun getCurrentChineseDateTime(): String {
        return timestampToString(System.currentTimeMillis(), "yyyy年MM月dd日 HH:mm:ss")
    }

    /**
     * 获取当前时间戳
     */
    fun getCurrentTimestamp(): Long {
        return System.currentTimeMillis()
    }

    /**
     * 安全解析日期，提供默认值
     */
    fun safeStringToTimestamp(dateString: String, defaultTimestamp: Long = 0L): Long {
        return stringToTimestamp(dateString) ?: defaultTimestamp
    }

    /**
     * 判断字符串是否是有效的日期
     */
    fun isValidDateString(dateString: String): Boolean {
        return stringToTimestamp(dateString) != null
    }

    /**
     * 获取支持的格式列表（用于显示）
     */
    fun getSupportedFormats(): List<String> {
        return SUPPORTED_FORMATS.toList()
    }

    /**
     * 清理缓存（在内存紧张时调用）
     */
    fun clearCache() {
        formatCache.clear()
    }
}