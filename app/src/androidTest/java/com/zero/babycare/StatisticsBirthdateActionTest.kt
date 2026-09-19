package com.zero.babycare

import android.content.res.Configuration
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.statistics.StatisticsFragment
import com.zero.babycare.statistics.StatisticsViewModel
import com.zero.babycare.statistics.adapter.StatisticsBabyAgeAdapter
import com.zero.babycare.statistics.model.DayRecordSectionUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Locale

/** 只绑定合成展示状态和打开/返回编辑页，不保存或修改设备上的宝宝资料。 */
@RunWith(AndroidJUnit4::class)
class StatisticsBirthdateActionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun missingBirthdateShowsReadableButtonAndKnownBirthdateRemovesAction() {
        instrumentation.runOnMainSync {
            val app = instrumentation.targetContext
            val config = Configuration(app.resources.configuration).apply {
                fontScale = 2f
                setLocale(Locale.US)
            }
            val context = ContextThemeWrapper(app.createConfigurationContext(config), com.zero.common.R.style.AppTheme_Boy)
            var clicks = 0
            val adapter = StatisticsBabyAgeAdapter { clicks++ }
            val holder = adapter.createViewHolder(FrameLayout(context), 0)
            val missing = DayRecordSectionUiModel.empty(LocalDate.now())
            adapter.bindDayRecord(missing)
            adapter.bindViewHolder(holder, 0)
            val width = (288 * context.resources.displayMetrics.density).toInt()
            holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            holder.itemView.layout(0, 0, width, holder.itemView.measuredHeight)
            val button = holder.itemView.findViewById<Button>(R.id.btnAddBirthdate)
            assertEquals(View.VISIBLE, button.visibility)
            assertTrue(button.height >= 48 * context.resources.displayMetrics.density)
            assertEquals(Button::class.java.name, button.accessibilityClassName.toString())
            val textLayout = requireNotNull(button.layout)
            assertEquals(button.text.length, textLayout.getLineEnd(textLayout.lineCount - 1))
            assertTrue(textLayout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
            assertTrue(button.performClick())
            assertEquals(1, clicks)

            adapter.bindDayRecord(missing.copy(babyAgeText = context.getString(com.zero.common.R.string.days_born, 42), showBirthHint = false))
            adapter.bindViewHolder(holder, 0)
            assertEquals(View.GONE, button.visibility)
            assertFalse(button.isEnabled)
            assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.llBabyAge).visibility)
        }
    }

    @Test
    fun birthdateActionEditsCurrentBabyAndReturnsToSelectedStatisticsDate() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val statisticsTarget = NavTarget.Statistics(returnTarget = NavTarget.Dashboard)
        val selectedDate = LocalDate.now().minusDays(3)
        try {
            await(scenario) { mainVm(it).babyDataLoaded.value }
            scenario.onActivity { activity ->
                assumeTrue(mainVm(activity).getCurrentBabyInfo() != null)
                mainVm(activity).navigateTo(statisticsTarget)
            }
            await(scenario) { visible(it, statisticsTarget) }
            scenario.onActivity { statsVm(it).selectDate(selectedDate) }
            await(scenario) { !statsVm(it).uiState.value.isLoading && statsVm(it).uiState.value.selectedDate == selectedDate }
            scenario.onActivity { activity ->
                val list = statisticsFragment(activity).requireView().findViewById<RecyclerView>(R.id.rvStatistics)
                val adapters = (list.adapter as ConcatAdapter).adapters
                // 展示层注入缺失生日状态，测试不必修改真实宝宝的生日。
                adapters.filterIsInstance<StatisticsBabyAgeAdapter>().single().bindDayRecord(
                    statsVm(activity).uiState.value.dayRecord.copy(babyAgeText = null, showBirthHint = true)
                )
                list.scrollToPosition(adapters.first().itemCount)
            }
            await(scenario) { statisticsFragment(it).view?.findViewById<View>(R.id.btnAddBirthdate)?.isShown == true }
            var expectedBabyId = 0
            scenario.onActivity { activity ->
                expectedBabyId = requireNotNull(mainVm(activity).getCurrentBabyInfo()).babyId
                statisticsFragment(activity).requireView().findViewById<View>(R.id.btnAddBirthdate).performClick()
                assertEquals(NavTarget.BabyInfo.edit(expectedBabyId, statisticsTarget), mainVm(activity).navTarget.value)
            }
            await(scenario) { visible(it, NavTarget.BabyInfo.edit(expectedBabyId, statisticsTarget)) }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await(scenario) { visible(it, statisticsTarget) }
            scenario.onActivity { activity ->
                assertEquals(statisticsTarget, mainVm(activity).navTarget.value)
                assertEquals(selectedDate, statsVm(activity).uiState.value.selectedDate)
            }
        } finally {
            scenario.onActivity { mainVm(it).navigateTo(NavTarget.Dashboard) }
            scenario.close()
        }
    }

    private fun statisticsFragment(activity: MainActivity): StatisticsFragment =
        activity.supportFragmentManager.fragments.filterIsInstance<StatisticsFragment>().single()

    private fun statsVm(activity: MainActivity) = ViewModelProvider(statisticsFragment(activity))[StatisticsViewModel::class.java]
    private fun mainVm(activity: MainActivity) = ViewModelProvider(activity)[MainViewModel::class.java]

    private fun visible(activity: MainActivity, target: NavTarget) = activity.supportFragmentManager.fragments.any {
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
        throw AssertionError("生日入口未在限定时间内完成导航或渲染")
    }
}
