package com.zero.babycare.statistics.mapper

import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.GrowthData
import java.time.LocalDate
import java.time.ZoneId

/**
 * 成长记录按选中日期做截止过滤，避免历史统计页展示未来才录入的数据。
 */
object StatisticsGrowthCutoff {

    // 同一测量时刻优先较晚创建的记录；旧数据的创建时间相同时，用持久 ID 保证各统计区块一致。
    internal val measurementOrder: Comparator<EventRecord> =
        compareBy<EventRecord> { it.time }.thenBy { it.createdAt }.thenBy { it.eventId }

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
            .sortedWith(measurementOrder.reversed())
            .take(limit)
    }

    fun latestValidGrowthDataUntil(
        records: List<EventRecord>,
        selectedDate: LocalDate,
        limit: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<GrowthRecordData> {
        return validGrowthDataUntil(records, selectedDate, zone)
            .sortedWith { left, right -> measurementOrder.compare(right.record, left.record) }
            .take(limit)
    }
}
