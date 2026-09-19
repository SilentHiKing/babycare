package com.zero.babycare

import android.os.SystemClock
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.home.DashboardFragment
import com.zero.babycare.navigation.NavTarget
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/** 实际 Activity 首轮与窗口重排后，子项测量必须与当前列数同步，不能只更新 LayoutManager。 */
@RunWith(AndroidJUnit4::class)
class DashboardGridLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun quickRecordCellsUseCurrentColumnsAfterFirstAndSubsequentLayouts() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            await(scenario) { ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value }
            scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].navigateTo(NavTarget.Dashboard) }
            await(scenario) { activity ->
                dashboard(activity)?.view?.isShown == true &&
                    dashboard(activity)?.view?.findViewById<View>(com.zero.components.R.id.progressState)?.isShown != true
            }
            scenario.onActivity { activity ->
                // 空库也能验证只读控件排版，无需创建宝宝或修改任何业务数据。
                requireNotNull(dashboard(activity)).requireView()
                    .findViewById<View>(R.id.contentScroll).visibility = View.VISIBLE
            }
            assertGridUsesCurrentColumns(scenario)
            listOf(300, 120, 300).forEach { widthDp ->
                scenario.onActivity { activity ->
                    val root = requireNotNull(dashboard(activity)).requireView()
                    val grid = root.findViewById<RecyclerView>(R.id.rvQuickRecord)
                    grid.layoutParams = grid.layoutParams.apply {
                        width = (widthDp * grid.resources.displayMetrics.density).roundToInt()
                    }
                }
                assertGridUsesCurrentColumns(scenario)
            }
        } finally {
            scenario.close()
        }
    }

    private fun assertGridUsesCurrentColumns(scenario: ActivityScenario<MainActivity>) {
        await(scenario) { grid(it)?.let { list -> list.width > 0 && list.childCount == list.adapter?.itemCount } == true }
        SystemClock.sleep(350L)
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val list = requireNotNull(grid(activity))
            val columns = (list.layoutManager as GridLayoutManager).spanCount
            val expectedCellWidth = (list.width - list.paddingLeft - list.paddingRight).toFloat() / columns
            for (index in 0 until list.childCount) {
                val child = list.getChildAt(index)
                assertTrue("快速记录子项必须按当前列数重测，不能保留旧列宽",
                    abs(child.width - expectedCellWidth) <= 1f)
            }
        }
    }

    private fun dashboard(activity: MainActivity) = activity.supportFragmentManager.fragments
        .filterIsInstance<DashboardFragment>().firstOrNull { !it.isHidden }

    private fun grid(activity: MainActivity) = dashboard(activity)?.view?.findViewById<RecyclerView>(R.id.rvQuickRecord)

    private fun await(scenario: ActivityScenario<MainActivity>, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("快速记录未完成布局")
    }
}
