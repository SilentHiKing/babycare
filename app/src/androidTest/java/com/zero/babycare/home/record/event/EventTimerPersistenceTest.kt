package com.zero.babycare.home.record.event

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.home.record.PersistentTimerEditor
import com.zero.babycare.home.record.RecordMutationController
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.R
import com.zero.babydata.domain.TimerRecordKind
import com.zero.babydata.domain.TimerStatus
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import com.zero.common.util.TimerClockSnapshot
import com.zero.components.widget.RecordTimerPanelView
import com.zero.components.widget.RecordView.RecordState
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 使用独立内存库和真实 VM，验证活动计时写回原业务行，不读写设备业务数据。 */
@RunWith(AndroidJUnit4::class)
class EventTimerPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var database: BabyDatabase
    private lateinit var repository: BabyRepository
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, BabyDatabase::class.java).build()
        repository = BabyRepository(database)
        repository.insertBabyInfo(BabyInfo(babyId = BABY, name = "合成活动计时"))
        Unit
    }

    @After fun tearDown() {
        clearModels()
        if (::database.isInitialized) database.close()
    }

    @Test fun allDurationTypesFinishTheStartedRowAndPreserveItsIdentity() = runBlocking {
        val types = listOf(EventType.ACTIVITY_OUTDOOR, EventType.ACTIVITY_TUMMY_TIME,
            EventType.ACTIVITY_SWIMMING, EventType.ACTIVITY_PLAY, EventType.CARE_BATH, EventType.CARE_MASSAGE)
        for (type in types) {
            val vm = model(type)
            onMain { vm.openTimer(BABY, type) }
            awaitTimer(vm) { !it.busy }
            onMain { vm.startTimer(BABY, System.currentTimeMillis() - 5_000L, false, "开始备注") }
            val started = requireNotNull(awaitTimer(vm) { !it.busy && it.snapshot != null }.snapshot)
            assertEquals(TimerStatus.RUNNING, started.status)
            onMain {
                vm.setNote("完成备注")
                vm.saveRecord(target())
            }
            assertNull(awaitResult(vm).errorRes)
            val saved = requireNotNull(repository.getEventRecordById(started.ref.recordId))
            assertEquals(TimerStatus.COMPLETED.code, saved.timerStatus)
            assertEquals(started.ref.sessionId, saved.timerSessionId)
            assertEquals(started.startAt, saved.time)
            assertTrue(requireNotNull(saved.activeDurationMs) >= 5_000L)
            assertEquals("完成备注", saved.note)
            assertNull(repository.timers.findUnfinished(TimerRecordKind.EVENT, BABY, type))
        }
        assertEquals(types.size, repository.getAllEventRecords(BABY).size)
    }

    @Test fun coldViewModelRestoresPausedActivityAndExcludesThePauseWhenResumed() = runBlocking {
        val type = EventType.CARE_MASSAGE
        val startAt = System.currentTimeMillis() - 600_000L
        val startClock = TimerClockSnapshot(startAt, 1_000L, processId = "合成时钟")
        val started = repository.timers.startEvent(EventRecord(babyId = BABY, type = type, time = startAt), startClock)
        val paused = repository.timers.pause(started.ref, startClock.copy(wallTimeMs = startAt + 60_000L, elapsedRealtimeMs = 61_000L))
        val first = model(type)
        onMain { first.openTimer(BABY, type) }
        assertEquals(paused, awaitTimer(first) { !it.busy }.snapshot)
        clearModels()
        val restored = model(type)
        onMain { restored.openTimer(BABY, type, paused.ref.sessionId) }
        assertEquals(paused, awaitTimer(restored) { !it.busy }.snapshot)
        onMain { restored.timer.resume() }
        val resumed = requireNotNull(awaitTimer(restored) { !it.busy && it.snapshot?.status == TimerStatus.RUNNING }.snapshot)
        assertEquals(2, resumed.metadata.segments.size)
        assertEquals(paused.metadata.segments.single(), resumed.metadata.segments.first())
        onMain { restored.setNote("冷恢复后完成"); restored.saveRecord(target()) }
        assertNull(awaitResult(restored).errorRes)
        val saved = requireNotNull(repository.getEventRecordById(paused.ref.recordId))
        assertEquals(paused.ref.sessionId, saved.timerSessionId)
        assertTrue(requireNotNull(saved.activeDurationMs) < saved.endTime - saved.time - 300_000L)
        assertEquals(1, repository.getAllEventRecords(BABY).size)
    }

    @Test fun failedFinishKeepsStoppedRowAndRetryDoesNotExtendItsEnd() = runBlocking {
        val type = EventType.ACTIVITY_PLAY
        val startAt = System.currentTimeMillis() - 120_000L
        val clock = TimerClockSnapshot(startAt, 1_000L, processId = "合成校时")
        val started = repository.timers.startEvent(EventRecord(babyId = BABY, type = type, time = startAt), clock)
        val uncertain = repository.timers.pause(started.ref, clock.copy(wallTimeMs = startAt + 60_000L, elapsedRealtimeMs = 31_000L))
        assertTrue(uncertain.metadata.requiresReview)
        val vm = model(type)
        onMain { vm.openTimer(BABY, type, uncertain.ref.sessionId) }
        awaitTimer(vm) { !it.busy }
        onMain { vm.setNote("完成失败仍保留备注"); vm.saveRecord(target()) }
        val failure = awaitResult(vm)
        assertNotNull(failure.errorRes)
        val stopped = requireNotNull(repository.timers.getBySession(TimerRecordKind.EVENT, BABY, uncertain.ref.sessionId))
        assertEquals(TimerStatus.STOPPED, stopped.status)
        assertEquals(uncertain.endAt, stopped.endAt)
        assertEquals("完成失败仍保留备注", stopped.event?.note)
        onMain { assertTrue(vm.mutation.consume(failure)); vm.saveRecord(target()) }
        assertNotNull(awaitResult(vm).errorRes)
        val retried = requireNotNull(repository.timers.getBySession(TimerRecordKind.EVENT, BABY, stopped.ref.sessionId))
        // 完成前独立持久化详情允许增加 revision，但不能延长计时、丢备注或换成另一条记录。
        assertTrue(retried.ref.revision >= stopped.ref.revision)
        assertEquals(stopped.ref.copy(revision = retried.ref.revision), retried.ref)
        assertEquals(TimerStatus.STOPPED, retried.status)
        assertEquals(stopped.startAt, retried.startAt)
        assertEquals(stopped.endAt, retried.endAt)
        assertEquals(stopped.durationMs, retried.durationMs)
        assertEquals(stopped.metadata, retried.metadata)
        assertEquals(stopped.event?.copy(timerRevision = 0L), retried.event?.copy(timerRevision = 0L))
        assertEquals(stopped.ref.recordId, repository.getEventRecordById(stopped.ref.recordId)?.eventId)
        assertEquals(listOf(retried), repository.timers.observeUnfinished(BABY).first())
    }

    @Test fun committedOrDeletedSessionCannotBecomeAnotherManualRecordAfterColdRestore() = runBlocking {
        val type = EventType.ACTIVITY_OUTDOOR
        val startAt = System.currentTimeMillis() - 60_000L
        val clock = TimerClockSnapshot(startAt, 1_000L, processId = "合成完成时钟")
        val started = repository.timers.startEvent(EventRecord(babyId = BABY, type = type, time = startAt), clock)
        val stopped = repository.timers.stop(started.ref, clock.copy(wallTimeMs = startAt + 30_000L, elapsedRealtimeMs = 31_000L))
        val completed = repository.timers.finalize(stopped.ref)
        val restored = model(type)
        onMain {
            restored.setDurationStartTime(startAt)
            restored.setEndTime(startAt + 30_000L)
            restored.openTimer(BABY, type, completed.ref.sessionId)
        }
        assertEquals(completed, awaitTimer(restored) { !it.busy }.snapshot)
        onMain { restored.saveRecord(target()) }
        assertNotNull(awaitResult(restored).errorRes)
        assertEquals(listOf(completed.event), repository.getAllEventRecords(BABY))
        repository.deleteEventRecordById(completed.ref.recordId)
        clearModels()
        val deleted = model(type)
        onMain {
            deleted.setDurationStartTime(startAt)
            deleted.setEndTime(startAt + 30_000L)
            deleted.openTimer(BABY, type, completed.ref.sessionId)
        }
        assertNotNull(awaitTimer(deleted) { !it.busy }.errorRes)
        onMain { deleted.saveRecord(target()) }
        assertEquals(RecordMutationController.State.Idle, deleted.mutation.state.value)
        assertTrue(repository.getAllEventRecords(BABY).isEmpty())
    }

    @Test fun realActivityKeepsPartialEndAfterRecreationAndFinishesTheOriginalTimerRow() {
        assumeTrue("真实页面导航仅在有合成宝宝的 AVD 运行", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<Application>()
        val injection = EventInjection(repository)
        app.registerActivityLifecycleCallbacks(injection)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val current = ActivityScenario.launch(MainActivity::class.java)
            scenario = current
            awaitActivity(current) { mainVm(it).babyDataLoaded.value }
            var babyId: Int? = null
            current.onActivity { babyId = mainVm(it).getCurrentBabyInfo()?.babyId }
            assumeTrue("AVD 需已有用于导航的合成宝宝", babyId != null)
            val id = requireNotNull(babyId)
            runBlocking { repository.insertBabyInfo(BabyInfo(babyId = id, name = "合成活动页面")) }
            current.onActivity { mainVm(it).navigateTo(NavTarget.EventRecord(EventCategory.ACTIVITY.id)) }
            awaitActivity(current) { eventFragment(it)?.view != null }
            current.onActivity {
                ViewModelProvider(requireNotNull(eventFragment(it)))[EventRecordViewModel::class.java]
                    .selectSubtype(requireNotNull(EventSubtype.fromType(EventType.ACTIVITY_OUTDOOR)))
            }
            awaitActivity(current) {
                val fragment = eventFragment(it) ?: return@awaitActivity false
                val vm = ViewModelProvider(fragment)[EventRecordViewModel::class.java]
                timerPanel(it) != null && !vm.timer.state.value.busy && vm.timer.state.value.eventType == EventType.ACTIVITY_OUTDOOR
            }
            current.onActivity {
                val panel = requireNotNull(timerPanel(it))
                panel.startInput.setTimestamp(System.currentTimeMillis() - 5_000L)
                assertTrue(panel.timerView.performClick())
            }
            awaitActivity(current) { timerPanel(it)?.timerView?.currentShowState == RecordState.RECORDING }
            current.onActivity { assertTrue(requireNotNull(timerPanel(it)).timerView.performClick()) }
            awaitActivity(current) {
                val fragment = eventFragment(it) ?: return@awaitActivity false
                val vm = ViewModelProvider(fragment)[EventRecordViewModel::class.java]
                !vm.timer.state.value.busy && vm.timer.state.value.snapshot?.status == TimerStatus.PAUSED &&
                    timerPanel(it)?.timerView?.currentShowState == RecordState.PAUSE
            }
            val paused = runBlocking { requireNotNull(repository.timers.findUnfinished(TimerRecordKind.EVENT, id, EventType.ACTIVITY_OUTDOOR)) }
            current.onActivity {
                requireNotNull(eventFragment(it)).requireView().findViewById<EditText>(R.id.etNote).setText("重建后完成的备注")
                requireNotNull(timerPanel(it)).endInput.setText("12-")
            }
            current.recreate()
            awaitActivity(current) {
                val panel = timerPanel(it)
                panel?.endInput?.text?.toString() == "12-" && panel.timerView.currentShowState == RecordState.PAUSE
            }
            current.onActivity {
                val root = requireNotNull(eventFragment(it)).requireView()
                val panel = requireNotNull(timerPanel(it))
                assertEquals("重建后完成的备注", root.findViewById<EditText>(R.id.etNote).text.toString())
                assertTrue(root.findViewById<View>(R.id.btnSave).performClick())
                assertNotNull(panel.endInput.error)
                assertEquals(0L, panel.endInput.getTimestamp())
            }
            assertEquals(paused, runBlocking { repository.timers.getBySession(TimerRecordKind.EVENT, id, paused.ref.sessionId) })
            current.onActivity {
                requireNotNull(timerPanel(it)).endInput.restoreInputState(requireNotNull(paused.endAt), "")
                assertTrue(requireNotNull(eventFragment(it)).requireView().findViewById<View>(R.id.btnSave).performClick())
            }
            awaitActivity(current) { mainVm(it).navTarget.value == NavTarget.Dashboard }
            val saved = runBlocking { requireNotNull(repository.getEventRecordById(paused.ref.recordId)) }
            assertEquals(TimerStatus.COMPLETED.code, saved.timerStatus)
            assertEquals(paused.ref.sessionId, saved.timerSessionId)
            assertEquals(paused.durationMs, saved.activeDurationMs)
            assertEquals("重建后完成的备注", saved.note)
            assertEquals(1, runBlocking { repository.getAllEventRecords(id).size })
        } finally {
            scenario?.onActivity { mainVm(it).navigateTo(NavTarget.Dashboard) }
            scenario?.close()
            app.unregisterActivityLifecycleCallbacks(injection)
        }
    }

    /** 在页面第一次创建 VM 前替换数据源，Activity 只读取导航宝宝，所有事件写入内存库。 */
    private class EventInjection(private val repository: BabyRepository) : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity !is MainActivity) return
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentPreCreated(fm: FragmentManager, fragment: Fragment, state: Bundle?) {
                    if (fragment !is EventRecordFragment) return
                    ViewModelProvider(fragment, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = EventRecordViewModel(repository) as T
                    })[EventRecordViewModel::class.java]
                }
            }, false)
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun mainVm(activity: MainActivity) = ViewModelProvider(activity)[MainViewModel::class.java]
    private fun eventFragment(activity: MainActivity) = activity.supportFragmentManager.fragments
        .filterIsInstance<EventRecordFragment>().firstOrNull { !it.isHidden }
    private fun timerPanel(activity: MainActivity) = eventFragment(activity)?.view?.findViewById<RecordTimerPanelView>(R.id.timerPanel)
    private fun awaitActivity(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < end)
        throw AssertionError("活动计时页面未达到预期状态")
    }

    private fun model(type: Int): EventRecordViewModel = onMain {
        EventRecordViewModel(repository).also { vm ->
            stores += ViewModelStore().apply { put("activity", vm) }
            val subtype = requireNotNull(EventSubtype.fromType(type))
            vm.selectCategory(subtype.category)
            vm.selectSubtype(subtype)
        }
    }

    private fun target() = RecordMutationController.Target(UUID.randomUUID().toString(), BABY, null,
        NavTarget.EventRecord(), NavTarget.Dashboard)
    private suspend fun awaitTimer(vm: EventRecordViewModel, predicate: (PersistentTimerEditor.State) -> Boolean) =
        withTimeout(10_000L) { vm.timer.state.first(predicate) }
    private suspend fun awaitResult(vm: EventRecordViewModel) = withTimeout(10_000L) {
        vm.mutation.state.filterIsInstance<RecordMutationController.State.Finished>().first()
    }
    private fun clearModels() = onMain { stores.forEach(ViewModelStore::clear); stores.clear() }
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    companion object { private const val BABY = 2_000_000_042 }
}
