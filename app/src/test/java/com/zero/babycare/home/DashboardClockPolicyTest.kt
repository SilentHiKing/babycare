package com.zero.babycare.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DashboardClockPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 9, 20)

    @Test
    fun `midnight reloads even when there are no predictions`() {
        val before = day.atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        assertTrue(DashboardClockPolicy.needsDayReload(before, before + 60_000L, zone.id, zone))
    }

    @Test
    fun `ordinary clock ticks do not query records again`() {
        val before = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertFalse(DashboardClockPolicy.needsDayReload(before, before + 60_000L, zone.id, zone))
    }

    @Test
    fun `timezone change reloads day boundaries even within the same date`() {
        val now = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue(DashboardClockPolicy.needsDayReload(now, now, zone.id, ZoneId.of("Asia/Tokyo")))
    }
}
