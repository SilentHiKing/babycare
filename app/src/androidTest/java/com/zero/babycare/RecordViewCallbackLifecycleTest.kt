package com.zero.babycare

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.home.record.event.EventCategory
import com.zero.babycare.home.record.event.EventRecordFragment
import com.zero.babycare.navigation.NavTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 重放已出队的 UI 回调，确定性覆盖 View 销毁和同一 Fragment 新 View 的边界。 */
@RunWith(AndroidJUnit4::class)
class RecordViewCallbackLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun queuedCategoryScrollDoesNotAccessDestroyedOrReplacementView() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            await(scenario) { mainVm(it).babyDataLoaded.value }
            scenario.onActivity { activity ->
                assumeTrue(mainVm(activity).getCurrentBabyInfo() != null)
                mainVm(activity).navigateTo(NavTarget.EventRecord(returnTarget = NavTarget.Dashboard))
            }
            await(scenario) { eventFragment(it)?.view?.isShown == true }
            scenario.onActivity { activity ->
                val fragment = requireNotNull(eventFragment(activity))
                val oldView = fragment.requireView()
                val oldRecycler = oldView.findViewById<RecyclerView>(R.id.rvCategory)
                val oldManager = CountingLayoutManager(activity)
                oldRecycler.layoutManager = oldManager
                val staleScroll = queueScroll(fragment)

                // 在同一主线程事务内销毁 View，确保 post 尚未执行，随后主动模拟迟到投递。
                activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
                assertNull(fragment.view)
                assertNull(pendingScroll(fragment))
                staleScroll.run()
                assertEquals(0, oldManager.scrollCalls)

                activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
                assertNotSame(oldView, fragment.requireView())
                val newRecycler = fragment.requireView().findViewById<RecyclerView>(R.id.rvCategory)
                val newManager = CountingLayoutManager(activity)
                newRecycler.layoutManager = newManager
                val currentScroll = queueScroll(fragment)
                // 旧请求不得覆盖新请求或操作新旧列表；当前请求仍能正常工作。
                staleScroll.run()
                assertEquals(0, oldManager.scrollCalls)
                assertEquals(0, newManager.scrollCalls)
                newRecycler.removeCallbacks(currentScroll)
                currentScroll.run()
                assertEquals(1, newManager.scrollCalls)
            }
        } finally {
            scenario.onActivity { mainVm(it).navigateTo(NavTarget.Dashboard) }
            scenario.close()
        }
    }

    private fun queueScroll(fragment: EventRecordFragment): Runnable {
        EventRecordFragment::class.java.getDeclaredMethod("scrollCategoryIntoView", EventCategory::class.java)
            .apply { isAccessible = true }.invoke(fragment, EventCategory.GROWTH)
        return requireNotNull(pendingScroll(fragment))
    }

    private fun pendingScroll(fragment: EventRecordFragment): Runnable? =
        EventRecordFragment::class.java.getDeclaredField("pendingCategoryScroll")
            .apply { isAccessible = true }.get(fragment) as? Runnable

    private class CountingLayoutManager(context: Context) : LinearLayoutManager(context, RecyclerView.HORIZONTAL, false) {
        var scrollCalls = 0
        override fun smoothScrollToPosition(recyclerView: RecyclerView, state: RecyclerView.State, position: Int) {
            scrollCalls++
        }
    }

    private fun eventFragment(activity: MainActivity) = activity.supportFragmentManager.fragments
        .filterIsInstance<EventRecordFragment>().firstOrNull { !it.isHidden }

    private fun mainVm(activity: MainActivity) = ViewModelProvider(activity)[MainViewModel::class.java]

    private fun await(scenario: ActivityScenario<MainActivity>, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("事件页未在限定时间内完成加载")
    }
}
