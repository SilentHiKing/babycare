package com.zero.common.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/** 日期缓存不能继续沿用用户切换前的时区或语言；测试结束恢复进程配置。 */
class CompatDateUtilsTest {
    private val originalLocale = Locale.getDefault()
    private val originalZone = TimeZone.getDefault()

    @After
    fun restoreConfiguration() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalZone)
        CompatDateUtils.clearCache()
    }

    @Test
    fun cachedBirthdayParserAndFormatterFollowTimeZoneChanges() {
        CompatDateUtils.clearCache()
        Locale.setDefault(Locale.US)
        val timestamp = Instant.parse("2026-09-20T12:34:00Z").toEpochMilli()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals(timestamp, CompatDateUtils.stringToTimestamp("2026-09-20 12:34"))
        assertEquals("2026-09-20 12:34", CompatDateUtils.timestampToString(timestamp, "yyyy-MM-dd HH:mm"))

        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        assertEquals(timestamp, CompatDateUtils.stringToTimestamp("2026-09-20 05:34"))
        assertEquals("2026-09-20 05:34", CompatDateUtils.timestampToString(timestamp, "yyyy-MM-dd HH:mm"))
    }

    @Test
    fun cachedFormatterFollowsLocaleChanges() {
        CompatDateUtils.clearCache()
        val timestamp = Instant.parse("2026-09-20T12:34:00Z").toEpochMilli()
        Locale.setDefault(Locale.US)
        assertEquals("September", CompatDateUtils.timestampToString(timestamp, "MMMM"))
        Locale.setDefault(Locale.FRANCE)
        assertEquals("septembre", CompatDateUtils.timestampToString(timestamp, "MMMM"))
    }
}
