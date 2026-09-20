package com.zero.babydata

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 独立内存库验证跨日摘要对应的原记录仍可查找和编辑，不接触用户记录。 */
@RunWith(AndroidJUnit4::class)
class TimelineDayCoverageTest {
    private lateinit var database: BabyDatabase
    private lateinit var repository: BabyRepository
    private lateinit var originalZone: TimeZone
    private val hour = 3_600_000L

    @Before fun setUp() = runBlocking {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, BabyDatabase::class.java
        ).build()
        repository = BabyRepository(database)
        database.babyInfoDao().insertBabyInfo(BabyInfo(babyId = 1, name = "合成时间轴 A"))
        database.babyInfoDao().insertBabyInfo(BabyInfo(babyId = 2, name = "合成时间轴 B"))
        Unit
    }

    @After fun tearDown() {
        database.close()
        TimeZone.setDefault(originalZone)
    }

    @Test fun overnightRecordsRemainEditableOnEveryCoveredDayWithoutChangingCounts() = runBlocking {
        val date = LocalDate.of(2026, 2, 1)
        val feeding = FeedingRecord(
            feedingId = 11, babyId = 1, feedingType = FeedingType.FORMULA.type,
            feedingStart = at("2026-01-31T23:30"), feedingEnd = at("2026-02-01T00:30"),
            feedingDuration = hour / 2, feedingAmount = 120, note = "合成跨日喂养", createdAt = 123L
        )
        val sleep = SleepRecord(
            sleepId = 12, babyId = 1, sleepStart = at("2026-01-31T22:00"),
            sleepEnd = at("2026-02-01T07:00"), sleepDuration = 9 * hour,
            note = "合成跨夜睡眠", createdAt = 456L
        )
        repository.insertFeedingRecord(feeding)
        repository.insertSleepRecord(sleep)
        val summary = repository.getDaySummary(1, date)
        assertEquals(0, summary.feedingCount)
        assertEquals(15, summary.feedingTotalMinutes)
        assertEquals(0, summary.sleepCount)
        assertEquals(420, summary.sleepTotalMinutes)
        // 必须返回原实体而非截断后的副本，否则从当天进入编辑可能改写原始起止时间。
        assertEquals(listOf(BabyRepository.TimelineRecord.Feeding(feeding), BabyRepository.TimelineRecord.Sleep(sleep)),
            repository.getTimelineRecords(1, date))
        assertEquals(setOf(date), repository.getDatesWithRecords(1, YearMonth.from(date)))
        assertEquals(setOf(date.minusDays(1)), repository.getDatesWithRecords(1, YearMonth.of(2026, 1)))
    }

    @Test fun midnightBoundaryLegacyZeroAndIncompleteRecordsDoNotLeakIntoAnotherDay() = runBlocking {
        val date = LocalDate.of(2026, 2, 1)
        val midnight = at("2026-02-01T00:00")
        val intervals = listOf(
            Triple(1, midnight - hour, midnight), // 恰在午夜结束，不属于次日。
            Triple(2, midnight - hour, midnight + 1L), // 旧 duration=0 仍有有效完整区间。
            Triple(3, midnight - hour, 0L), // 未完成记录不能无限延伸。
            Triple(4, midnight - hour, midnight - 2 * hour), // 倒置记录不跨日。
            Triple(5, midnight + 24 * hour, midnight + 25 * hour), // 未来一天不能泄漏到选中日。
            Triple(6, midnight + hour, 0L) // 原日异常记录仍须可进入修正。
        )
        intervals.forEach { (id, start, end) ->
            repository.insertFeedingRecord(FeedingRecord(feedingId = id, babyId = 1,
                feedingType = FeedingType.BREAST.type, feedingStart = start, feedingEnd = end, feedingDuration = 0L))
            repository.insertSleepRecord(SleepRecord(sleepId = id, babyId = 1,
                sleepStart = start, sleepEnd = end, sleepDuration = 0L))
        }
        repository.insertSleepRecord(SleepRecord(sleepId = 20, babyId = 2,
            sleepStart = midnight - hour, sleepEnd = midnight + hour, sleepDuration = 2 * hour))
        val timeline = repository.getTimelineRecords(1, date)
        assertEquals(setOf(2, 6), timeline.filterIsInstance<BabyRepository.TimelineRecord.Feeding>().map { it.record.feedingId }.toSet())
        assertEquals(setOf(2, 6), timeline.filterIsInstance<BabyRepository.TimelineRecord.Sleep>().map { it.record.sleepId }.toSet())
        assertEquals(setOf(date, date.plusDays(1)), repository.getDatesWithRecords(1, YearMonth.from(date)))
    }

    @Test fun calendarCoverageUsesLocalDatesAcrossDaylightSavingAndExclusiveMonthEnd() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val date = LocalDate.of(2026, 3, 8)
        val sleep = SleepRecord(sleepId = 1, babyId = 1,
            sleepStart = at("2026-03-07T23:00"), sleepEnd = at("2026-03-09T00:00"), sleepDuration = 24 * hour)
        repository.insertSleepRecord(sleep)
        repository.insertSleepRecord(SleepRecord(sleepId = 2, babyId = 1,
            sleepStart = at("2026-02-28T23:00"), sleepEnd = at("2026-03-01T00:00"), sleepDuration = hour))
        assertEquals(23 * 60, repository.getDaySummary(1, date).sleepTotalMinutes)
        assertEquals(listOf(BabyRepository.TimelineRecord.Sleep(sleep)), repository.getTimelineRecords(1, date))
        assertEquals(setOf(date.minusDays(1), date), repository.getDatesWithRecords(1, YearMonth.from(date)))
    }

    private fun at(value: String): Long = LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
