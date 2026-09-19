package com.zero.babycare

import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
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
import com.zero.babycare.home.record.SleepRecordFragment
import com.zero.babycare.navigation.NavTarget
import com.zero.components.widget.RecordTimerPanelView
import com.zero.components.widget.TimeEditText
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

/** 未完成时间属于真实草稿；仅操作内存表单，不保存或取消用户的进行中记录。 */
@RunWith(AndroidJUnit4::class)
class TimeInputStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun partialReplacementInvalidatesOldTimeAndProgrammaticRestoreDoesNotEmitEdits() {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, com.zero.common.R.style.AppTheme_Boy)
            val input = TimeEditText(context)
            var rawChanges = 0
            var completed = 0
            input.setOnRawInputChangedListener { rawChanges++ }
            input.setOnTimeEnteredListener { _, _ -> completed++ }
            val original = System.currentTimeMillis() - 3_600_000L
            input.setTimestamp(original)
            assertEquals(0, rawChanges)
            input.text!!.replace(0, input.length(), "12")
            assertEquals("可见文本未完成时不能沿用旧时间", 0L, input.getTimestamp())
            assertFalse(input.hasValidTime())
            assertEquals(1, rawChanges)
            assertEquals(0, completed)
            input.restoreInputState(0L, "12")
            assertEquals("12", input.text.toString())
            assertEquals(1, rawChanges)
            input.restoreInputState(original, "")
            assertEquals(original, input.getTimestamp())
            assertEquals(1, rawChanges)

            // 合法四位输入需在本次编辑中完成，不靠可能迟到的主线程消息提交。
            input.text!!.replace(0, input.length(), "1234")
            assertEquals(1, completed)
            val selected = Calendar.getInstance().apply { timeInMillis = input.getTimestamp() }
            assertEquals(12, selected.get(Calendar.HOUR_OF_DAY))
            assertEquals(34, selected.get(Calendar.MINUTE))
            assertEquals(0, selected.get(Calendar.SECOND))
            input.text!!.delete(0, input.length())
            assertEquals(0L, input.getTimestamp())
            assertEquals(3, rawChanges)
            input.clearOnRawInputChangedListener()
            input.clearOnTimeEnteredListener()
        }
    }

    @Test
    fun activityRecreationKeepsPartialTimeAndBackRequiresConfirmation() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            await(scenario) { ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value }
            scenario.onActivity {
                ViewModelProvider(it)[MainViewModel::class.java]
                    .navigateTo(NavTarget.SleepRecord(returnTarget = NavTarget.Dashboard))
            }
            await(scenario) { sleepPanel(it)?.isShown == true }
            scenario.onActivity { activity ->
                val start = requireNotNull(sleepPanel(activity)).startInput
                start.setTimestamp(System.currentTimeMillis() - 3_600_000L)
                start.text!!.replace(0, start.length(), "12")
            }
            scenario.recreate()
            await(scenario) { sleepPanel(it)?.isShown == true }
            scenario.onActivity { activity ->
                val start = requireNotNull(sleepPanel(activity)).startInput
                assertEquals("重建必须保留原始未完成时间", "12", start.text.toString())
                assertEquals("重建不得把未完成时间还原成旧时间戳", 0L, start.getTimestamp())
                activity.onBackPressedDispatcher.onBackPressed()
            }
            onView(withId(com.zero.components.R.id.tv_content))
                .check(matches(withText(com.zero.common.R.string.unsaved_record_tip)))
            onView(withId(com.zero.components.R.id.tv_cancel)).perform(object : ViewAction {
                override fun getDescription() = "取消返回并保留未完成时间"
                override fun getConstraints(): Matcher<View> = isDisplayed()
                override fun perform(uiController: UiController, view: View) {
                    assertTrue(view.performClick())
                    uiController.loopMainThreadUntilIdle()
                }
            })
            scenario.onActivity { activity ->
                assertTrue(ViewModelProvider(activity)[MainViewModel::class.java].navTarget.value is NavTarget.SleepRecord)
                assertEquals("12", requireNotNull(sleepPanel(activity)).startInput.text.toString())
            }
        } finally {
            try { scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].navigateTo(NavTarget.Dashboard) } }
            finally { scenario.close() }
        }
    }

    private fun sleepPanel(activity: MainActivity): RecordTimerPanelView? = activity.supportFragmentManager.fragments
        .filterIsInstance<SleepRecordFragment>().firstOrNull { !it.isHidden && it.view?.isShown == true }
        ?.view?.findViewById(R.id.timerPanel)

    private fun await(scenario: ActivityScenario<MainActivity>, ready: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var complete = false
            scenario.onActivity { complete = ready(it) }
            if (complete) return
            SystemClock.sleep(30L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("睡眠草稿页面未就绪")
    }
}
