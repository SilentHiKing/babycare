package com.zero.babycare

import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.home.record.RecordDraftState
import com.zero.babycare.home.record.RecordMutationController
import com.zero.babycare.home.record.SleepRecordViewModel
import com.zero.babycare.home.record.event.EventCategory
import com.zero.babycare.home.record.event.EventRecordViewModel
import com.zero.babycare.home.record.event.EventSubtype
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.reminder.ReminderNotifier
import com.zero.babycare.reminder.ReminderType
import com.zero.babydata.entity.EventType
import com.zero.components.widget.RecordTimerPanelView
import com.zero.components.widget.RecordView.RecordState
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.junit.runner.RunWith

/** 只操作未提交表单，验证真实 Fragment 重建；不保存、不改写用户记录。 */
@RunWith(AndroidJUnit4::class)
class RecordDraftRecreationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun originalReminderIntentDoesNotReplaceRestoredEventDraft() = withScenario { scenario ->
        val target = NavTarget.EventRecord(returnTarget = NavTarget.Dashboard)
        navigate(scenario, target)
        selectEvent(scenario, target, EventCategory.GROWTH, EventType.GROWTH_WEIGHT)
        await(scenario) { currentFragment(it, target).view?.findViewById<View>(R.id.etValue) != null }
        scenario.onActivity { activity ->
            currentFragment(activity, target).requireView().findViewById<EditText>(R.id.etValue).setText("8.75")
            val babyId = requireNotNull(mainVm(activity).getCurrentBabyInfo()).babyId
            // 模拟系统回收后再次传入原始启动 Intent；进程内 removeExtra 不会改写系统持有的副本。
            activity.intent = Intent(activity.intent)
                .putExtra(ReminderNotifier.EXTRA_BABY_ID, babyId)
                .putExtra(ReminderNotifier.EXTRA_REMINDER_TYPE, ReminderType.SLEEP.name)
        }
        scenario.recreate()
        await(scenario) { visible(it, target) && currentFragment(it, target).view?.findViewById<View>(R.id.etValue) != null }
        scenario.onActivity { activity ->
            assertEquals(target, mainVm(activity).navTarget.value)
            assertEquals("8.75", currentFragment(activity, target).requireView().findViewById<EditText>(R.id.etValue).text.toString())
        }
        assertUnsavedConfirmation(scenario, target)
    }

    @Test
    fun pendingWriteSurvivesRecreationAndDeliversOneResult() = withScenario { scenario ->
        val target = NavTarget.SleepRecord(returnTarget = NavTarget.Dashboard)
        navigate(scenario, target)
        val release = CompletableDeferred<Unit>()
        lateinit var originalVm: SleepRecordViewModel
        lateinit var request: RecordMutationController.Target
        var writes = 0
        scenario.onActivity { activity ->
            val babyId = mainVm(activity).getCurrentBabyInfo()?.babyId
            assumeTrue(babyId != null)
            val fragment = currentFragment(activity, target)
            originalVm = ViewModelProvider(fragment)[SleepRecordViewModel::class.java]
            val saved = Bundle()
            fragment.onSaveInstanceState(saved)
            val draftId = requireNotNull(saved.getBundle(RecordDraftState.KEY)?.getString("draft_id"))
            request = RecordMutationController.Target(draftId, requireNotNull(babyId), null, target, NavTarget.Dashboard)
            // 注入可控的异步写入，不触及真实数据，验证页面与VM之间的重建协议。
            assertTrue(originalVm.mutation.submit(request, RecordMutationController.Kind.SAVE, 1) {
                writes++
                release.await()
            })
        }
        scenario.recreate()
        await(scenario) { visible(it, target) }
        scenario.onActivity { activity ->
            val fragment = currentFragment(activity, target)
            assertTrue(originalVm === ViewModelProvider(fragment)[SleepRecordViewModel::class.java])
            assertFalse(fragment.requireView().findViewById<View>(R.id.tvSave).isEnabled)
            assertFalse(originalVm.mutation.submit(request, RecordMutationController.Kind.SAVE, 1) { writes++ })
            release.complete(Unit)
        }
        await(scenario) { visible(it, NavTarget.Dashboard) }
        scenario.onActivity {
            assertEquals(1, writes)
            assertEquals(RecordMutationController.State.Idle, originalVm.mutation.state.value)
        }
    }

    @Test
    fun feedingRecreationKeepsAmountsTimesAndExitConfirmation() = withScenario { scenario ->
        scenario.onActivity { activity ->
            mainVm(activity).getCurrentBabyInfo()?.babyId?.let {
                // 不修改用户已有的喂养计时会话。
                assumeFalse(kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { OngoingRecordManager.isFeeding(it) })
            }
        }
        val target = NavTarget.FeedingRecord(returnTarget = NavTarget.Dashboard)
        navigate(scenario, target)
        val start = System.currentTimeMillis() - 1_200_000L
        val end = start + 600_000L
        scenario.onActivity { activity ->
            val root = currentFragment(activity, target).requireView()
            root.findViewById<RadioButton>(R.id.rbFormula).isChecked = true
            root.findViewById<EditText>(R.id.etFeedingAmount).setText("125")
            root.findViewById<EditText>(R.id.etNote).setText("DraftFeeding")
            root.findViewById<RecordTimerPanelView>(R.id.timerPanel).apply {
                startInput.setTimestamp(start, triggerCallback = true)
                endInput.setTimestamp(end, triggerCallback = true)
            }
        }
        scenario.recreate()
        await(scenario) { visible(it, target) }
        scenario.onActivity { activity ->
            val root = currentFragment(activity, target).requireView()
            assertTrue(root.findViewById<RadioButton>(R.id.rbFormula).isChecked)
            assertEquals("125", root.findViewById<EditText>(R.id.etFeedingAmount).text.toString())
            assertEquals("DraftFeeding", root.findViewById<EditText>(R.id.etNote).text.toString())
            root.findViewById<RecordTimerPanelView>(R.id.timerPanel).apply {
                assertEquals(start, startInput.getTimestamp())
                assertEquals(end, endInput.getTimestamp())
                assertEquals(600_000L, timerView.getDuration())
            }
        }
        assertUnsavedConfirmation(scenario, target)
    }

    @Test
    fun eventRecreationKeepsDynamicGrowthInputAndType() = withScenario { scenario ->
        val target = NavTarget.EventRecord(returnTarget = NavTarget.Dashboard)
        navigate(scenario, target)
        val eventTime = System.currentTimeMillis() - 60_000L
        selectEvent(scenario, target, EventCategory.GROWTH, EventType.GROWTH_WEIGHT)
        await(scenario) { currentFragment(it, target).view?.findViewById<View>(R.id.etValue) != null }
        scenario.onActivity { activity ->
            val fragment = currentFragment(activity, target)
            ViewModelProvider(fragment)[EventRecordViewModel::class.java].setEventTime(eventTime)
            fragment.requireView().findViewById<EditText>(R.id.etValue).setText("7.25")
            fragment.requireView().findViewById<EditText>(R.id.etNote).setText("DraftGrowth")
        }
        scenario.recreate()
        await(scenario) { visible(it, target) && currentFragment(it, target).view?.findViewById<View>(R.id.etValue) != null }
        scenario.onActivity { activity ->
            val fragment = currentFragment(activity, target)
            val vm = ViewModelProvider(fragment)[EventRecordViewModel::class.java]
            assertEquals(EventType.GROWTH_WEIGHT, vm.selectedSubtype.value?.type)
            assertEquals(eventTime, vm.eventTime.value)
            assertEquals("7.25", fragment.requireView().findViewById<EditText>(R.id.etValue).text.toString())
            assertEquals("DraftGrowth", fragment.requireView().findViewById<EditText>(R.id.etNote).text.toString())
        }
        assertUnsavedConfirmation(scenario, target)
    }

    @Test
    fun pausedActivityRecreationKeepsFrozenDuration() = withScenario { scenario ->
        val target = NavTarget.EventRecord(returnTarget = NavTarget.Dashboard)
        navigate(scenario, target)
        selectEvent(scenario, target, EventCategory.ACTIVITY, EventType.ACTIVITY_OUTDOOR)
        await(scenario) { currentFragment(it, target).view?.findViewById<View>(R.id.timerPanel) != null }
        val start = System.currentTimeMillis() - 1_200_000L
        val end = start + 600_000L
        scenario.onActivity { activity ->
            currentFragment(activity, target).requireView().findViewById<RecordTimerPanelView>(R.id.timerPanel).apply {
                startInput.setTimestamp(start, triggerCallback = true)
                endInput.setTimestamp(end, triggerCallback = true)
                timerView.restorePaused(180_000L)
            }
        }
        scenario.recreate()
        await(scenario) { visible(it, target) && currentFragment(it, target).view?.findViewById<View>(R.id.timerPanel) != null }
        scenario.onActivity { activity ->
            currentFragment(activity, target).requireView().findViewById<RecordTimerPanelView>(R.id.timerPanel).apply {
                assertEquals(start, startInput.getTimestamp())
                assertEquals(end, endInput.getTimestamp())
                assertEquals(RecordState.PAUSE, timerView.currentShowState)
                assertEquals(180_000L, timerView.getDuration())
            }
        }
    }

    @Test
    fun parcelledDraftCannotRestoreIntoAnotherBabyOrEditingTarget() {
        val draft = Bundle().apply { putInt("baby_id", 41); putInt("edit_id", 15) }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(draft)
            parcel.setDataPosition(0)
            val restored = requireNotNull(parcel.readBundle(javaClass.classLoader))
            assertTrue(RecordDraftState.matches(restored, 41, 15))
            assertFalse(RecordDraftState.matches(restored, 42, 15))
            assertFalse(RecordDraftState.matches(restored, 41, 16))
            assertFalse(RecordDraftState.matches(restored, 41, null))
        } finally {
            parcel.recycle()
        }
    }

    private fun withScenario(block: (ActivityScenario<MainActivity>) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            await(scenario) { mainVm(it).babyDataLoaded.value }
            block(scenario)
        } finally {
            scenario.onActivity { mainVm(it).navigateTo(NavTarget.Dashboard) }
            scenario.close()
        }
    }

    private fun selectEvent(scenario: ActivityScenario<MainActivity>, target: NavTarget, category: EventCategory, type: Int) {
        scenario.onActivity { activity ->
            ViewModelProvider(currentFragment(activity, target))[EventRecordViewModel::class.java].apply {
                selectCategory(category)
                selectSubtype(requireNotNull(EventSubtype.fromType(type)))
            }
        }
    }

    private fun assertUnsavedConfirmation(scenario: ActivityScenario<MainActivity>, target: NavTarget) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        onView(withId(com.zero.components.R.id.tv_content)).check(matches(withText(com.zero.common.R.string.unsaved_record_tip)))
        onView(withId(com.zero.components.R.id.tv_cancel)).perform(object : ViewAction {
            override fun getDescription() = "取消退出以保留未保存表单"
            override fun getConstraints(): Matcher<View> = isDisplayed()
            override fun perform(uiController: UiController, view: View) {
                check(view.performClick())
                uiController.loopMainThreadUntilIdle()
            }
        })
        scenario.onActivity { assertTrue(visible(it, target)) }
    }

    private fun navigate(scenario: ActivityScenario<MainActivity>, target: NavTarget) {
        scenario.onActivity { mainVm(it).navigateTo(target) }
        await(scenario) { visible(it, target) }
    }

    private fun mainVm(activity: MainActivity) = ViewModelProvider(activity)[MainViewModel::class.java]

    private fun currentFragment(activity: MainActivity, target: NavTarget): Fragment =
        activity.supportFragmentManager.fragments.first { target.fragmentClass.isInstance(it) && !it.isHidden }

    private fun visible(activity: MainActivity, target: NavTarget): Boolean =
        activity.supportFragmentManager.fragments.any {
            target.fragmentClass.isInstance(it) && !it.isHidden && it.view?.isShown == true
        }

    private fun await(scenario: ActivityScenario<MainActivity>, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("记录页面未在限定时间内恢复")
    }
}
