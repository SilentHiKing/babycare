package com.zero.babycare.statistics.mapper

import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.GrowthData
import java.time.LocalDate
import java.time.ZoneId

/**
 * 成长记录按选中日期做截止过滤，避免历史统计页展示未来才录入的数据。
 */
object StatisticsGrowthCutoff {

    data class GrowthRecordData(
        val record: EventRecord,
        val data: GrowthData
    )

    fun filterUntil(
        records: List<EventRecord>,
        selectedDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<EventRecord> {
        val endMillis = StatisticsDateRange.day(selectedDate, zone).endMillis
        return records.filter { it.time <= endMillis }
    }

    fun validGrowthDataUntil(
        records: List<EventRecord>,
        selectedDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<GrowthRecordData> {
        return filterUntil(records, selectedDate, zone)
            .mapNotNull { record ->
                val data = GrowthData.fromJson(record.extraData) ?: return@mapNotNull null
                GrowthRecordData(record = record, data = data)
            }
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

    fun latestValidGrowthDataUntil(
        records: List<EventRecord>,
        selectedDate: LocalDate,
        limit: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<GrowthRecordData> {
        return validGrowthDataUntil(records, selectedDate, zone)
            .sortedByDescending { it.record.time }
            .take(limit)
    }
}
