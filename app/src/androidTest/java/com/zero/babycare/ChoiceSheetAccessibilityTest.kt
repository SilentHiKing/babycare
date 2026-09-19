package com.zero.babycare

import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.enums.PopupStatus
import com.zero.components.base.util.DialogHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** 通过公开弹窗入口检查真实单选节点、焦点稳定性及最终测量位置，不修改用户设置。 */
@RunWith(AndroidJUnit4::class)
class ChoiceSheetAccessibilityTest {
    @Test
    fun selectionKeepsNodeAndFocusUntilConfirmationInCenteredSheet() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var popup: BasePopupView? = null
        val confirmed = mutableListOf<Int>()
        try {
            await(scenario, "初始页面未就绪") { activity ->
                ViewModelProvider(activity)[MainViewModel::class.java].babyDataLoaded.value
            }
            scenario.onActivity { activity ->
                popup = DialogHelper.showChoiceSheet(
                    context = activity,
                    title = activity.getString(com.zero.common.R.string.feeding_amount),
                    options = listOf(
                        DialogHelper.PickerOption(0, activity.getString(com.zero.common.R.string.unit_ml_abbr)),
                        DialogHelper.PickerOption(1, activity.getString(com.zero.common.R.string.unit_oz_abbr))
                    ),
                    selectedValue = 0,
                    onSelected = { confirmed.add(it) }
                )
            }
            val shownPopup = requireNotNull(popup)
            await(scenario, "选择弹窗未完成显示") { shownPopup.popupStatus == PopupStatus.Show }
            scenario.onActivity { activity ->
                val container = shownPopup.findViewById<LinearLayout>(com.zero.components.R.id.option_container)
                assertEquals(activity.getString(com.zero.common.R.string.feeding_amount),
                    ViewCompat.getAccessibilityPaneTitle(container)?.toString())
                fun rowAt(index: Int) = (container.getChildAt(index) as LinearLayout).getChildAt(0) as FrameLayout
                val first = rowAt(0)
                val second = rowAt(1)
                val firstNode = first.createAccessibilityNodeInfo()
                val secondNode = second.createAccessibilityNodeInfo()
                assertEquals("android.widget.RadioButton", firstNode.className.toString())
                assertTrue(firstNode.isCheckable)
                assertTrue(firstNode.isChecked)
                assertFalse(secondNode.isChecked)
                assertEquals(activity.getString(com.zero.common.R.string.unit_oz_abbr), secondNode.text.toString())

                // 模拟键盘获得焦点后执行无障碍选择，不依赖 shell 坐标注入。
                assertTrue(second.requestFocusFromTouch())
                assertTrue(second.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertSame("选择后必须保留原节点", second, rowAt(1))
                assertTrue("选择后必须保留键盘焦点", second.isFocused)
                assertFalse(first.createAccessibilityNodeInfo().isChecked)
                assertTrue(second.createAccessibilityNodeInfo().isChecked)
                assertTrue("选择选项时不能提前提交业务值", confirmed.isEmpty())

                val sheet = shownPopup.popupImplView
                val maxWidth = activity.resources.getDimensionPixelSize(com.zero.common.R.dimen.picker_sheet_max_width)
                assertTrue("底部选择器必须遵循实际最大宽度", sheet.width > 0 && sheet.width <= maxWidth)
                val sheetPosition = IntArray(2).also(sheet::getLocationOnScreen)
                val popupPosition = IntArray(2).also(shownPopup::getLocationOnScreen)
                val sheetCenter = sheetPosition[0] + sheet.width / 2f
                val popupCenter = popupPosition[0] + shownPopup.width / 2f
                assertTrue("限宽后的选择器必须在窗口中水平居中", abs(sheetCenter - popupCenter) <= 1f)

                assertTrue(shownPopup.findViewById<View>(com.zero.components.R.id.tv_confirm).performClick())
                assertEquals(listOf(1), confirmed)
            }
            await(scenario, "确认后选择弹窗未关闭") { shownPopup.popupStatus == PopupStatus.Dismiss }
        } finally {
            try {
                scenario.onActivity { popup?.dismiss() }
            } finally {
                scenario.close()
            }
        }
    }

    private fun await(
        scenario: ActivityScenario<MainActivity>,
        message: String,
        condition: (MainActivity) -> Boolean
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError(message)
    }
}
