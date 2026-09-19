package com.zero.babycare.statistics

import com.zero.babycare.statistics.mapper.StatisticsGrowthCutoff
import com.zero.babycare.statistics.mapper.StatisticsGrowthHistory
import com.zero.babycare.statistics.model.GrowthPercentilePoint
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/** 对照旧的完整历史计算，验证提早停止只减少工作、不改变最近值或曲线语义。 */
class StatisticsGrowthHistoryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val birthDate = LocalDate.of(2020, 1, 1)
    private val birthMillis = birthDate.atStartOfDay(zone).toInstant().toEpochMilli()
    private val selectedDate = birthDate.plusDays(40)

    @Test
    fun `recent results match full history across invalid rows age bounds and equal times`() = runBlocking {
        val records = buildList {
            for (index in 0..80) add(record(index, day = index / 2 - 5))
            add(record(100, 20, "broken-json"))
            add(record(101, 24, "null"))
            add(record(102, 40, "broken-json"))
            add(record(103, 41))
        }
        // 顺序随机且含同一时间项：不能用简单反转替代稳定降序，也不能原始 takeLast(12)。
        repeat(8) { seed ->
            val shuffled = records.shuffled(Random(seed))
            val history = StatisticsGrowthHistory.create(shuffled, selectedDate, zone)
            assertEquals(reference(shuffled), history.recentPercentiles(
                birthMillis, { it.value }, ::rank, { it * 2.0 }
            ))
            assertEquals(
                StatisticsGrowthCutoff.latestValidGrowthDataUntil(shuffled, selectedDate, 2, zone),
                history.latestValid()
            )
        }
    }

    @Test
    fun `WHO out of range latest value remains while earlier valid points fill all twelve places`() = runBlocking {
        val records = (1..32).map { record(it, it) } + record(90, 35, "broken-json")
        val history = StatisticsGrowthHistory.create(records, selectedDate, zone)
        val actual = history.recentPercentiles(birthMillis, { it.value }, ::rank, { it * 2.0 })
        assertEquals(reference(records), actual)
        assertEquals(64.0, actual.latestValue!!, 0.0)
        assertNull(actual.latestPercentile)
        assertEquals(12, actual.points.size)
        assertEquals(birthDate.plusDays(19), actual.points.first().date)
        assertEquals(birthDate.plusDays(30), actual.points.last().date)
    }

    @Test
    fun `before birth records are excluded only from percentiles and empty histories stay empty`() = runBlocking {
        for (records in listOf(emptyList(), listOf(record(1, -2), record(2, -1)), listOf(record(3, 40)))) {
            val history = StatisticsGrowthHistory.create(records, selectedDate, zone)
            assertEquals(reference(records), history.recentPercentiles(
                birthMillis, { it.value }, ::rank, { it * 2.0 }
            ))
            assertEquals(
                StatisticsGrowthCutoff.latestValidGrowthDataUntil(records, selectedDate, 2, zone),
                history.latestValid()
            )
        }
    }

    @Test
    fun `same timestamp keeps first original values for trend and last original values for percentile`() = runBlocking {
        val records = (1..20).map { record(it, 15) }
        val history = StatisticsGrowthHistory.create(records, selectedDate, zone)
        assertEquals(listOf(1, 2), history.latestValid().map { it.record.eventId })
        val result = history.recentPercentiles(birthMillis, { it.value }, ::rank, { it * 2.0 })
        assertEquals(40.0, result.latestValue!!, 0.0)
        assertEquals((9..20).toList(), result.points.map { it.percentile })
        assertEquals(reference(records), result)
    }

    @Test
    fun `point limit boundary keeps every available point in its original order`() = runBlocking {
        for (count in listOf(0, 1, 11, 12, 13)) {
            val records = (1..count).map { record(it, 10) }
            val history = StatisticsGrowthHistory.create(records, selectedDate, zone)
            var evaluations = 0
            val result = history.recentPercentiles(birthMillis, { it.value }, { age, value ->
                evaluations++
                rank(age, value)
            }, { it * 2.0 })
            assertEquals(reference(records), result)
            assertEquals(minOf(count, 12), evaluations)
        }
    }

    @Test
    fun `complete twelve point window stops WHO work for large valid history`() = runBlocking {
        val records = (1..5_000).map { record(it, 10) }
        val history = StatisticsGrowthHistory.create(records, selectedDate, zone)
        var evaluations = 0
        val result = history.recentPercentiles(
            birthMillis,
            { it.value },
            { _, value -> evaluations++; value.toInt() },
            { it }
        )
        assertEquals(12, evaluations)
        assertEquals((4_989..5_000).toList(), result.points.map { it.percentile })
        assertEquals(5_000.0, result.latestValue!!, 0.0)
    }

    @Test
    fun `cancellation during WHO mapping stops before the next record`() = runBlocking {
        val history = StatisticsGrowthHistory.create((1..5_000).map { record(it, 10) }, selectedDate, zone)
        val request = Job()
        var evaluations = 0
        try {
            withContext(request) {
                history.recentPercentiles(birthMillis, { it.value }, { _, _ ->
                    evaluations++
                    if (evaluations == 5) request.cancel()
                    null
                }, { it })
            }
            fail("取消后不能返回部分统计")
        } catch (_: CancellationException) {
            assertEquals(5, evaluations)
        } finally {
            request.cancel()
        }
    }

    @Test
    fun `cancellation while preparing history stops reading the input`() = runBlocking {
        val request = Job()
        var reads = 0
        val records = object : AbstractList<EventRecord>() {
            override val size = 50_000
            override fun get(index: Int): EventRecord {
                reads++
                if (reads == 17) request.cancel()
                return record(index, 10)
            }
        }
        try {
            withContext(request) { StatisticsGrowthHistory.create(records, selectedDate, zone) }
            fail("取消后不能继续构建历史快照")
        } catch (_: CancellationException) {
            assertTrue("取消后仍读取 $reads 条记录", reads <= 18)
        } finally {
            request.cancel()
        }
    }

    /** 保留改动前的全量算法，测试不依赖新算法的排序或提前退出实现。 */
    private fun reference(records: List<EventRecord>): StatisticsGrowthHistory.Percentiles {
        val valid = StatisticsGrowthCutoff.validGrowthDataUntil(records, selectedDate, zone)
            .sortedBy { it.record.time }
        val points = mutableListOf<GrowthPercentilePoint>()
        var latestValue: Double? = null
        var latestPercentile: Int? = null
        valid.forEach { item ->
            val date = Instant.ofEpochMilli(item.record.time).atZone(zone).toLocalDate()
            val ageDays = ChronoUnit.DAYS.between(birthDate, date)
            if (ageDays < 0L) return@forEach
            val percentile = rank(ageDays.toInt(), item.data.value)
            if (percentile != null) points.add(GrowthPercentilePoint(date, percentile))
            latestValue = item.data.value * 2.0
            latestPercentile = percentile
        }
        return StatisticsGrowthHistory.Percentiles(latestValue, latestPercentile, points.takeLast(12))
    }

    private fun rank(ageDays: Int, value: Double): Int? =
        value.toInt().takeIf { ageDays in 0..30 }

    private fun record(id: Int, day: Int, json: String = """{"value":$id,"unit":"cm"}""") = EventRecord(
        eventId = id,
        babyId = 1,
        type = EventType.GROWTH_HEIGHT,
        time = birthDate.plusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli(),
        extraData = json
    )
}
