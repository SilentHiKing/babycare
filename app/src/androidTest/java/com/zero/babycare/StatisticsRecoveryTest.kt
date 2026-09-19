package com.zero.babycare

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.statistics.StatisticsFragment
import com.zero.babycare.statistics.StatisticsViewModel
import com.zero.babycare.statistics.adapter.StatisticsCalendarAdapter
import com.zero.babycare.statistics.adapter.TimelineAdapter
import com.zero.babycare.statistics.model.CalendarBrowseState
import com.zero.babycare.statistics.model.StatisticsUiState
import com.zero.babycare.statistics.widget.BabyCalendarView
import com.zero.babydata.entity.BabyInfo
import com.zero.common.theme.ThemeManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 控制只读内存快照与真实 View 重建，不创建宝宝、不保存记录，也不依赖设备已有数据。 */
@RunWith(AndroidJUnit4::class)
class StatisticsRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val target = NavTarget.Statistics(returnTarget = NavTarget.Dashboard)

    @Test
    fun recreatedStatisticsWaitsForDelayedFirstBabySnapshot() = withScenario { scenario, fallbackBaby ->
        onActivity(scenario) { activity ->
            val main = mainVm(activity)
            // 精确重现冷恢复的先后顺序：导航已恢复，但 Room 的第一个宝宝快照尚未到达。
            stateFlow<Boolean>(main, "_babyDataLoaded").value = false
            stateFlow<BabyInfo?>(main, "_currentBaby").value = null
            main.navigateTo(target)
        }
        await(scenario) { isStatisticsVisible(it) }
        recreate(scenario)
        await(scenario) { isStatisticsVisible(it) }
        onActivity(scenario) { activity ->
            assertEquals("未加载不能被解释为没有宝宝", target, mainVm(activity).navTarget.value)
            val root = stats(activity).requireView()
            assertFalse(root.findViewById<View>(R.id.rvStatistics).isShown)
            assertTrue(root.findViewById<View>(com.zero.components.R.id.progressState).isShown)
            stateFlow<BabyInfo?>(mainVm(activity), "_currentBaby").value = fallbackBaby
            stateFlow<Boolean>(mainVm(activity), "_babyDataLoaded").value = true
        }
        await(scenario) { isStatisticsVisible(it) && !statsVm(it).uiState.value.isLoading &&
            statsVm(it).uiState.value.babyId == fallbackBaby.babyId }
        onActivity(scenario) { activity ->
            assertEquals(target, mainVm(activity).navTarget.value)
            assertTrue(stats(activity).requireView().findViewById<View>(R.id.rvStatistics).isShown)
        }
    }

    @Test
    fun calendarRebindingKeepsBrowsedMonthAndWeekSeparateFromSelectedDate() {
        onMain {
            val context = ContextThemeWrapper(instrumentation.targetContext, com.zero.common.R.style.AppTheme_Boy)
            val adapter = StatisticsCalendarAdapter({}, {}, {})
            val selected = LocalDate.of(2025, 1, 15)
            adapter.syncSelectedDate(selected)
            adapter.syncBrowseState(CalendarBrowseState.forDate(selected))
            var holder = adapter.createViewHolder(FrameLayout(context), 0)
            adapter.bindViewHolder(holder, 0)
            var calendar = holder.binding.calendarView
            calendar.setViewMode(BabyCalendarView.ViewMode.MONTH, animate = false)
            calendar.navigateNext()
            val browsedMonth = calendar.getBrowseState()
            adapter.setDatesWithRecords(setOf(browsedMonth.anchorDate.plusDays(2)))
            adapter.bindViewHolder(holder, 0)
            assertEquals(browsedMonth, calendar.getBrowseState())
            assertEquals(selected, calendar.getSelectedDate())

            adapter.onViewRecycled(holder)
            holder = adapter.createViewHolder(FrameLayout(context), 0)
            adapter.bindViewHolder(holder, 0)
            calendar = holder.binding.calendarView
            assertEquals("回收后的新 holder 必须保留浏览月份和月模式", browsedMonth, calendar.getBrowseState())
            calendar.setViewMode(BabyCalendarView.ViewMode.WEEK, animate = false)
            calendar.navigatePrevious()
            val browsedWeek = calendar.getBrowseState()
            adapter.bindViewHolder(holder, 0)
            assertEquals(browsedWeek, calendar.getBrowseState())
            assertEquals(selected, calendar.getSelectedDate())
        }
    }

    @Test
    fun delayedCalendarMarkersDoNotRebindTimelineOrResetBrowsedMonth() = withScenario { scenario, fallbackBaby ->
        onActivity(scenario) { activity ->
            stateFlow<BabyInfo?>(mainVm(activity), "_currentBaby").value = fallbackBaby
            mainVm(activity).navigateTo(target)
        }
        await(scenario) { isStatisticsVisible(it) && !statsVm(it).uiState.value.isLoading &&
            statsVm(it).uiState.value.babyId == fallbackBaby.babyId }
        await(scenario) { stats(it).view?.findViewById<BabyCalendarView>(R.id.calendarView) != null }
        var changes = 0
        lateinit var expectedBrowse: CalendarBrowseState
        lateinit var selected: LocalDate
        var timeline: TimelineAdapter? = null
        val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { changes++ }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { changes++ }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { changes++ }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { changes++ }
        }
        try {
            onActivity(scenario) { activity ->
                val root = stats(activity).requireView()
                val list = root.findViewById<RecyclerView>(R.id.rvStatistics)
                timeline = (list.adapter as ConcatAdapter).adapters.filterIsInstance<TimelineAdapter>().single().also {
                    it.registerAdapterDataObserver(observer)
                }
                val calendar = root.findViewById<BabyCalendarView>(R.id.calendarView)
                selected = calendar.getSelectedDate()
                calendar.setViewMode(BabyCalendarView.ViewMode.MONTH, animate = false)
                calendar.navigateNext()
                expectedBrowse = calendar.getBrowseState()
            }
            onMain { }
            // 独立一次主线程投递模拟月标记晚于页面内容返回，不改动任何数据库数据。
            onActivity(scenario) { activity ->
                val flow = stateFlow<StatisticsUiState>(statsVm(activity), "_uiState")
                flow.value = flow.value.copy(datesWithRecords = setOf(expectedBrowse.anchorDate.plusDays(2)))
            }
            onMain { }
            onActivity(scenario) { activity ->
                val calendar = stats(activity).requireView().findViewById<BabyCalendarView>(R.id.calendarView)
                assertEquals(expectedBrowse, calendar.getBrowseState())
                assertEquals(selected, calendar.getSelectedDate())
                assertEquals("只更新日期标记不能通知时间轴整表重绑", 0, changes)
            }
        } finally {
            onActivity(scenario) { timeline?.unregisterAdapterDataObserver(observer) }
        }
    }

    @Test
    fun savedStatisticsContextRestoresSelectedDateAndIndependentBrowsePeriod() {
        onMain {
            val app = instrumentation.targetContext.applicationContext as Application
            val store = ViewModelStore()
            try {
                val handle = SavedStateHandle()
                val original = StatisticsViewModel(app, handle).also { store.put("original", it) }
                val selected = LocalDate.of(2025, 1, 15)
                val browse = CalendarBrowseState(true, LocalDate.of(2024, 12, 1))
                original.selectDate(selected)
                original.onCalendarBrowsed(browse)
                val restored = StatisticsViewModel(app, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
                    .also { store.put("restored", it) }
                assertEquals(selected, restored.uiState.value.selectedDate)
                assertEquals(browse, restored.uiState.value.calendarBrowse)
            } finally {
                // 独立 VM 与 ActivityScenario 一样显式结束生命周期，未来新增异步加载也不会泄漏。
                store.clear()
            }
        }
    }

    private fun withScenario(block: (ActivityScenario<MainActivity>, BabyInfo) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var originalBaby: BabyInfo? = null
        lateinit var fallbackBaby: BabyInfo
        try {
            await(scenario) { mainVm(it).babyDataLoaded.value }
            onActivity(scenario) { activity ->
                originalBaby = mainVm(activity).getCurrentBabyInfo()
                fallbackBaby = originalBaby ?: BabyInfo(
                    babyId = Int.MAX_VALUE,
                    name = "StatisticsTest",
                    gender = if (ThemeManager.getSavedTheme() == ThemeManager.BabyTheme.GIRL) "girl" else "boy"
                )
            }
            block(scenario, fallbackBaby)
        } finally {
            lateinit var closingActivity: MainActivity
            onActivity(scenario) { activity ->
                mainVm(activity).navigateTo(NavTarget.Dashboard)
                stateFlow<BabyInfo?>(mainVm(activity), "_currentBaby").value = originalBaby
                stateFlow<Boolean>(mainVm(activity), "_babyDataLoaded").value = true
                closingActivity = activity
                activity.finish()
            }
            // Scenario.close 内部仍使用平台 Idler；先有界结束窗口，停止故意保留的加载动画。
            val closeDeadline = SystemClock.uptimeMillis() + 10_000L
            while (onMain { closingActivity.lifecycle.currentState != Lifecycle.State.DESTROYED }) {
                if (SystemClock.uptimeMillis() >= closeDeadline) {
                    throw AssertionError("统计测试 Activity 未在限定时间内销毁")
                }
                SystemClock.sleep(50L)
            }
            scenario.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stateFlow(owner: Any, name: String): MutableStateFlow<T> =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as MutableStateFlow<T>

    private fun mainVm(activity: MainActivity) = ViewModelProvider(activity)[MainViewModel::class.java]
    private fun stats(activity: MainActivity) = activity.supportFragmentManager.fragments.filterIsInstance<StatisticsFragment>().single()
    private fun statsVm(activity: MainActivity) = ViewModelProvider(stats(activity))[StatisticsViewModel::class.java]
    private fun isStatisticsVisible(activity: MainActivity) = !stats(activity).isHidden && stats(activity).view?.isShown == true

    /** 故意保留加载动画时不能等全局队列空闲；主线程直接使用 Scenario 的当前实例。 */
    private fun onActivity(scenario: ActivityScenario<MainActivity>, action: (MainActivity) -> Unit) {
        onMain { scenario.onActivity(action) }
    }

    /** 触发真实系统重建，并以新实例恢复至 RESUMED 作为完成条件，不依赖 Idler。 */
    private fun recreate(scenario: ActivityScenario<MainActivity>) {
        lateinit var original: MainActivity
        onActivity(scenario) { activity ->
            original = activity
            activity.recreate()
        }
        await(scenario) { it !== original && it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
    }

    /** 每次 UI 读取都有独立上限；超时移除待执行任务，避免失败后再修改页面。 */
    private fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask(Callable { action() })
        mainHandler.post(task)
        return try {
            task.get(2, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        } catch (error: TimeoutException) {
            task.cancel(false)
            throw AssertionError("统计测试主线程操作未在限定时间内完成", error)
        } finally {
            mainHandler.removeCallbacks(task)
        }
    }

    private fun await(scenario: ActivityScenario<MainActivity>, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            var ready = false
            onMain {
                // 重建窗口内 Scenario 暂无当前实例；等真实 Activity 恢复再读取页面状态。
                if (ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .any { it is MainActivity }) {
                    scenario.onActivity { activity ->
                        ready = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && predicate(activity)
                    }
                }
            }
            if (ready) return
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("统计页面未在限定时间内完成状态恢复")
    }
}
