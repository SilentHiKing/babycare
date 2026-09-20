package com.zero.babycare

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lxj.xpopup.core.BasePopupView
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.statistics.StatisticsFragment
import com.zero.babycare.statistics.StatisticsViewModel
import com.zero.babycare.statistics.adapter.TimelineAdapter
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.ChildDailyRecord
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** 通过真实列表点击、窗口重建和统一弹窗回看旧数据，全部测量仅写入隔离内存库。 */
@RunWith(AndroidJUnit4::class)
class StatisticsLegacyGrowthUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val handler = Handler(Looper.getMainLooper())
    private val date = LocalDate.of(2026, 8, 15)

    @Test fun legacyRowOpensCompleteDetailAcrossRecreationAndNavigationWithoutRewriting() {
        assumeTrue("隔离资料仅在审查 AVD 使用", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val database = Room.inMemoryDatabaseBuilder(app, BabyDatabase::class.java).build()
        val repository = BabyRepository(database)
        val injection = StatisticsInjection(repository)
        app.registerActivityLifecycleCallbacks(injection)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val current = ActivityScenario.launch(MainActivity::class.java)
            scenario = current
            await(current) { main(it).babyDataLoaded.value }
            val baby = onMain { var result: BabyInfo? = null; current.onActivity { result = main(it).getCurrentBabyInfo() }; result }
            assertNotNull("AVD需已创建合成宝宝", baby)
            val original = ChildDailyRecord(recordId = 901, babyId = baby!!.babyId,
                recordDate = date.atTime(10, 15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                weight = 4.321f, height = 54.25f, headCircumference = 33.75f,
                pic = "content://synthetic/photo/reference",
                extra = "合成原始附加信息。".repeat(100) + "完整内容末尾")
            runBlocking {
                repository.insertBabyInfo(baby.copy(name = "隔离成长回看"))
                database.childDailyRecordDao().insertChildDailyRecord(original)
            }
            onMain { current.onActivity { main(it).navigateTo(NavTarget.Statistics(NavTarget.Dashboard)) } }
            await(current) { model(it)?.uiState?.value?.let { state -> !state.isLoading && state.dayRecord.timelineItems.size == 1 } == true }
            var rowPosition = 0
            onMain { current.onActivity { activity ->
                val list = stats(activity)!!.requireView().findViewById<RecyclerView>(R.id.rvStatistics)
                val adapters = (list.adapter as ConcatAdapter).adapters
                rowPosition = adapters.takeWhile { it !is TimelineAdapter }.sumOf { it.itemCount }
                list.scrollToPosition(rowPosition)
            } }
            await(current) { stats(it)?.view?.findViewById<RecyclerView>(R.id.rvStatistics)
                ?.findViewHolderForAdapterPosition(rowPosition)?.itemView?.isShown == true }
            onMain { current.onActivity { activity ->
                val row = stats(activity)!!.requireView().findViewById<RecyclerView>(R.id.rvStatistics)
                    .findViewHolderForAdapterPosition(rowPosition)!!.itemView
                assertEquals(activity.getString(com.zero.common.R.string.legacy_growth_record_title), row.findViewById<TextView>(R.id.tvTitle).text)
                assertTrue("从实际可见时间轴行进入", row.performClick())
            } }
            await(current) { popup(it)?.findViewById<TextView>(com.zero.components.R.id.tv_content)?.text?.contains("完整内容末尾") == true }
            verifyDetail(current, original)
            capture("opened")
            // 实际 Activity 重建只撤下窗口，不能误消费仍在查看的记录。
            lateinit var before: MainActivity
            onMain { current.onActivity { before = it; it.recreate() } }
            await(current) { it !== before && popup(it)?.findViewById<TextView>(com.zero.components.R.id.tv_content)?.text?.contains("完整内容末尾") == true }
            verifyDetail(current, original)
            onMain { current.onActivity { main(it).navigateTo(NavTarget.Dashboard) } }
            await(current) { stats(it)?.isHidden == true && popup(it) == null }
            onMain { current.onActivity { main(it).navigateTo(NavTarget.Statistics(NavTarget.Dashboard)) } }
            await(current) { popup(it)?.findViewById<TextView>(com.zero.components.R.id.tv_content)?.text?.contains("完整内容末尾") == true }
            onMain { current.onActivity { activity ->
                assertTrue(popup(activity)!!.findViewById<View>(com.zero.components.R.id.tv_confirm).performClick())
            } }
            await(current) { model(it)?.uiState?.value?.legacyGrowthDetail == null }
            assertEquals(listOf(original), runBlocking { database.childDailyRecordDao().getAllRecordsByBabyId(baby.babyId) })
        } finally {
            scenario?.let { current ->
                lateinit var closing: MainActivity
                onMain { current.onActivity { closing = it; it.finish() } }
                val deadline = SystemClock.uptimeMillis() + 10_000L
                while (onMain { closing.lifecycle.currentState != Lifecycle.State.DESTROYED }) {
                    check(SystemClock.uptimeMillis() < deadline) { "测试窗口未结束" }
                    SystemClock.sleep(50)
                }
                current.close()
            }
            app.unregisterActivityLifecycleCallbacks(injection)
            database.close()
        }
    }

    private fun verifyDetail(scenario: ActivityScenario<MainActivity>, original: ChildDailyRecord) {
        // 正文在 onCreate 就存在，窗口进入动画尚未完成时不能据此判定布局已可交互。
        await(scenario) { activity ->
            val confirm = popup(activity)?.findViewById<TextView>(com.zero.components.R.id.tv_confirm)
            val rect = android.graphics.Rect()
            confirm != null && confirm.height > 0 && confirm.getGlobalVisibleRect(rect) &&
                rect.height() == confirm.height && rect.width() == confirm.width
        }
        onMain { scenario.onActivity { activity ->
            val dialog = requireNotNull(popup(activity))
            val content = dialog.findViewById<TextView>(com.zero.components.R.id.tv_content).text.toString()
            assertEquals(android.view.Gravity.START,
                dialog.findViewById<TextView>(com.zero.components.R.id.tv_content).gravity and android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
            listOf("4.321", "54.25", "33.75", original.extra, original.pic).forEach { assertTrue(content.contains(it)) }
            assertTrue(content.contains(activity.getString(com.zero.common.R.string.legacy_growth_statistics_hint)))
            val confirm = dialog.findViewById<TextView>(com.zero.components.R.id.tv_confirm)
            val rect = android.graphics.Rect()
            assertTrue("长内容下确认操作仍完整可见", confirm.getGlobalVisibleRect(rect) && rect.height() == confirm.height)
            assertTrue(confirm.height >= (48 * activity.resources.displayMetrics.density).toInt())
            assertTrue("不能省略确认文字", confirm.layout.getEllipsisCount(confirm.lineCount - 1) == 0)
        } }
    }

    private fun capture(label: String) {
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        try { File(app.filesDir, "audit-screens").apply { mkdirs() }.resolve("phase11-legacy-$label.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } } finally { image.recycle() }
    }

    private inner class StatisticsInjection(private val repository: BabyRepository) : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity !is MainActivity) return
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentPreCreated(fm: FragmentManager, fragment: Fragment, state: Bundle?) {
                    if (fragment !is StatisticsFragment) return
                    ViewModelProvider(fragment, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = StatisticsViewModel(app,
                            SavedStateHandle(mapOf("statistics_selected_date" to date.toString())), repository) as T
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
    private fun stats(activity: MainActivity) = activity.supportFragmentManager.fragments.filterIsInstance<StatisticsFragment>().singleOrNull()
    private fun model(activity: MainActivity) = stats(activity)?.let { ViewModelProvider(it)[StatisticsViewModel::class.java] }
    private fun popup(activity: MainActivity): BasePopupView? = stats(activity)?.let { fragment ->
        StatisticsFragment::class.java.declaredFields.firstOrNull { BasePopupView::class.java.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true }?.get(fragment) as? BasePopupView
    }
    private fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask(Callable { action() })
        handler.post(task)
        return try { task.get(3, TimeUnit.SECONDS) } catch (error: ExecutionException) { throw (error.cause ?: error) }
        finally { handler.removeCallbacks(task) }
    }
    private fun await(scenario: ActivityScenario<MainActivity>, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        do {
            var ready = false
            onMain {
                if (ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is MainActivity }) {
                    scenario.onActivity { ready = predicate(it) }
                }
            }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("旧成长详情未在限定时间内恢复")
    }
}
