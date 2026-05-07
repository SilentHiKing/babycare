package com.zero.babycare.statistics.mapper

import com.zero.babydata.entity.EventRecord
import java.time.LocalDate
import java.time.ZoneId

/**
 * 成长记录按选中日期做截止过滤，避免历史统计页展示未来才录入的数据。
 */
object StatisticsGrowthCutoff {

    fun filterUntil(
        records: List<EventRecord>,
        selectedDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<EventRecord> {
        val endMillis = StatisticsDateRange.day(selectedDate, zone).endMillis
        return records.filter { it.time <= endMillis }
    }

    fun latestUntil(
        records: List<EventRecord>,
        selectedDate: LocalDate,
        limit: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<EventRecord> {
        return filterUntil(records, selectedDate, zone)
            .sortedByDescending { it.time }
            .take(limit)
    }
}
