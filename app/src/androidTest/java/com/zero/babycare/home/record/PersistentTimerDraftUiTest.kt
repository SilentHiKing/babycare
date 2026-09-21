package com.zero.babycare.home.record

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
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.R
import com.zero.babycare.home.record.event.EventRecordFragment
import com.zero.babycare.home.record.event.EventRecordViewModel
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.domain.DurationBasis
import com.zero.babydata.domain.TimerMetadata
import com.zero.babydata.domain.TimerMetadataCodec
import com.zero.babydata.domain.TimerPart
import com.zero.babydata.domain.TimerRecordKind
import com.zero.babydata.domain.TimerRecordSnapshot
import com.zero.babydata.domain.TimerStatus
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import com.zero.common.util.TimerClock
import com.zero.common.util.TimerClockSnapshot
import com.zero.components.widget.RecordTimerPanelView
import com.zero.components.widget.RecordView.RecordState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真实表单依赖注入内存 Room；设备现有宝宝只用于导航，不修改业务库或偏好。 */
@RunWith(AndroidJUnit4::class)
class PersistentTimerDraftUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun feedingTimerCommandsLockActionsWithoutFlashingAndCancelledSaveStaysStopped() = withScreen { scenario, database, repository, babyId ->
        val target = target(TimerRecordKind.FEEDING)
        val brightnessFailures = mutableListOf<String>()
        fun captureBrightness(activity: MainActivity, label: String) {
            val timer = requireNotNull(panel(activity, target)).timerView
            val save = requireNotNull(screen(activity, target)).requireView().findViewById<View>(R.id.tvSave)
            if (timer.alpha != 1f || save.alpha != 1f) {
                brightnessFailures += "${label}: timer=${timer.alpha}, save=${save.alpha}"
            }
        }
        navigate(scenario, target)
        await(scenario) {
            val fragment = screen(it, target) ?: return@await false
            val state = timerState(fragment)
            state.babyId == babyId && !state.busy && panel(it, target)?.timerView?.isEnabled == true &&
                fragment.requireView().findViewById<View>(R.id.tvSave).isEnabled
        }
        scenario.onActivity {
            captureBrightness(it, "开始前")
            // 起点取当前进程真实时钟，后续暂停无需跨进程估算或额外核对弹窗。
            requireNotNull(panel(it, target)).startInput.setTimestamp(TimerClock.snapshot().wallTimeMs - 5_000L)
        }
        runBlocking {
            suspend fun blockedCommand(label: String, click: (MainActivity) -> Unit) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val blocker = async(Dispatchers.IO) {
                    database.withTransaction { entered.complete(Unit); release.await() }
                }
                try {
                    withTimeout(10_000L) { entered.await() }
                    scenario.onActivity { click(it) }
                    await(scenario) {
                        val fragment = screen(it, target) ?: return@await false
                        timerState(fragment).busy && panel(it, target)?.timerView?.isEnabled == false &&
                            !fragment.requireView().findViewById<View>(R.id.tvSave).isEnabled
                    }
                    scenario.onActivity {
                        val fragment = requireNotNull(screen(it, target))
                        val timer = requireNotNull(panel(it, target)).timerView
                        val save = fragment.requireView().findViewById<View>(R.id.tvSave)
                        assertTrue("${label}必须保持命令忙碌", timerState(fragment).busy)
                        assertFalse("${label}等待写入时计时入口不可重复操作", timer.isEnabled)
                        assertFalse("${label}等待写入时计时入口不可点击", timer.isClickable)
                        assertFalse("${label}等待写入时保存不可重复操作", save.isEnabled)
                        // 汇总三段亮度诊断，让一次失败完整指出哪些短暂写入使界面闪暗。
                        captureBrightness(it, label)
                    }
                } finally {
                    release.complete(Unit)
                    blocker.await()
                }
            }

            blockedCommand("开始") { assertTrue(requireNotNull(panel(it, target)).timerView.performClick()) }
            await(scenario) {
                val fragment = screen(it, target) ?: return@await false
                !timerState(fragment).busy && timerState(fragment).snapshot?.status == TimerStatus.RUNNING &&
                    panel(it, target)?.timerView?.currentShowState == RecordState.RECORDING
            }
            scenario.onActivity { captureBrightness(it, "开始完成") }
            blockedCommand("暂停") { assertTrue(requireNotNull(panel(it, target)).timerView.performClick()) }
            await(scenario) {
                val fragment = screen(it, target) ?: return@await false
                !timerState(fragment).busy && timerState(fragment).snapshot?.status == TimerStatus.PAUSED &&
                    panel(it, target)?.timerView?.currentShowState == RecordState.PAUSE
            }
            scenario.onActivity { captureBrightness(it, "暂停完成") }
            blockedCommand("保存时停止") {
                assertTrue(requireNotNull(screen(it, target)).requireView().findViewById<View>(R.id.tvSave).performClick())
            }
            await(scenario) {
                val fragment = screen(it, target) ?: return@await false
                !timerState(fragment).busy && timerState(fragment).snapshot?.status == TimerStatus.STOPPED
            }
            scenario.onActivity { captureBrightness(it, "停止完成等待确认") }
        }
        onView(withId(com.zero.components.R.id.tv_cancel)).perform(click())
        instrumentation.waitForIdleSync()
        val stopped = runBlocking { requireNotNull(repository.timers.findUnfinished(TimerRecordKind.FEEDING, babyId)) }
        assertEquals(TimerStatus.STOPPED, stopped.status)
        scenario.onActivity {
            captureBrightness(it, "取消确认后")
            assertEquals("取消确认应留在原记录页", target, mainVm(it).navTarget.value)
            val timer = requireNotNull(panel(it, target))
            assertEquals(stopped.endAt, timer.endInput.getTimestamp())
            assertEquals(stopped.durationMs, timer.timerView.getDuration())
            assertFalse("等待保存不能重新运行", timer.timerView.isTimerRunning())
            assertFalse(timer.timerView.isEnabled)
        }
        // 跨过一次显示秒 tick，确认取消只关闭弹窗，不偷偷继续计时或改写已停止的结束时间。
        SystemClock.sleep(1_100L)
        scenario.onActivity {
            captureBrightness(it, "取消确认后经过显示 tick")
            val timer = requireNotNull(panel(it, target))
            assertEquals(stopped.endAt, timer.endInput.getTimestamp())
            assertEquals(stopped.durationMs, timer.timerView.getDuration())
        }
        assertEquals(stopped, runBlocking { repository.timers.getBySession(TimerRecordKind.FEEDING, babyId, stopped.ref.sessionId) })
        assertTrue("计时命令等待期间亮度应保持 1：${brightnessFailures.joinToString()}", brightnessFailures.isEmpty())
    }

    @Test fun completedSegmentedDateEditsPreviewTheSameEffectiveDurationAsSaving() = withScreen { scenario, _, repository, babyId ->
        for (kind in TimerRecordKind.entries) {
            val original = runBlocking { seedCompletedSegments(repository, babyId, kind) }
            val target = when (kind) {
                TimerRecordKind.FEEDING -> NavTarget.FeedingRecord(original.ref.recordId, NavTarget.Dashboard)
                TimerRecordKind.SLEEP -> NavTarget.SleepRecord(original.ref.recordId, NavTarget.Dashboard)
                TimerRecordKind.EVENT -> NavTarget.EventRecord(editRecordId = original.ref.recordId, returnTarget = NavTarget.Dashboard)
            }
            navigate(scenario, target)
            await(scenario) { panel(it, target)?.timerView?.getDuration() == 120_000L }
            scenario.onActivity {
                val timer = requireNotNull(panel(it, target))
                // 两段各一分钟，中间暂停两分钟；首尾各延长半分钟，只能预览三分钟有效量。
                timer.startInput.setTimestamp(original.startAt - 30_000L, triggerCallback = true)
                assertEquals("${kind} 开始编辑不得补回暂停", 150_000L, timer.timerView.getDuration())
                timer.endInput.setTimestamp(requireNotNull(original.endAt) + 30_000L, triggerCallback = true)
                assertEquals("${kind} 结束编辑不得补回暂停", 180_000L, timer.timerView.getDuration())
                if (kind == TimerRecordKind.FEEDING) {
                    val root = requireNotNull(screen(it, target)).requireView()
                    assertEquals("1", root.findViewById<EditText>(R.id.etLeftBreastDuration).text.toString())
                    assertEquals("1", root.findViewById<EditText>(R.id.etRightBreastDuration).text.toString())
                }
                // 跨越首段末尾不可悄悄删除已知片段，更不能展示整跨度并允许保存。
                timer.startInput.setTimestamp(original.startAt + 61_000L, triggerCallback = true)
                assertNotNull(timer.startInput.error)
                assertTrue(timer.startInput.hasFocus())
                assertEquals(0L, timer.timerView.getDuration())
                assertTrue(requireNotNull(screen(it, target)).requireView()
                    .findViewById<View>(if (kind == TimerRecordKind.EVENT) R.id.btnSave else R.id.tvSave).performClick())
                assertNotNull(timer.startInput.error)
            }
            assertEquals(original, runBlocking { repository.timers.getBySession(kind, babyId, original.ref.sessionId) })
            scenario.onActivity {
                val timer = requireNotNull(panel(it, target))
                timer.startInput.setTimestamp(original.startAt - 30_000L, triggerCallback = true)
                assertNull(timer.startInput.error)
                assertEquals(180_000L, timer.timerView.getDuration())
            }
            scenario.recreate()
            await(scenario) { panel(it, target)?.timerView?.getDuration() == 180_000L }
            scenario.onActivity {
                assertTrue(requireNotNull(screen(it, target)).requireView()
                    .findViewById<View>(if (kind == TimerRecordKind.EVENT) R.id.btnSave else R.id.tvSave).performClick())
            }
            if (kind == TimerRecordKind.FEEDING) onView(withId(com.zero.components.R.id.tv_confirm)).perform(click())
            await(scenario) { mainVm(it).navTarget.value == NavTarget.Dashboard }
            val saved = runBlocking { requireNotNull(repository.timers.getBySession(kind, babyId, original.ref.sessionId)) }
            assertEquals(original.ref.recordId, saved.ref.recordId)
            assertEquals(180_000L, saved.durationMs)
            assertEquals(2, saved.metadata.segments.size)
            assertEquals(120_000L, saved.metadata.segments[1].startAt - requireNotNull(saved.metadata.segments[0].endAt))
            saved.feeding?.let {
                assertEquals(90_000L, it.feedingDurationBreastLeft)
                assertEquals(90_000L, it.feedingDurationBreastRight)
            }
        }
    }

    @Test fun pausedFeedingAndSleepKeepIncompleteEndAcrossRecreation() = withScreen { scenario, _, repository, babyId ->
        for (kind in listOf(TimerRecordKind.FEEDING, TimerRecordKind.SLEEP)) {
            val paused = runBlocking { seedPaused(repository, babyId, kind) }
            val target = target(kind)
            navigate(scenario, target)
            await(scenario) { panel(it, target)?.timerView?.currentShowState == RecordState.PAUSE }
            scenario.onActivity {
                val root = requireNotNull(screen(it, target)).requireView()
                root.findViewById<EditText>(R.id.etNote).setText("未完成输入的合成备注")
                requireNotNull(panel(it, target)).endInput.setText("12")
            }
            scenario.recreate()
            await(scenario) {
                val timer = panel(it, target)
                timer?.endInput?.text?.toString() == "12" && timer.timerView.currentShowState == RecordState.PAUSE &&
                    !timerState(requireNotNull(screen(it, target))).busy
            }
            scenario.onActivity {
                val fragment = requireNotNull(screen(it, target))
                val timer = requireNotNull(panel(it, target))
                assertEquals("未完成输入的合成备注", fragment.requireView().findViewById<EditText>(R.id.etNote).text.toString())
                assertTrue(fragment.requireView().findViewById<View>(R.id.tvSave).performClick())
                assertEquals(0L, timer.endInput.getTimestamp())
                assertNotNull(timer.endInput.error)
                assertTrue(timer.endInput.hasFocus())
            }
            assertEquals(paused, runBlocking { repository.timers.getBySession(kind, babyId, paused.ref.sessionId) })
            scenario.onActivity { mainVm(it).navigateTo(NavTarget.Dashboard) }
            await(scenario) { mainVm(it).navTarget.value == NavTarget.Dashboard && screen(it, target) == null }
        }
    }

    @Test fun unmeasuredBottleRecordCanEditNoteWithoutInventingAnEnd() = withScreen { scenario, _, repository, babyId ->
        val original = FeedingRecord(babyId = babyId, feedingType = FeedingType.FORMULA.type,
            feedingStart = System.currentTimeMillis() - 60_000L, feedingEnd = 0L, feedingDuration = 0L,
            feedingAmount = 120, note = "未测量时长",
            timerData = TimerMetadataCodec.encode(TimerMetadata(basis = DurationBasis.UNMEASURED)))
        runBlocking { repository.insertFeedingRecord(original) }
        val savedOriginal = runBlocking { repository.getAllFeedingRecords(babyId).single() }
        val target = NavTarget.FeedingRecord(savedOriginal.feedingId, NavTarget.Dashboard)
        navigate(scenario, target)
        await(scenario) { screen(it, target)?.view?.findViewById<EditText>(R.id.etNote)?.text?.toString() == original.note }
        scenario.onActivity {
            val root = requireNotNull(screen(it, target)).requireView()
            root.findViewById<EditText>(R.id.etNote).setText("仅更新未测时长的备注")
            assertTrue(root.findViewById<View>(R.id.tvSave).performClick())
        }
        await(scenario) { mainVm(it).navTarget.value == NavTarget.Dashboard }
        val saved = runBlocking { requireNotNull(repository.getFeedingRecordById(savedOriginal.feedingId)) }
        assertEquals(savedOriginal.copy(note = "仅更新未测时长的备注"), saved)
        assertEquals(DurationBasis.UNMEASURED, TimerMetadataCodec.decode(saved.timerData)?.basis)
        assertEquals(0L, saved.feedingEnd)
    }

    @Test fun leavingWhileStopIsPendingCannotAutoSaveTheReopenedForm() = withScreen { scenario, database, repository, babyId ->
        runBlocking {
            for (kind in listOf(TimerRecordKind.FEEDING, TimerRecordKind.SLEEP)) {
                val paused = seedPaused(repository, babyId, kind)
                val target = target(kind)
                navigate(scenario, target)
                await(scenario) { panel(it, target)?.timerView?.currentShowState == RecordState.PAUSE }
                // 真实 Room 事务控制 STOP 返回时机，不替换生产状态机或依靠 sleep 碰竞态。
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val blocker = async(Dispatchers.IO) {
                    database.withTransaction { entered.complete(Unit); release.await() }
                }
                withTimeout(10_000L) { entered.await() }
                try {
                    scenario.onActivity {
                        val fragment = requireNotNull(screen(it, target))
                        fragment.requireView().findViewById<EditText>(R.id.etNote).setText("停止等待期间离页")
                        assertTrue(fragment.requireView().findViewById<View>(R.id.tvSave).performClick())
                        assertTrue(timerState(fragment).busy)
                        mainVm(it).navigateTo(NavTarget.Dashboard)
                    }
                    await(scenario) { screen(it, target) == null }
                } finally {
                    release.complete(Unit)
                    blocker.await()
                }
                withTimeout(10_000L) {
                    while (repository.timers.getBySession(kind, babyId, paused.ref.sessionId)?.status != TimerStatus.STOPPED)
                        kotlinx.coroutines.delay(20L)
                }
                navigate(scenario, target)
                await(scenario) {
                    val fragment = screen(it, target) ?: return@await false
                    val state = timerState(fragment)
                    !state.busy && state.snapshot?.status == TimerStatus.STOPPED && panel(it, target)?.timerView?.isEnabled == false
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals(target, mainVm(it).navTarget.value)
                    assertEquals("持久原备注", requireNotNull(screen(it, target)).requireView().findViewById<EditText>(R.id.etNote).text.toString())
                }
                val stopped = requireNotNull(repository.timers.getBySession(kind, babyId, paused.ref.sessionId))
                assertEquals(TimerStatus.STOPPED, stopped.status)
                assertEquals(paused.endAt, stopped.endAt)
                scenario.onActivity { mainVm(it).navigateTo(NavTarget.Dashboard) }
                await(scenario) { screen(it, target) == null }
            }
        }
    }

    private suspend fun seedPaused(repository: BabyRepository, babyId: Int, kind: TimerRecordKind): TimerRecordSnapshot {
        val start = System.currentTimeMillis() - 120_000L
        val clock = TimerClockSnapshot(start, 1_000L, processId = "合成页面时钟")
        val running = when (kind) {
            TimerRecordKind.FEEDING -> repository.timers.startFeeding(FeedingRecord(babyId = babyId,
                feedingType = FeedingType.FORMULA.type, feedingStart = start, feedingAmount = 120, note = "持久原备注"), clock)
            TimerRecordKind.SLEEP -> repository.timers.startSleep(SleepRecord(babyId = babyId, sleepStart = start, note = "持久原备注"), clock)
            else -> error("本用例仅验证喂养和睡眠")
        }
        return repository.timers.pause(running.ref, clock.copy(wallTimeMs = start + 60_000L, elapsedRealtimeMs = 61_000L))
    }

    private suspend fun seedCompletedSegments(repository: BabyRepository, babyId: Int, kind: TimerRecordKind): TimerRecordSnapshot {
        val start = System.currentTimeMillis() - 600_000L
        val clock = TimerClockSnapshot(start, 1_000L, processId = "合成分段编辑")
        fun at(offset: Long) = clock.copy(wallTimeMs = start + offset, elapsedRealtimeMs = 1_000L + offset)
        val running = when (kind) {
            TimerRecordKind.FEEDING -> repository.timers.startFeeding(FeedingRecord(babyId = babyId,
                feedingType = FeedingType.BREAST.type, feedingStart = start), clock, TimerPart.LEFT)
            TimerRecordKind.SLEEP -> repository.timers.startSleep(SleepRecord(babyId = babyId, sleepStart = start), clock)
            TimerRecordKind.EVENT -> repository.timers.startEvent(EventRecord(babyId = babyId,
                type = EventType.ACTIVITY_OUTDOOR, time = start), clock)
        }
        val paused = repository.timers.pause(running.ref, at(60_000L))
        val resumed = repository.timers.resume(paused.ref, at(180_000L), if (kind == TimerRecordKind.FEEDING) TimerPart.RIGHT else null)
        val stopped = repository.timers.stop(resumed.ref, at(240_000L))
        return repository.timers.finalize(stopped.ref)
    }

    private fun withScreen(block: (ActivityScenario<MainActivity>, BabyDatabase, BabyRepository, Int) -> Unit) {
        assumeTrue("真实页面导航仅在有合成宝宝的 AVD 运行", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(app, BabyDatabase::class.java).build()
        val repository = BabyRepository(database)
        val injection = Injection(repository)
        app.registerActivityLifecycleCallbacks(injection)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val current = ActivityScenario.launch(MainActivity::class.java)
            scenario = current
            await(current) { mainVm(it).babyDataLoaded.value }
            var babyId: Int? = null
            current.onActivity { babyId = mainVm(it).getCurrentBabyInfo()?.babyId }
            assumeTrue("AVD 需已有用于导航的合成宝宝", babyId != null)
            val id = requireNotNull(babyId)
            runBlocking { repository.insertBabyInfo(BabyInfo(babyId = id, name = "隔离计时表单")) }
            block(current, database, repository, id)
        } finally {
            scenario?.onActivity { mainVm(it).navigateTo(NavTarget.Dashboard) }
            scenario?.close()
            app.unregisterActivityLifecycleCallbacks(injection)
            database.close()
        }
    }

    private class Injection(private val repository: BabyRepository) : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity !is MainActivity) return
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentPreCreated(fm: FragmentManager, fragment: Fragment, state: Bundle?) {
                    val type = when (fragment) {
                        is FeedingRecordFragment -> FeedingRecordViewModel::class.java
                        is SleepRecordFragment -> SleepRecordViewModel::class.java
                        is EventRecordFragment -> EventRecordViewModel::class.java
                        else -> return
                    }
                    ViewModelProvider(fragment, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                            FeedingRecordViewModel::class.java -> FeedingRecordViewModel(repository)
                            SleepRecordViewModel::class.java -> SleepRecordViewModel(repository)
                            else -> EventRecordViewModel(repository)
                        } as T
                    })[type]
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
    private fun target(kind: TimerRecordKind): NavTarget = if (kind == TimerRecordKind.FEEDING)
        NavTarget.FeedingRecord(returnTarget = NavTarget.Dashboard) else NavTarget.SleepRecord(returnTarget = NavTarget.Dashboard)
    private fun screen(activity: MainActivity, target: NavTarget) = activity.supportFragmentManager.fragments
        .firstOrNull { target.fragmentClass.isInstance(it) && !it.isHidden }
    private fun panel(activity: MainActivity, target: NavTarget) = screen(activity, target)?.view?.findViewById<RecordTimerPanelView>(R.id.timerPanel)
    private fun timerState(fragment: Fragment): PersistentTimerEditor.State = when (fragment) {
        is FeedingRecordFragment -> ViewModelProvider(fragment)[FeedingRecordViewModel::class.java].timer.state.value
        is SleepRecordFragment -> ViewModelProvider(fragment)[SleepRecordViewModel::class.java].timer.state.value
        else -> error("非计时页面")
    }
    private fun navigate(scenario: ActivityScenario<MainActivity>, target: NavTarget) {
        scenario.onActivity { mainVm(it).navigateTo(target) }
        await(scenario) { screen(it, target)?.view?.isShown == true }
    }
    private fun await(scenario: ActivityScenario<MainActivity>, predicate: (MainActivity) -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < end)
        throw AssertionError("持久计时表单未达到预期状态")
    }
}
