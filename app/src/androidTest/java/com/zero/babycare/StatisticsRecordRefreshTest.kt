package com.zero.babycare

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.home.BabyRecordChangeBus
import com.zero.babycare.statistics.StatisticsViewModel
import com.zero.babycare.statistics.model.CalendarBrowseState
import com.zero.babycare.statistics.model.StatisticsUiState
import com.zero.babycare.statistics.model.SummaryMetricType
import com.zero.common.util.LanguageManager
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** 通过独立 Room 和真实变更总线，覆盖返回页面先于提交完成的加载顺序。 */
@RunWith(AndroidJUnit4::class)
class StatisticsRecordRefreshTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val handler = Handler(Looper.getMainLooper())
    private val selected = LocalDate.of(2026, 8, 15)

    @Test
    fun lateSaveEditAndDeleteRefreshLoadedStatisticsWithoutChangingBrowseContext() = withModel { repository, model ->
        val record = feeding(1, selected)
        runBlocking { repository.insertFeedingRecord(record) }
        onMain { model.setBabyId(BABY_ID) }
        await(model) { !it.isLoading && feedingCountIs(it, 1) }
        val browse = CalendarBrowseState.forDate(selected, monthMode = true)
        onMain { model.onCalendarBrowsed(browse) }
        await(model) { selected in it.datesWithRecords }

        // 页面已经完成旧快照加载，之后另一页面才提交；不再调用 refreshData 掩盖竞态。
        val second = feeding(2, selected)
        runBlocking { repository.insertFeedingRecord(second) }
        BabyRecordChangeBus.notifyChanged(BABY_ID)
        await(model) { !it.isLoading && feedingCountIs(it, 2) && it.dayRecord.timelineItems.size == 2 }

        val moved = selected.plusDays(2)
        runBlocking { repository.updateFeedingRecord(second.copy(feedingStart = at(moved), feedingEnd = at(moved) + 600_000L)) }
        BabyRecordChangeBus.notifyChanged(BABY_ID)
        await(model) { !it.isLoading && feedingCountIs(it, 1) && moved in it.datesWithRecords }

        runBlocking { repository.deleteFeedingRecordById(record.feedingId) }
        BabyRecordChangeBus.notifyChanged(BABY_ID)
        await(model) { !it.isLoading && it.dayRecord.timelineItems.isEmpty() && selected !in it.datesWithRecords }
        assertEquals(selected, onMain { model.uiState.value.selectedDate })
        assertEquals(browse, onMain { model.uiState.value.calendarBrowse })

        // 切换宝宝后，旧宝宝的提交不得把旧列表带回；全域导入失效仍须刷新当前宝宝。
        onMain { model.setBabyId(OTHER_BABY_ID) }
        await(model) { !it.isLoading && it.babyId == OTHER_BABY_ID }
        val otherBabyState = onMain { model.uiState.value }
        runBlocking { repository.insertFeedingRecord(feeding(3, selected, OTHER_BABY_ID)) }
        onMain { BabyRecordChangeBus.notifyChanged(BABY_ID) }
        onMain {
            assertSame("无关宝宝的提交不能触发当前页加载", otherBabyState, model.uiState.value)
            assertTrue(feedingCountIs(model.uiState.value, 0))
        }
        BabyRecordChangeBus.notifyAllChanged()
        await(model) { !it.isLoading && it.babyId == OTHER_BABY_ID && feedingCountIs(it, 1) }
    }

    @Test
    fun monthMarkersIncludeVisibleLeadingAndTrailingWeeks() = withModel { repository, model ->
        val dates = setOf(LocalDate.of(2026, 7, 31), selected, LocalDate.of(2026, 9, 1))
        runBlocking { dates.forEachIndexed { index, date -> repository.insertFeedingRecord(feeding(index + 1, date)) } }
        onMain {
            model.onCalendarBrowsed(CalendarBrowseState.forDate(selected, monthMode = true))
            model.setBabyId(BABY_ID)
        }
        await(model) { !it.isLoading && it.datesWithRecords.isNotEmpty() }
        assertEquals("补齐周中的可点击日期也必须有记录点", dates, onMain { model.uiState.value.datesWithRecords })
        assertEquals(selected, onMain { model.uiState.value.selectedDate })
        assertTrue(onMain { feedingCountIs(model.uiState.value, 1) })
    }

    private fun withModel(block: (BabyRepository, StatisticsViewModel) -> Unit) {
        assumeTrue("真实变更总线会重排提醒，仅在审查 AVD 运行", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val database = Room.inMemoryDatabaseBuilder(app, BabyDatabase::class.java).build()
        val repository = BabyRepository(database)
        val store = ViewModelStore()
        try {
            runBlocking {
                repository.insertBabyInfo(BabyInfo(babyId = BABY_ID, name = "隔离统计一"))
                repository.insertBabyInfo(BabyInfo(babyId = OTHER_BABY_ID, name = "隔离统计二"))
            }
            val model = onMain {
                StatisticsViewModel(app, SavedStateHandle(mapOf("statistics_selected_date" to selected.toString())), repository)
                    .also { store.put("statistics", it) }
            }
            block(repository, model)
        } finally {
            onMain { store.clear() }
            database.close()
        }
    }

    private fun feeding(id: Int, date: LocalDate, babyId: Int = BABY_ID) = FeedingRecord(
        feedingId = id, babyId = babyId, feedingType = FeedingType.FORMULA.type,
        feedingStart = at(date), feedingEnd = at(date) + 600_000L, feedingDuration = 600_000L, feedingAmount = 100
    )

    private fun at(date: LocalDate) = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun feedingCountIs(state: StatisticsUiState, count: Int) =
        state.dayRecord.summaryMetrics.firstOrNull { it.type == SummaryMetricType.FEEDING }?.primaryText ==
            LanguageManager.localizedContext(app).getString(com.zero.common.R.string.times_count_format, count)

    private fun await(model: StatisticsViewModel, predicate: (StatisticsUiState) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            if (onMain { predicate(model.uiState.value) }) return
            SystemClock.sleep(25L)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue("统计提交后的状态未在限定时间内更新", onMain { predicate(model.uiState.value) })
    }

    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask(Callable(action))
        handler.post(task)
        return try { task.get(2, TimeUnit.SECONDS) } finally { handler.removeCallbacks(task) }
    }

    companion object {
        private const val BABY_ID = 2_000_000_101
        private const val OTHER_BABY_ID = 2_000_000_102
    }
}
