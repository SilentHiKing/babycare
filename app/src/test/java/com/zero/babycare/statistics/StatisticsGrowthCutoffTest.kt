package com.zero.babycare.statistics

import com.zero.babycare.statistics.mapper.StatisticsDateRange
import com.zero.babycare.statistics.mapper.StatisticsGrowthCutoff
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatisticsGrowthCutoffTest {

    @Test
    fun `growth records after selected day are excluded`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val selectedDate = LocalDate.of(2026, 5, 2)
        val endOfSelectedDay = StatisticsDateRange.day(selectedDate, zone).endMillis
        val records = listOf(
            growthRecord(1, selectedDate.minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()),
            growthRecord(2, endOfSelectedDay),
            growthRecord(3, selectedDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        )

        val result = StatisticsGrowthCutoff.filterUntil(records, selectedDate, zone)

        assertEquals(listOf(1, 2), result.map { it.eventId })
    }

    @Test
    fun `latest two growth records are sorted newest first before cutoff`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val selectedDate = LocalDate.of(2026, 5, 2)
        val records = listOf(
            growthRecord(1, selectedDate.minusDays(5).atStartOfDay(zone).toInstant().toEpochMilli()),
            growthRecord(2, selectedDate.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()),
            growthRecord(3, selectedDate.minusDays(3).atStartOfDay(zone).toInstant().toEpochMilli()),
            growthRecord(4, selectedDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        )

        val result = StatisticsGrowthCutoff.latestUntil(records, selectedDate, limit = 2, zone = zone)

        assertEquals(listOf(2, 3), result.map { it.eventId })
    }

    @Test
    fun `latest valid growth data skips malformed records before taking limit`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val selectedDate = LocalDate.of(2026, 5, 2)
        val records = listOf(
            growthRecord(1, selectedDate.minusDays(4).atStartOfDay(zone).toInstant().toEpochMilli()),
            growthRecord(2, selectedDate.minusDays(3).atStartOfDay(zone).toInstant().toEpochMilli()),
            growthRecord(
                id = 3,
                time = selectedDate.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                extraData = "broken-json"
            ),
            growthRecord(4, selectedDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        )

        val result = StatisticsGrowthCutoff.latestValidGrowthDataUntil(
            records = records,
            selectedDate = selectedDate,
            limit = 2,
            zone = zone
        )

        assertEquals(listOf(2, 1), result.map { it.record.eventId })
    }

    private fun growthRecord(
        id: Int,
        time: Long,
        extraData: String = """{"value":76.0,"unit":"cm"}"""
    ): EventRecord {
        return EventRecord(
            eventId = id,
            babyId = 1,
            type = EventType.GROWTH_HEIGHT,
            time = time,
            extraData = extraData
        )
    }
}
