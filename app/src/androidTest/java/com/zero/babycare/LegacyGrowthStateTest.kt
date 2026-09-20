package com.zero.babycare

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.statistics.StatisticsViewModel
import com.zero.babycare.statistics.model.StatisticsUiState
import com.zero.babycare.statistics.model.TimelineEditTarget
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.ChildDailyRecord
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** 真实统计 VM 只读独立 Room，验证旧表详情按身份恢复，不把长载荷装进 SavedState。 */
@RunWith(AndroidJUnit4::class)
class LegacyGrowthStateTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val handler = Handler(Looper.getMainLooper())
    private val selected = LocalDate.of(2026, 5, 2)

    @Test
    fun restoredDetailReloadsOriginalRowAndDoesNotEnterGrowthCalculations() = withModels { repository, store ->
        val record = legacy(11, BABY, "原始备注".repeat(600))
        val second = legacy(12, BABY, "同日第二次测量")
        runBlocking {
            repository.insertChildDailyRecord(record)
            repository.insertChildDailyRecord(second)
        }
        val saved = savedState()
        val first = create(repository, store, saved)
        onMain { first.setBabyId(BABY) }
        await(first) { !it.isLoading && it.dayRecord.timelineItems.size == 2 }
        onMain {
            assertTrue(first.uiState.value.dayRecord.timelineItems.all { it.editTarget is TimelineEditTarget.LegacyGrowth })
            assertEquals("旧记录的日与周期数量必须一致", listOf(2, 2, 2),
                first.uiState.value.insights.trend.summaries.map { it.otherEventCount })
            first.openLegacyGrowthDetail(record.recordId)
            assertTrue(requireNotNull(first.uiState.value.legacyGrowthDetail).contentText.contains(record.extra))
            assertNull(first.uiState.value.insights.growth.weight.latestValue)
            assertNull(first.uiState.value.insights.growth.height.latestValue)
            assertNull(first.uiState.value.insights.percentile?.weight?.latestValue)
            val identityKeys = saved.keys().filter { it.startsWith("statistics_legacy_detail_") }
            assertEquals(setOf(DETAIL_BABY, DETAIL_RECORD), identityKeys.toSet())
            assertTrue(identityKeys.all { saved.get<Any>(it) is Int })
        }
        val restoredState = snapshot(saved)
        onMain { store.clear() }
        // 模拟另一入口更新之后再冷恢复：详情应来自新查询，不能重放旧长备注快照。
        val updated = record.copy(extra = "查询后的新备注\n保留原文", pic = "content://synthetic/photo")
        runBlocking { repository.updateChildDailyRecord(updated) }
        val restoredHandle = SavedStateHandle(restoredState)
        val restored = create(repository, store, restoredHandle)
        onMain {
            assertNull(restored.uiState.value.legacyGrowthDetail)
            restored.setBabyId(BABY)
        }
        await(restored) { !it.isLoading && it.legacyGrowthDetail?.recordId == record.recordId }
        onMain {
            assertTrue(requireNotNull(restored.uiState.value.legacyGrowthDetail).contentText.contains(updated.extra))
            assertTrue(requireNotNull(restored.uiState.value.legacyGrowthDetail).contentText.contains(updated.pic))
            restored.dismissLegacyGrowthDetail(BABY, record.recordId)
            assertNull(restored.uiState.value.legacyGrowthDetail)
            assertFalse(restoredHandle.contains(DETAIL_RECORD))
        }
        val closedState = snapshot(restoredHandle)
        onMain { store.clear() }
        val closed = create(repository, store, SavedStateHandle(closedState))
        onMain { closed.setBabyId(BABY) }
        await(closed) { !it.isLoading && it.dayRecord.timelineItems.size == 2 }
        assertNull(onMain { closed.uiState.value.legacyGrowthDetail })
        assertEquals(listOf(updated, second).sortedBy { it.recordId },
            runBlocking { repository.getAllChildDailyRecords(BABY) }.sortedBy { it.recordId })
        assertTrue(runBlocking { repository.getAllEventRecords(BABY) }.isEmpty())
    }

    @Test
    fun changedBabyDateAndDeletedRowCannotRestoreOrDismissAnotherDetail() = withModels { repository, store ->
        val firstRow = legacy(21, BABY, "宝宝一")
        val secondRow = legacy(22, OTHER_BABY, "宝宝二")
        runBlocking {
            repository.insertChildDailyRecord(firstRow)
            repository.insertChildDailyRecord(secondRow)
        }
        val saved = savedState()
        val model = create(repository, store, saved)
        onMain { model.setBabyId(BABY) }
        await(model) { !it.isLoading && it.dayRecord.timelineItems.size == 1 }
        onMain {
            model.openLegacyGrowthDetail(firstRow.recordId)
            model.setBabyId(OTHER_BABY)
            assertNull(model.uiState.value.legacyGrowthDetail)
        }
        await(model) { !it.isLoading && it.babyId == OTHER_BABY }
        onMain {
            model.openLegacyGrowthDetail(firstRow.recordId)
            assertNull(model.uiState.value.legacyGrowthDetail)
            model.openLegacyGrowthDetail(secondRow.recordId)
            model.dismissLegacyGrowthDetail(BABY, firstRow.recordId)
            assertEquals(secondRow.recordId, model.uiState.value.legacyGrowthDetail?.recordId)
            model.selectDate(selected.plusDays(1))
            assertNull(model.uiState.value.legacyGrowthDetail)
            assertFalse(saved.contains(DETAIL_RECORD))
        }
        await(model) { !it.isLoading && it.selectedDate == selected.plusDays(1) }
        onMain { model.selectDate(selected) }
        await(model) { !it.isLoading && it.dayRecord.timelineItems.size == 1 }
        onMain { model.openLegacyGrowthDetail(secondRow.recordId) }
        val pending = snapshot(saved)
        onMain { store.clear() }
        runBlocking { repository.deleteChildDailyRecord(secondRow) }
        val restoredHandle = SavedStateHandle(pending)
        val restored = create(repository, store, restoredHandle)
        onMain { restored.setBabyId(OTHER_BABY) }
        await(restored) { !it.isLoading && it.babyId == OTHER_BABY }
        onMain {
            assertNull(restored.uiState.value.legacyGrowthDetail)
            assertFalse(restoredHandle.contains(DETAIL_RECORD))
        }
        assertEquals(listOf(firstRow), runBlocking { repository.getAllChildDailyRecords(BABY) })
    }

    private fun withModels(block: (BabyRepository, ViewModelStore) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(app, BabyDatabase::class.java).build()
        val repository = BabyRepository(database)
        val store = ViewModelStore()
        try {
            runBlocking {
                repository.insertBabyInfo(BabyInfo(babyId = BABY, name = "隔离成长一"))
                repository.insertBabyInfo(BabyInfo(babyId = OTHER_BABY, name = "隔离成长二"))
            }
            block(repository, store)
        } finally {
            onMain { store.clear() }
            database.close()
        }
    }

    private fun create(repository: BabyRepository, store: ViewModelStore, saved: SavedStateHandle) = onMain {
        StatisticsViewModel(app, saved, repository).also { store.put("statistics", it) }
    }

    private fun savedState() = SavedStateHandle(mapOf("statistics_selected_date" to selected.toString()))
    private fun snapshot(saved: SavedStateHandle): Map<String, Any?> = onMain {
        saved.keys().associateWith { saved.get<Any?>(it) }
    }

    private fun legacy(id: Int, baby: Int, note: String) = ChildDailyRecord(
        recordId = id, babyId = baby,
        recordDate = selected.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        weight = 4.234567f, height = 54.12345f, headCircumference = 37.45678f, extra = note
    )

    private fun await(model: StatisticsViewModel, ready: (StatisticsUiState) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            if (onMain { ready(model.uiState.value) }) return
            SystemClock.sleep(25L)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue("旧成长统计状态未完成恢复", onMain { ready(model.uiState.value) })
    }

    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask(Callable(action))
        handler.post(task)
        return try { task.get(2, TimeUnit.SECONDS) } finally { handler.removeCallbacks(task) }
    }

    private companion object {
        const val BABY = 2_000_000_121
        const val OTHER_BABY = 2_000_000_122
        const val DETAIL_BABY = "statistics_legacy_detail_baby"
        const val DETAIL_RECORD = "statistics_legacy_detail_record"
    }
}
