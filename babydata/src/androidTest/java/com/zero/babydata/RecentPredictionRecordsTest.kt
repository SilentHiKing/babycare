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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 独立内存库验证 SQL 先选有效样本再限量，不读写用户宝宝或记录。 */
@RunWith(AndroidJUnit4::class)
class RecentPredictionRecordsTest {
    private lateinit var database: BabyDatabase
    private lateinit var repository: BabyRepository
    private val base = 1_767_225_600_000L
    private val minute = 60_000L
    private val hour = 3_600_000L

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, BabyDatabase::class.java).build()
        repository = BabyRepository(database)
        database.babyInfoDao().insertBabyInfo(BabyInfo(babyId = 1, name = "合成样本 A"))
        database.babyInfoDao().insertBabyInfo(BabyInfo(babyId = 2, name = "合成样本 B"))
        Unit
    }

    @After fun tearDown() { database.close() }

    @Test fun milkSelectionFiltersBeforeLimitAndLastFeedingStillIncludesSolidFood() = runBlocking {
        val types = listOf(FeedingType.BREAST, FeedingType.FORMULA, FeedingType.MIXED, FeedingType.BREAST)
        types.forEachIndexed { index, type ->
            insertFeeding(index + 1, base + index * 3 * hour, base + index * 3 * hour + 15 * minute, type)
        }
        repeat(15) { index ->
            insertFeeding(100 + index, base + 10 * hour + index * minute, base + 10 * hour + index * minute + 30_000L, FeedingType.SOLID_FOOD)
        }
        insertFeeding(200, base + 100 * hour, 0L)
        insertFeeding(201, base + 100 * hour, base + 90 * hour)
        insertFeeding(202, base + 90 * hour, base + 90 * hour)
        insertFeeding(203, base + 200 * hour, base + 201 * hour, babyId = 2)

        assertEquals(listOf(4, 3, 2, 1), repository.getRecentCompletedMilkFeedings(1, 15).map { it.feedingId })
        assertEquals(listOf(4, 3), repository.getRecentCompletedMilkFeedings(1, 2).map { it.feedingId })
        assertEquals("首页距上次喂养仍应显示辅食，不改为上次奶类", 114, repository.getLastFeedingRecord(1)!!.feedingId)
        assertEquals(listOf(203), repository.getRecentCompletedMilkFeedings(2, 15).map { it.feedingId })
    }

    @Test fun latestCompletedLongIntervalsSurviveLaterStartingNestedRecords() = runBlocking {
        insertFeeding(1, base, base + 10 * hour)
        insertSleep(1, base, base + 10 * hour)
        repeat(16) { index ->
            val start = base + (index + 1) * 20 * minute
            insertFeeding(index + 2, start, start + 5 * minute)
            insertSleep(index + 2, start, start + 5 * minute)
        }
        insertSleep(100, base + 100 * hour, 0L)
        insertSleep(101, base + 100 * hour, base + 90 * hour)
        insertSleep(102, base + 90 * hour, base + 90 * hour)
        insertSleep(103, base + 200 * hour, base + 201 * hour, babyId = 2)

        val expected = listOf(1) + (17 downTo 4).toList()
        assertEquals(expected, repository.getRecentCompletedMilkFeedings(1, 15).map { it.feedingId })
        assertEquals(expected, repository.getRecentCompletedSleeps(1, 15).map { it.sleepId })
        assertEquals(1, repository.getLastSleepRecord(1)!!.sleepId)
        assertEquals(listOf(103), repository.getRecentCompletedSleeps(2, 15).map { it.sleepId })
    }

    @Test fun equalCompletionTimesUseStartThenIdForStableLimitedResults() = runBlocking {
        listOf(3, 1, 2).forEach { id ->
            val start = base + if (id == 1) 0L else minute
            insertFeeding(id, start, base + hour)
            insertSleep(id, start, base + hour)
        }
        assertEquals(listOf(3, 2), repository.getRecentCompletedMilkFeedings(1, 2).map { it.feedingId })
        assertEquals(listOf(3, 2), repository.getRecentCompletedSleeps(1, 2).map { it.sleepId })
        assertEquals(3, repository.getLastFeedingRecord(1)!!.feedingId)
        assertEquals(3, repository.getLastSleepRecord(1)!!.sleepId)
    }

    private suspend fun insertFeeding(
        id: Int, start: Long, end: Long, type: FeedingType = FeedingType.BREAST, babyId: Int = 1
    ) {
        repository.insertFeedingRecord(FeedingRecord(
            feedingId = id, babyId = babyId, feedingType = type.type,
            feedingStart = start, feedingEnd = end, feedingDuration = (end - start).coerceAtLeast(0L)
        ))
    }

    private suspend fun insertSleep(id: Int, start: Long, end: Long, babyId: Int = 1) {
        repository.insertSleepRecord(SleepRecord(
            sleepId = id, babyId = babyId, sleepStart = start, sleepEnd = end,
            sleepDuration = (end - start).coerceAtLeast(0L)
        ))
    }
}
