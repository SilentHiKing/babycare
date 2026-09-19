package com.zero.babycare

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.graphics.Bitmap
import android.content.res.Configuration
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.enums.PopupStatus
import com.zero.components.base.util.DialogHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** 只使用合成时间，直接验证系统无障碍节点；不写入业务记录或系统日期。 */
@RunWith(AndroidJUnit4::class)
class DateTimePickerAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val columnIds = listOf(com.zero.components.R.id.list_year, com.zero.components.R.id.list_month,
        com.zero.components.R.id.list_day, com.zero.components.R.id.list_hour,
        com.zero.components.R.id.list_minute, com.zero.components.R.id.list_second)

    @Test
    fun accessibilityClickSelectsPreviousYearAndSecondsBeforeConfirmation() {
        val initial = time(2026, 1, 1, 12, 34, 37)
        val maximum = time(2026, 1, 2, 23, 58, 47)
        withPicker(initial, maximum) { scenario, popup, confirmed ->
            clickValue(scenario, popup, com.zero.components.R.id.list_year,
                com.zero.common.R.string.picker_year_format, 2025)
            clickValue(scenario, popup, com.zero.components.R.id.list_month,
                com.zero.common.R.string.picker_month_format, 12)
            clickValue(scenario, popup, com.zero.components.R.id.list_day,
                com.zero.common.R.string.picker_day_format, 31)
            clickValue(scenario, popup, com.zero.components.R.id.list_second,
                com.zero.common.R.string.picker_second_format, 38)
            assertEquals("选择数值不能提前提交", 0L, confirmed.get())
            clickNode(popup, com.zero.components.R.id.tv_confirm)
            await(scenario) { popup.popupStatus == PopupStatus.Dismiss }
            assertEquals("跨年选择必须保留月日与秒值", time(2025, 12, 31, 12, 34, 38), confirmed.get())
        }
    }

    @Test
    fun limitedWindowKeepsWholeSelectedLabelsAndConfirmationReachableWithoutFutureValues() {
        val maximum = time(2026, 1, 2, 23, 58, 47)
        listOf(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES).forEach { nightMode ->
            withPicker(maximum + 86_400_000L, maximum, nightMode) { scenario, popup, confirmed ->
                scenario.onActivity {
                    val column = popup.findViewById<RecyclerView>(com.zero.components.R.id.list_year)
                    val selected = (0 until column.childCount).map { column.getChildAt(it) }.first { it.isSelected }
                    assertTrue("回归节点必须实际附着在窗口中", selected.isAttachedToWindow)
                    // 精确覆盖 RecyclerView 跳过原 delegate 的窗口，避免依赖某台设备的帧时序。
                    column.adapter!!.notifyItemChanged(column.getChildAdapterPosition(selected))
                    assertTrue(column.hasPendingAdapterUpdates())
                    val pendingNode = selected.createAccessibilityNodeInfo()
                    try {
                        assertTrue("待更新期间也不能丢失单选及选中语义", pendingNode.isCheckable && pendingNode.isChecked)
                    } finally { pendingNode.recycle() }
                }
                await(scenario) { settled(popup.popupImplView) }
                columnIds.forEach { id ->
                    scenario.onActivity {
                        val column = popup.findViewById<RecyclerView>(id)
                        val selected = (0 until column.childCount).map { column.getChildAt(it) }
                            .filterIsInstance<TextView>().first { it.isSelected }
                        val minimum = column.resources.getDimensionPixelSize(com.zero.common.R.dimen.min_touch_target)
                        assertTrue("日期滚轮每个数值必须保留48dp热区", selected.width >= minimum && selected.height >= minimum)
                        val textLayout = requireNotNull(selected.layout)
                        assertEquals("选择值必须完整排版", selected.text.length, textLayout.getLineEnd(textLayout.lineCount - 1))
                        assertTrue("选择值不能被固定高度裁切", textLayout.height <= selected.height)
                        for (line in 0 until textLayout.lineCount) {
                            assertEquals(0, textLayout.getEllipsisCount(line))
                            assertTrue("年份和单位不能横向裁切", textLayout.getLineLeft(line) >= -1 &&
                                textLayout.getLineRight(line) <= selected.width + 1)
                        }
                        val local = Rect(0, 0, selected.width, selected.height)
                        val sheet = popup.popupImplView as ViewGroup
                        sheet.offsetDescendantRectToMyCoords(selected, local)
                        assertTrue("滚轮不能超出实际弹层宽度", local.left >= 0 && local.right <= sheet.width)
                        selected.requestRectangleOnScreen(Rect(0, 0, selected.width, selected.height), true)
                    }
                    await(scenario) { settled(popup.popupImplView) }
                    scenario.onActivity {
                        val column = popup.findViewById<RecyclerView>(id)
                        val selected = (0 until column.childCount).map { column.getChildAt(it) }.first { it.isSelected }
                        assertFullyVisible(selected, "每列当前值必须可滚入视口")
                        assertFullyVisible(popup.findViewById(com.zero.components.R.id.tv_confirm), "确认必须始终可见")
                    }
                }
                clickNode(popup, com.zero.components.R.id.tv_confirm)
                await(scenario) { popup.popupStatus == PopupStatus.Dismiss }
                assertEquals("未来初始值必须限制到上界，不能丢掉秒", maximum, confirmed.get())
            }
        }
    }

    private fun withPicker(initial: Long, maximum: Long, nightMode: Int? = null,
        block: (ActivityScenario<MainActivity>, BasePopupView, AtomicLong) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        var popup: BasePopupView? = null
        var originalLocalNightMode = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
        var originalUiNight = Configuration.UI_MODE_NIGHT_NO
        val confirmed = AtomicLong()
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            await(scenario) { ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value }
            if (nightMode != null) {
                scenario.onActivity { activity ->
                    originalLocalNightMode = activity.delegate.localNightMode
                    originalUiNight = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    activity.delegate.localNightMode = nightMode
                }
                val expected = if (nightMode == AppCompatDelegate.MODE_NIGHT_YES)
                    Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                await(scenario) { it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == expected }
            }
            scenario.onActivity { activity ->
                popup = DialogHelper.showDateTimeSecondSheet(activity,
                    activity.getString(com.zero.common.R.string.select_time), initial,
                    minTime = time(2024, 12, 31, 0, 0, 0), maxTime = maximum,
                    onConfirm = confirmed::set)
            }
            val shown = requireNotNull(popup)
            await(scenario) { shown.popupStatus == PopupStatus.Show && settled(shown.popupImplView) }
            saveScreenshot(when (nightMode) {
                AppCompatDelegate.MODE_NIGHT_YES -> "datetime-picker-night.png"
                AppCompatDelegate.MODE_NIGHT_NO -> "datetime-picker-light.png"
                else -> "datetime-picker-selection.png"
            })
            block(scenario, shown, confirmed)
        } finally {
            try {
                scenario.onActivity { activity ->
                    popup?.dismiss()
                    if (nightMode != null) activity.delegate.localNightMode = originalLocalNightMode
                }
                if (nightMode != null) await(scenario) {
                    it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == originalUiNight
                }
            }
            finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
                scenario.close()
            }
        }
    }

    private fun clickValue(scenario: ActivityScenario<MainActivity>, popup: BasePopupView,
        columnId: Int, labelRes: Int, value: Int) {
        val label = popup.context.getString(labelRes, value)
        scenario.onActivity {
            val column = popup.findViewById<RecyclerView>(columnId)
            column.requestRectangleOnScreen(Rect(0, 0, column.width, column.height), true)
        }
        await(scenario) { settled(popup.popupImplView) }
        val columnName = popup.resources.getResourceName(columnId)
        // 年切换后通过真实滚动动作访问原先被 maxTime 限制的十二月和月末。
        var reached = false
        for (step in 0 until 60) {
            val candidate = findNodeOrNull(columnName, label, requireVisible = true)
            if (candidate != null) {
                candidate.recycle()
                reached = true
                break
            }
            val columnNode = findNode(columnName, null)
            try { assertTrue("日期范围内的数值必须可通过读屏滚动到达",
                columnNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) }
            finally { columnNode.recycle() }
            await(scenario) {
                popup.findViewById<RecyclerView>(columnId).scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                    settled(popup.popupImplView)
            }
        }
        assertTrue("切换年份后必须恢复完整月份和日期范围", reached)
        val node = findNode(columnName, label)
        var viewState = ""
        scenario.onActivity {
            val column = popup.findViewById<RecyclerView>(columnId)
            viewState = "pending=${column.hasPendingAdapterUpdates()} scroll=${column.scrollState} children=${column.childCount}"
        }
        try {
            val diagnostic = "column=$columnName class=${node.className} id=${node.viewIdResourceName} " +
                "text=${node.text} description=${node.contentDescription} selected=${node.isSelected} " +
                "checkable=${node.isCheckable} checked=${node.isChecked} actions=${node.actions} $viewState"
            assertTrue("滚轮值必须可被读屏点击：$diagnostic", node.isClickable && node.isVisibleToUser)
            assertTrue("数值在滚动和重绑后仍须保留单选语义：$diagnostic", node.isCheckable)
            val descriptionRes = when (columnId) {
                com.zero.components.R.id.list_year -> com.zero.common.R.string.picker_year_accessibility_format
                com.zero.components.R.id.list_month -> com.zero.common.R.string.picker_month_accessibility_format
                com.zero.components.R.id.list_day -> com.zero.common.R.string.picker_day_accessibility_format
                else -> com.zero.common.R.string.picker_second_accessibility_format
            }
            assertEquals("读屏必须区分年份与秒等相同数字", popup.context.getString(descriptionRes, value), node.contentDescription.toString())
            assertFalse("点击前应为另一个选项", node.isSelected)
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } finally { node.recycle() }
        await(scenario) { settled(popup.popupImplView) }
        val updated = findNode(popup.resources.getResourceName(columnId), label, selected = true)
        try {
            assertTrue("读屏必须获知新选中值", updated.isSelected && updated.isChecked)
        } finally { updated.recycle() }
    }

    private fun clickNode(popup: BasePopupView, id: Int) {
        val node = findNode(popup.resources.getResourceName(id), null)
        try { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
        finally { node.recycle() }
    }

    /** 从系统封装的真实窗口节点查找，不能用未附着 View 的空节点替代。 */
    private fun findNode(viewId: String, text: String?, selected: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        do {
            instrumentation.waitForIdleSync()
            findNodeOrNull(viewId, text, selected)?.let { return it }
            SystemClock.sleep(30L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("日期选择器缺少可操作的目标无障碍节点")
    }

    private fun findNodeOrNull(viewId: String, text: String?, selected: Boolean = false,
        requireVisible: Boolean = false): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        try {
            val roots = root.findAccessibilityNodeInfosByViewId(viewId)
            var found: AccessibilityNodeInfo? = null
            roots.forEach { candidate ->
                if (found == null) found = findDescendant(candidate) {
                    (text == null || it.text?.toString() == text) && (!selected || it.isSelected) &&
                        (!requireVisible || it.isVisibleToUser)
                }
                candidate.recycle()
            }
            return found
        } finally { root.recycle() }
    }

    private fun findDescendant(node: AccessibilityNodeInfo,
        matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (matches(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { findDescendant(child, matches)?.let { return it } }
            finally { child.recycle() }
        }
        return null
    }

    private fun assertFullyVisible(view: View, message: String) {
        val visible = Rect()
        assertTrue(message, view.getGlobalVisibleRect(visible) && visible.width() == view.width && visible.height() == view.height)
    }

    private fun settled(view: View): Boolean = view.visibility == View.GONE ||
        (!view.isLayoutRequested && view.width > 0 && view.height > 0 &&
            (view !is ViewGroup || (0 until view.childCount).all { child ->
                val next = view.getChildAt(child)
                next is android.widget.Space || settled(next)
            }))

    private fun await(scenario: ActivityScenario<MainActivity>, ready: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var complete = false
            scenario.onActivity { complete = ready(it) }
            if (complete) return
            SystemClock.sleep(30L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("日期选择器尚未完成布局")
    }

    private fun saveScreenshot(filename: String) {
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
        SystemClock.sleep(350L)
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(instrumentation.targetContext.filesDir, "audit-screens")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, filename).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis
}
