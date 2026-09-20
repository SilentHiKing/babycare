package com.zero.babycare.statistics.mapper

import com.zero.babycare.statistics.model.GrowthPercentilePoint
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.GrowthData
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 单次统计请求的成长快照；只解析实际需要的最近有效记录，不跨请求缓存业务结果。 */
internal class StatisticsGrowthHistory private constructor(
    private val recordsAscending: List<EventRecord>,
    private val zone: ZoneId
) {
    data class Percentiles(
        val latestValue: Double?,
        val latestPercentile: Int?,
        val points: List<GrowthPercentilePoint>
    )

    /** 趋势的有效条件只有 JSON 可解析；与百分位共用确定性顺序，从最新有效测量向前取值。 */
    suspend fun latestValid(limit: Int = 2): List<StatisticsGrowthCutoff.GrowthRecordData> {
        require(limit >= 0)
        val context = currentCoroutineContext()
        context.ensureActive()
        val result = ArrayList<StatisticsGrowthCutoff.GrowthRecordData>(limit)
        for (index in recordsAscending.indices.reversed()) {
            context.ensureActive()
            if (result.size == limit) break
            val record = recordsAscending[index]
            val data = GrowthData.fromJson(record.extraData) ?: continue
            result.add(StatisticsGrowthCutoff.GrowthRecordData(record, data))
        }
        return result
    }

    /**
     * 最新值允许超出 WHO 年龄范围，曲线则只保留最近的有效百分位；无效项不能占用 12 点额度。
     * 倒序遍历确定性升序快照，最后反转曲线点；最新值与趋势取自同一排序规则。
     */
    suspend fun recentPercentiles(
        birthDateMillis: Long,
        standardValue: (GrowthData) -> Double,
        percentile: (ageDays: Int, standardValue: Double) -> Int?,
        displayValue: (Double) -> Double,
        limit: Int = 12
    ): Percentiles {
        require(limit > 0)
        val context = currentCoroutineContext()
        context.ensureActive()
        if (birthDateMillis <= 0L) return Percentiles(null, null, emptyList())
        val birthDate = Instant.ofEpochMilli(birthDateMillis).atZone(zone).toLocalDate()
        val points = ArrayList<GrowthPercentilePoint>(limit)
        var latestValue: Double? = null
        var latestPercentile: Int? = null
        var latestFound = false
        for (index in recordsAscending.indices.reversed()) {
            context.ensureActive()
            val record = recordsAscending[index]
            val data = GrowthData.fromJson(record.extraData) ?: continue
            val date = Instant.ofEpochMilli(record.time).atZone(zone).toLocalDate()
            val ageDays = ChronoUnit.DAYS.between(birthDate, date)
            if (ageDays < 0L) continue
            val value = standardValue(data)
            val rank = percentile(ageDays.toInt(), value)
            context.ensureActive()
            if (!latestFound) {
                latestFound = true
                latestValue = displayValue(value)
                latestPercentile = rank
            }
            if (rank != null) points.add(GrowthPercentilePoint(date, rank))
            if (points.size == limit) break
        }
        points.reverse()
        return Percentiles(latestValue, latestPercentile, points)
    }

    companion object {
        /** 过滤和稳定排序都合作取消，快速切换日期后旧请求不继续占用 CPU。 */
        suspend fun create(
            records: List<EventRecord>,
            selectedDate: LocalDate,
            zone: ZoneId = ZoneId.systemDefault()
        ): StatisticsGrowthHistory {
            val context = currentCoroutineContext()
            context.ensureActive()
            val endMillis = StatisticsDateRange.day(selectedDate, zone).endMillis
            val selected = ArrayList<EventRecord>()
            for (record in records) {
                context.ensureActive()
                if (record.time <= endMillis) selected.add(record)
            }
            var comparisons = 0
            selected.sortWith { left, right ->
                if (comparisons++ % 64 == 0) context.ensureActive()
                StatisticsGrowthCutoff.measurementOrder.compare(left, right)
            }
            context.ensureActive()
            return StatisticsGrowthHistory(selected, zone)
        }
    }
}
