package com.zero.babycare.home.record

import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.home.record.event.EventCategory
import com.zero.babycare.home.record.event.EventRecordViewModel
import com.zero.babycare.home.record.event.EventSubtype
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.GrowthData
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import com.zero.common.R
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 使用独立内存库验证新 VM 的恢复重试，不触碰设备上的宝宝、记录或计时会话。 */
@RunWith(AndroidJUnit4::class)
class RecordSaveRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val stores = mutableListOf<ViewModelStore>()
    private lateinit var database: BabyDatabase
    private lateinit var repository: BabyRepository
    private val gson = Gson()

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, BabyDatabase::class.java).build()
        repository = BabyRepository(database)
        repository.insertBabyInfo(BabyInfo(babyId = BABY_ID, name = "合成恢复测试"))
        // 即便测试设备存在用户会话，也不能通过碰撞的宝宝 ID/时间结束它。
        onMain {
            assertNull(OngoingRecordManager.getFeedingSession(BABY_ID))
            assertNull(OngoingRecordManager.getSleepSession(BABY_ID))
        }
    }

    @After
    fun tearDown() {
        onMain { stores.forEach(ViewModelStore::clear) }
        if (::database.isInitialized) database.close()
    }

    @Test
    fun newSleepViewModelRetriesCommittedDraftWithoutAnotherRow() = runBlocking {
        val target = request(NavTarget.SleepRecord(returnTarget = NavTarget.Dashboard))
        val record = SleepRecord(babyId = BABY_ID, sleepStart = START, sleepEnd = END,
            sleepDuration = END - START, note = "恢复睡眠草稿", createdAt = END)
        val saved = roundTrip(Bundle().apply {
            putString("draft_id", target.draftId)
            putString("record", gson.toJson(record))
        })
        val first = newModel { SleepRecordViewModel(repository) }
        onMain { first.save(record, target) }
        assertNull(awaitResult(first.mutation).errorRes)
        val persisted = repository.getAllSleepRecords(BABY_ID).single()

        // 成功结果未被页面消费就丢失 VM，模拟提交完成后进程退出的窄窗口。
        clearModels()
        val restored = newModel { SleepRecordViewModel(repository) }
        assertNotSame(first, restored)
        val restoredRecord = gson.fromJson(saved.getString("record"), SleepRecord::class.java)
        onMain { restored.save(restoredRecord, target.copy(draftId = requireNotNull(saved.getString("draft_id")))) }
        assertNull(awaitResult(restored.mutation).errorRes)
        assertEquals(listOf(persisted), repository.getAllSleepRecords(BABY_ID))
    }

    @Test
    fun newFeedingViewModelRetriesCommittedDraftWithoutAnotherRow() = runBlocking {
        val target = request(NavTarget.FeedingRecord(returnTarget = NavTarget.Dashboard))
        val record = FeedingRecord(babyId = BABY_ID, feedingType = FeedingType.FORMULA.type,
            feedingStart = START, feedingEnd = END, feedingDuration = END - START,
            feedingAmount = 123, note = "恢复喂养草稿", createdAt = END)
        val saved = roundTrip(Bundle().apply {
            putString("draft_id", target.draftId)
            putString("record", gson.toJson(record))
        })
        val first = newModel { FeedingRecordViewModel(repository) }
        onMain { first.save(record, target) }
        assertNull(awaitResult(first.mutation).errorRes)
        val persisted = repository.getAllFeedingRecords(BABY_ID).single()

        clearModels()
        val restored = newModel { FeedingRecordViewModel(repository) }
        assertNotSame(first, restored)
        val restoredRecord = gson.fromJson(saved.getString("record"), FeedingRecord::class.java)
        onMain { restored.save(restoredRecord, target.copy(draftId = requireNotNull(saved.getString("draft_id")))) }
        assertNull(awaitResult(restored.mutation).errorRes)
        assertEquals(listOf(persisted), repository.getAllFeedingRecords(BABY_ID))
    }

    @Test
    fun newEventViewModelRetriesCommittedDraftAndKeepsConflictingInput() = runBlocking {
        val target = request(NavTarget.EventRecord(returnTarget = NavTarget.Dashboard))
        val first = newModel { EventRecordViewModel(repository) }
        onMain {
            first.selectCategory(EventCategory.GROWTH)
            first.selectSubtype(requireNotNull(EventSubtype.fromType(EventType.GROWTH_WEIGHT)))
            first.setEventTime(START)
            first.setExtraData(GrowthData(7.25, "kg"))
            first.setNote("恢复成长草稿")
        }
        val saved = roundTrip(Bundle().apply {
            putString("draft_id", target.draftId)
            putString("draft", gson.toJson(first.captureDraft()))
            putString("note", first.note.value)
            putDouble("value", 7.25)
        })
        onMain { first.saveRecord(target, createdAt = END) }
        assertNull(awaitResult(first.mutation).errorRes)
        val persisted = repository.getAllEventRecords(BABY_ID).single()

        clearModels()
        val restored = newModel { EventRecordViewModel(repository) }
        assertNotSame(first, restored)
        val restoredTarget = target.copy(draftId = requireNotNull(saved.getString("draft_id")))
        onMain {
            restored.restoreDraft(gson.fromJson(saved.getString("draft"), EventRecordViewModel.Draft::class.java))
            restored.setExtraData(GrowthData(saved.getDouble("value"), "kg"))
            restored.setNote(requireNotNull(saved.getString("note")))
            restored.saveRecord(restoredTarget, createdAt = END + 1L)
        }
        val retried = awaitResult(restored.mutation)
        assertNull(retried.errorRes)
        assertEquals(listOf(persisted), repository.getAllEventRecords(BABY_ID))

        onMain {
            restored.mutation.consume(retried)
            restored.setExtraData(GrowthData(8.5, "kg"))
            restored.setNote("保留修改后的输入")
            restored.saveRecord(restoredTarget)
        }
        assertEquals(R.string.record_draft_already_saved, awaitResult(restored.mutation).errorRes)
        assertEquals(GrowthData(8.5, "kg"), restored.extraData.value)
        assertEquals("保留修改后的输入", restored.note.value)
        assertEquals(listOf(persisted), repository.getAllEventRecords(BABY_ID))
    }

    @Test
    fun newEventViewModelDoesNotResurrectDeletedCommittedRecord() = runBlocking {
        val target = request(NavTarget.EventRecord(returnTarget = NavTarget.Dashboard))
        fun restoredModel() = newModel { EventRecordViewModel(repository) }.also { vm ->
            onMain {
                vm.restoreDraft(EventRecordViewModel.Draft(EventCategory.GROWTH.id, EventType.GROWTH_WEIGHT, START, null, null))
                vm.setExtraData(GrowthData(7.25, "kg"))
                vm.setNote("原记录已删除仍保留草稿")
            }
        }
        val first = restoredModel()
        onMain { first.saveRecord(target) }
        assertNull(awaitResult(first.mutation).errorRes)
        val persisted = repository.getAllEventRecords(BABY_ID).single()
        repository.deleteEventRecordById(persisted.eventId)
        clearModels()

        val restored = restoredModel()
        onMain { restored.saveRecord(target) }
        assertEquals(R.string.record_draft_record_deleted, awaitResult(restored.mutation).errorRes)
        assertEquals(GrowthData(7.25, "kg"), restored.extraData.value)
        assertEquals("原记录已删除仍保留草稿", restored.note.value)
        assertEquals(emptyList<Any>(), repository.getAllEventRecords(BABY_ID))
    }

    private fun request(source: NavTarget) = RecordMutationController.Target(
        UUID.randomUUID().toString(), BABY_ID, null, source, NavTarget.Dashboard
    )

    private suspend fun awaitResult(controller: RecordMutationController): RecordMutationController.State.Finished =
        withTimeout(10_000L) { controller.state.filterIsInstance<RecordMutationController.State.Finished>().first() }

    private fun <T : ViewModel> newModel(factory: () -> T): T = onMain {
        factory().also { vm -> stores += ViewModelStore().apply { put("record", vm) } }
    }

    private fun clearModels() = onMain { stores.forEach(ViewModelStore::clear); stores.clear() }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun roundTrip(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    companion object {
        private const val BABY_ID = 2_000_000_001
        private const val START = 1_577_836_800_000L
        private const val END = START + 600_000L
    }
}
