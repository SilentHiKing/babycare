package com.zero.babycare

import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.customview.widget.ExploreByTouchHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.statistics.widget.BabyCalendarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** 直接查询 Android 实际节点提供器，验证自绘日期可独立选择且与触摸走相同回调。 */
@RunWith(AndroidJUnit4::class)
class BabyCalendarAccessibilityTest {
    @Test
    fun virtualDatesExposeLocalizedStatesAndSupportSelectionAndKeyboard() = onMain {
        val calendar = createCalendar(fontScale = 1f)
        val date = LocalDate.of(2024, 2, 15)
        calendar.setSelectedDate(date, notify = false)
        calendar.setViewMode(BabyCalendarView.ViewMode.MONTH, animate = false)
        calendar.setDatesWithRecords(setOf(date))
        measure(calendar)
        val selections = mutableListOf<LocalDate>()
        calendar.setOnDateSelectedListener(selections::add)

        val provider = requireNotNull(calendar.accessibilityNodeProvider)
        val host = requireNotNull(provider.createAccessibilityNodeInfo(ExploreByTouchHelper.HOST_ID))
        assertTrue("月视图必须提供完整日期节点", host.childCount in 28..42)
        val nodes = (0 until host.childCount).map { id ->
            id to requireNotNull(provider.createAccessibilityNodeInfo(id))
        }
        val selected = nodes.single { (_, node) -> node.isSelected }
        val expectedDate = date.format(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(calendar.resources.configuration.locales[0])
        )
        assertEquals(
            calendar.context.getString(com.zero.common.R.string.calendar_recorded_date_format, expectedDate),
            selected.second.contentDescription.toString()
        )
        nodes.forEach { (_, node) ->
            val bounds = Rect().also(node::getBoundsInParent)
            assertFalse("日期命中区不能为空", bounds.isEmpty)
            assertTrue("日期节点必须位于可见面板内", bounds.top >= 0 && bounds.bottom <= calendar.height)
            assertTrue("日期必须提供选择动作", node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        }

        val nextId = selected.first + 1
        assertTrue(provider.performAction(nextId, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(date.plusDays(1), calendar.getSelectedDate())
        assertEquals(listOf(date.plusDays(1)), selections)
        assertTrue(requireNotNull(provider.createAccessibilityNodeInfo(nextId)).isSelected)
        assertFalse(requireNotNull(provider.createAccessibilityNodeInfo(selected.first)).isSelected)

        // 键盘焦点移动不能立即改日期，回车才真正执行同一个选择回调。
        assertTrue(provider.performAction(nextId, AccessibilityNodeInfo.ACTION_FOCUS, null))
        assertTrue(calendar.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(date.plusDays(1), calendar.getSelectedDate())
        assertTrue(calendar.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertEquals(listOf(date.plusDays(1), date.plusDays(2)), selections)

        calendar.isEnabled = false
        assertFalse(provider.performAction(selected.first, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(2, selections.size)
    }

    @Test
    fun todayAndRecordStatesRemainAvailableWhenSelectedAndFontScaleExpandsRows() = onMain {
        val normal = createCalendar(fontScale = 1f)
        val large = createCalendar(fontScale = 2f)
        listOf(normal, large).forEach { calendar ->
            val today = LocalDate.now()
            calendar.setSelectedDate(today, notify = false)
            calendar.setDatesWithRecords(setOf(today))
            measure(calendar)
            val provider = requireNotNull(calendar.accessibilityNodeProvider)
            val host = requireNotNull(provider.createAccessibilityNodeInfo(ExploreByTouchHelper.HOST_ID))
            assertEquals(7, host.childCount)
            val todayNode = (0 until host.childCount).map {
                requireNotNull(provider.createAccessibilityNodeInfo(it))
            }.single { it.isSelected }
            val date = today.format(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(calendar.resources.configuration.locales[0])
            )
            val todayDescription = calendar.context.getString(com.zero.common.R.string.calendar_today_date_format, date)
            assertEquals(
                calendar.context.getString(com.zero.common.R.string.calendar_recorded_date_format, todayDescription),
                todayNode.contentDescription.toString()
            )
            assertNotNull(todayNode.collectionItemInfo)
        }
        assertTrue("大字号必须增加真实测量高度，不能压缩回固定密度字号", large.height > normal.height)
    }

    private fun createCalendar(fontScale: Float): BabyCalendarView {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply { this.fontScale = fontScale }
        return BabyCalendarView(ContextThemeWrapper(
            base.createConfigurationContext(configuration), com.zero.common.R.style.AppTheme_Girl
        ))
    }

    private fun measure(calendar: BabyCalendarView) {
        val width = (315 * calendar.resources.displayMetrics.density).roundToInt()
        calendar.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        calendar.layout(0, 0, width, calendar.measuredHeight)
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
