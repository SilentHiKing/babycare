package com.zero.babycare

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.statistics.StatisticsFragment
import com.zero.babycare.statistics.StatisticsViewModel
import com.zero.babycare.statistics.model.CalendarBrowseState
import com.zero.babycare.statistics.model.StatisticsUiState
import com.zero.babycare.statistics.model.SummaryMetricType
import com.zero.babycare.statistics.model.TimelineEditTarget
import com.zero.babycare.statistics.widget.BabyCalendarView
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import com.zero.common.theme.ThemeManager
import com.zero.common.util.LanguageManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** 真实同实例后台恢复，系统只改变时区；全部宝宝和事件仅写入独立内存 Room。 */
@RunWith(AndroidJUnit4::class)
class StatisticsClockRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val handler = Handler(Looper.getMainLooper())
    private val selected = LocalDate.of(2020, 1, 2)
    private val browse = CalendarBrowseState(true, LocalDate.of(2020, 1, 1))
    private val target = NavTarget.Statistics(NavTarget.Dashboard)

    @Test
    fun backgroundTimeZoneChangeReloadsSameActivityWithoutMovingSelectedDateOrBrowse() {
        assumeTrue("真实时区权限仅用于 API 29+ 审查 AVD", Build.VERSION.SDK_INT >= 29 &&
            (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")))
        val originalZone = TimeZone.getDefault().id
        val originalSelection = MMKVStore.getInt(MMKVKeys.CURRENT_BABY_ID)
        val originalLegacy = MMKVStore.getString(MMKVKeys.LEGACY_BABY_INFO)
        val database = Room.inMemoryDatabaseBuilder(app, BabyDatabase::class.java).build()
        val repository = BabyRepository(database)
        val baby = BabyInfo(babyId = 2_000_000_121, name = "隔离时区统计",
            gender = if (ThemeManager.getSavedTheme() == ThemeManager.BabyTheme.GIRL) "girl" else "boy")
        val original = EventRecord(eventId = 912, babyId = baby.babyId, type = EventType.OTHER_BURP,
            time = Instant.parse("2020-01-02T01:00:00Z").toEpochMilli(), note = "隔离时区原记录")
        runBlocking {
            repository.insertBabyInfo(baby)
            repository.insertEventRecord(original)
        }
        val injection = StatisticsInjection(repository)
        app.registerActivityLifecycleCallbacks(injection)
        try {
            setSystemTimeZone("Asia/Shanghai")
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("隔离宝宝未加载") { onMain { injection.activity?.let { main(it).babyDataLoaded.value } == true } }
            val activity = onMain { requireNotNull(injection.activity).also { main(it).navigateTo(target) } }
            await("上海日期统计未加载") { onMain {
                stats(activity).view?.isShown == true &&
                    stats(activity).view?.findViewById<BabyCalendarView>(R.id.calendarView) != null && model(activity).uiState.value.let {
                    !it.isLoading && it.dayRecord.timelineItems.singleOrNull()?.timeText == "09:00" &&
                        it.datesWithRecords == setOf(selected)
                }
            } }
            onMain {
                val state = model(activity).uiState.value
                assertEquals(baby.babyId, state.babyId)
                assertEquals(TimelineEditTarget.Event(original.eventId), state.dayRecord.timelineItems.single().editTarget)
                assertOtherCount(state, 1)
                assertContext(activity)
            }

            // HOME 使现有 Activity 真正停止；不调用 recreate、refreshData 或替换 ViewModel 来掩盖缺失入口。
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            await("统计 Activity 未进入后台") { onMain { activity.lifecycle.currentState == Lifecycle.State.CREATED } }
            setSystemTimeZone("America/Los_Angeles")
            onMain {
                assertSame(activity, injection.activity)
                assertEquals(Lifecycle.State.CREATED, activity.lifecycle.currentState)
            }
            // REORDER_TO_FRONT 只取回原任务中的 Activity；严格同实例断言防止新建页面替我们刷新数据。
            app.startActivity(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            await("同一统计 Activity 未恢复前台") { onMain {
                injection.activity === activity && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            } }
            await("时区恢复未重新归日：Jan 2 应为零条，记录点应迁到 Jan 1") { onMain {
                model(activity).uiState.value.let {
                    !it.isLoading && it.dayRecord.timelineItems.isEmpty() &&
                        it.datesWithRecords == setOf(selected.minusDays(1))
                }
            } }
            onMain {
                assertSame(activity, injection.activity)
                assertOtherCount(model(activity).uiState.value, 0)
                assertContext(activity)
                // 使用真实日历控件的选择回调，回看新归属日仍指向同一条原始记录。
                stats(activity).requireView().findViewById<BabyCalendarView>(R.id.calendarView)
                    .setSelectedDate(selected.minusDays(1))
            }
            await("新归属日期未显示同一原记录") { onMain {
                model(activity).uiState.value.let {
                    !it.isLoading && it.dayRecord.timelineItems.singleOrNull()?.let { item ->
                        item.editTarget == TimelineEditTarget.Event(original.eventId) && item.timeText == "17:00"
                    } == true
                }
            } }
            // 进程仍处于洛杉矶时区，显式上海查询也必须在同一时区中完成范围与事件归日。
            runBlocking {
                val zone = ZoneId.of("Asia/Shanghai")
                assertEquals(setOf(selected), repository.getDatesWithRecords(baby.babyId, YearMonth.from(selected), zone))
                assertEquals(1, repository.getDaySummary(baby.babyId, selected, zone).otherEventCount)
                assertEquals(original.eventId, (repository.getTimelineRecords(baby.babyId, selected, zone)
                    .single() as BabyRepository.TimelineRecord.Event).record.eventId)
            }
            val foregroundBrowse = onMain { model(activity).uiState.value.calendarBrowse }
            setSystemTimeZone("Asia/Shanghai")
            await("前台系统时区广播未刷新统计") { onMain {
                model(activity).uiState.value.let {
                    !it.isLoading && it.selectedDate == selected.minusDays(1) &&
                        it.calendarBrowse == foregroundBrowse && it.dayRecord.timelineItems.isEmpty() &&
                        it.datesWithRecords == setOf(selected)
                }
            } }
            assertEquals("时区变化不得改写数据库原始时间", original,
                runBlocking { repository.getEventRecordById(original.eventId) })
        } finally {
            try {
                val closing = onMain { injection.activity?.also { it.finish() } }
                if (closing != null) await("隔离统计 Activity 未销毁") {
                    onMain { closing.lifecycle.currentState == Lifecycle.State.DESTROYED }
                }
            } finally {
                try {
                    setSystemTimeZone(originalZone)
                } finally {
                    app.unregisterActivityLifecycleCallbacks(injection)
                    if (originalSelection > 0) MMKVStore.put(MMKVKeys.CURRENT_BABY_ID, originalSelection)
                        else MMKVStore.remove(MMKVKeys.CURRENT_BABY_ID)
                    MMKVStore.put(MMKVKeys.LEGACY_BABY_INFO, originalLegacy)
                    database.close()
                }
            }
        }
    }

    private fun assertContext(activity: MainActivity) {
        val state = model(activity).uiState.value
        assertEquals(target, main(activity).navTarget.value)
        assertEquals(selected, state.selectedDate)
        assertEquals(browse, state.calendarBrowse)
        val calendar = stats(activity).requireView().findViewById<BabyCalendarView>(R.id.calendarView)
        assertEquals(selected, calendar.getSelectedDate())
        assertEquals(browse, calendar.getBrowseState())
    }

    private fun assertOtherCount(state: StatisticsUiState, count: Int) {
        assertEquals(LanguageManager.localizedContext(app).getString(com.zero.common.R.string.times_count_format, count),
            state.dayRecord.summaryMetrics.single { it.type == SummaryMetricType.OTHER }.primaryText)
    }

    /** 采用真实系统权限触发 TIMEZONE_CHANGED，等进程默认时区更新，不直接篡改 TimeZone 缓存。 */
    private fun setSystemTimeZone(zone: String) {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.SET_TIME_ZONE)
        try {
            (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setTimeZone(zone)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        await("进程没有收到系统时区 $zone") { TimeZone.getDefault().id == zone }
    }

    /** MainVM 与统计 VM 共享隔离数据源，避免页面归属观察真实应用库。 */
    private inner class StatisticsInjection(private val repository: BabyRepository) : Application.ActivityLifecycleCallbacks {
        var activity: MainActivity? = null
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity !is MainActivity) return
            this.activity = activity
            ViewModelProvider(activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(SavedStateHandle(), repository.observeAllBabyInfo()) as T
            })[MainViewModel::class.java]
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentPreCreated(fm: FragmentManager, fragment: Fragment, state: Bundle?) {
                    if (fragment !is StatisticsFragment) return
                    ViewModelProvider(fragment, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = StatisticsViewModel(app,
                            SavedStateHandle(mapOf("statistics_selected_date" to selected.toString(),
                                "statistics_calendar_anchor" to browse.anchorDate.toString(),
                                "statistics_calendar_month" to browse.monthMode)), repository) as T
                    })[StatisticsViewModel::class.java]
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

    private fun main(activity: MainActivity) = ViewModelProvider(activity)[MainViewModel::class.java]
    private fun stats(activity: MainActivity) = activity.supportFragmentManager.fragments.filterIsInstance<StatisticsFragment>().single()
    private fun model(activity: MainActivity) = ViewModelProvider(stats(activity))[StatisticsViewModel::class.java]

    /** 不使用全局 Idler，后台停留和异步加载均以真实状态为准并限制等待时间。 */
    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask(Callable(action))
        handler.post(task)
        return try { task.get(3, TimeUnit.SECONDS) }
        catch (error: ExecutionException) { throw (error.cause ?: error) }
        finally { task.cancel(false); handler.removeCallbacks(task) }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        do {
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue(message, condition())
    }
}
