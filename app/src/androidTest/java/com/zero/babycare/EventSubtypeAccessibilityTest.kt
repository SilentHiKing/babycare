package com.zero.babycare

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.home.record.event.EventCategory
import com.zero.babycare.home.record.event.EventSubtype
import com.zero.babycare.home.record.event.EventSubtypeAdapter
import com.zero.common.ext.getThemeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** 挂载生产 Adapter 并从系统窗口操作节点；只有内存选项，没有记录写入。 */
@RunWith(AndroidJUnit4::class)
class EventSubtypeAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun subtypeSelectionIsExposedAndOperableThroughRealAccessibilityNodes() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        var host: RecyclerView? = null
        var siblings: List<Pair<View, Int>> = emptyList()
        try {
            await(scenario) { ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value }
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            }
            await(scenario, "UiAutomation 未能启用触摸探索，无法验证真实无障碍焦点；不以键盘焦点代替") {
                (it.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled
            }
            val options = EventSubtype.getSubtypes(EventCategory.DIAPER).take(2)
            val selected = mutableListOf<Int>()
            lateinit var adapter: EventSubtypeAdapter
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                siblings = (0 until content.childCount).map { content.getChildAt(it) }
                    .map { it to it.importantForAccessibility }
                siblings.forEach { (view, _) -> view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
                adapter = EventSubtypeAdapter { selected += it.type }
                host = RecyclerView(activity).apply {
                    layoutManager = GridLayoutManager(activity, 1)
                    overScrollMode = View.OVER_SCROLL_NEVER
                    this.adapter = adapter
                    setBackgroundColor(activity.getThemeColor(com.zero.common.R.attr.colorBackground))
                }
                content.addView(host, FrameLayout.LayoutParams(
                    (288 * activity.resources.displayMetrics.density).roundToInt(), ViewGroup.LayoutParams.MATCH_PARENT))
                adapter.submitList(options)
                adapter.setSelectedType(options.first().type)
            }
            val list = requireNotNull(host)
            awaitLayout(scenario, list)
            assertSelection(scenario, list, selectedPosition = 0)

            lateinit var focusedOption: View
            scenario.onActivity {
                focusedOption = requireNotNull(list.findViewHolderForAdapterPosition(1)).itemView
                assertTrue("子类型必须能通过键盘获得焦点", focusedOption.requestFocusFromTouch())
            }
            withNode(scenario, list, 1) { node ->
                assertTrue("未选子类型必须暴露可执行的点击动作", node.isClickable)
                assertTrue("系统节点必须保留当前键盘焦点", node.isFocused)
                // 主动执行真实服务动作；键盘焦点与读屏焦点分别验证，不能相互替代。
                if (node.isAccessibilityFocused) node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
                assertTrue("系统节点必须接受真实无障碍焦点动作",
                    node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            }
            withNode(scenario, list, 1) { node ->
                assertTrue("选择前读屏焦点必须确实位于目标节点", node.isAccessibilityFocused)
                assertTrue("系统无障碍点击必须能选择子类型", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            awaitLayout(scenario, list)
            scenario.onActivity {
                assertEquals(listOf(options[1].type), selected)
                assertSame("改选后不能替换正在操作的节点", focusedOption,
                    requireNotNull(list.findViewHolderForAdapterPosition(1)).itemView)
                assertTrue("改选后焦点必须保留在原选项", focusedOption.isFocused)
            }
            assertSelection(scenario, list, selectedPosition = 1)
            withNode(scenario, list, 1) { node ->
                assertTrue("选择并刷新后读屏焦点必须留在同一节点", node.isAccessibilityFocused)
            }

            // 编辑记录回填走同一生产入口，不能只在手工点击时补状态。
            scenario.onActivity { adapter.setSelectedType(options.first().type) }
            awaitLayout(scenario, list)
            assertSelection(scenario, list, selectedPosition = 0)
            scenario.onActivity { adapter.setLocked(true) }
            awaitLayout(scenario, list)
            withNode(scenario, list, 1) { node ->
                assertFalse("锁定子类型节点必须声明不可用", node.isEnabled)
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals("锁定后不可发出选择回调", listOf(options[1].type), selected)
                assertEquals(options.first().type, adapter.getSelectedType())
            }
            assertSelection(scenario, list, selectedPosition = 0)
            scenario.onActivity { adapter.setSelectedType(options[1].type) }
            awaitLayout(scenario, list)
            assertSelection(scenario, list, selectedPosition = 1)
        } finally {
            try {
                scenario.onActivity {
                    host?.adapter = null
                    (host?.parent as? ViewGroup)?.removeView(host)
                    siblings.forEach { (view, flag) -> view.importantForAccessibility = flag }
                }
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
                scenario.close()
            }
        }
    }

    private fun assertSelection(scenario: ActivityScenario<MainActivity>, host: RecyclerView, selectedPosition: Int) {
        repeat(2) { position ->
            withNode(scenario, host, position) { node ->
                val expected = position == selectedPosition
                val detail = "position=$position selected=$selectedPosition class=${node.className} " +
                    "checkable=${node.isCheckable} checked=${node.isChecked} selectedNode=${node.isSelected}"
                assertTrue("子类型必须声明单选状态：$detail", node.isCheckable)
                assertEquals("读屏选中状态必须与实际子类型一致：$detail", expected, node.isChecked)
                assertEquals("列表选中语义必须同步：$detail", expected, node.isSelected)
            }
        }
    }

    private fun withNode(
        scenario: ActivityScenario<MainActivity>, host: RecyclerView, position: Int,
        block: (AccessibilityNodeInfo) -> Unit
    ) {
        lateinit var viewId: String
        val expectedBounds = Rect()
        scenario.onActivity {
            val view = requireNotNull(host.findViewHolderForAdapterPosition(position)).itemView
            val location = IntArray(2).also(view::getLocationOnScreen)
            expectedBounds.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
            viewId = view.resources.getResourceName(view.id)
        }
        instrumentation.waitForIdleSync()
        val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        try {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            try {
                val node = nodes.firstOrNull {
                    val actual = Rect().also(it::getBoundsInScreen)
                    actual == expectedBounds
                }
                assertTrue("系统窗口必须提供对应子类型节点：position=$position bounds=$expectedBounds", node != null)
                block(requireNotNull(node))
            } finally { nodes.forEach { it.recycle() } }
        } finally { root.recycle() }
    }

    private fun awaitLayout(scenario: ActivityScenario<MainActivity>, host: RecyclerView) = await(scenario) {
        !host.isLayoutRequested && !host.hasPendingAdapterUpdates() &&
            (0..1).all { host.findViewHolderForAdapterPosition(it)?.itemView?.isLayoutRequested == false }
    }

    private fun await(
        scenario: ActivityScenario<MainActivity>, message: String = "事件子类型测试视图未完成布局",
        condition: (MainActivity) -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(30L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError(message)
    }
}
