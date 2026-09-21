package com.zero.babycare

import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.components.widget.RecordTimerPanelView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** 检查真实测量结果，防止横竖排列切换后结束字段落到面板裁剪范围外。 */
@RunWith(AndroidJUnit4::class)
class RecordTimerPanelLayoutTest {
    @Test
    fun narrowAndWideLayoutsKeepBothTimeFieldsInsidePanel() {
        withPanel(fontScale = 1f) { panel ->
            measureAndCheck(panel, widthDp = 315, stacked = true)
            measureAndCheck(panel, widthDp = 600, stacked = false)
            measureAndCheck(panel, widthDp = 315, stacked = true)
        }
    }

    @Test
    fun largeFontKeepsBothTimeFieldsVisibleOnFirstLayout() {
        withPanel(fontScale = 1.5f) { panel ->
            measureAndCheck(panel, widthDp = 600, stacked = true)
        }
    }

    private fun withPanel(fontScale: Float, block: (RecordTimerPanelView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val appContext = instrumentation.targetContext
            listOf(com.zero.common.R.style.AppTheme_Boy, com.zero.common.R.style.AppTheme_Girl).forEach { theme ->
                listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).forEach { night ->
                    val configuration = Configuration(appContext.resources.configuration).apply {
                        this.fontScale = fontScale
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                    }
                    val context = ContextThemeWrapper(appContext.createConfigurationContext(configuration), theme)
                    val panel = RecordTimerPanelView(context)
                    try { block(panel) } finally { panel.timerView.release() }
                }
            }
        }
    }

    private fun measureAndCheck(panel: RecordTimerPanelView, widthDp: Int, stacked: Boolean) {
        val width = (widthDp * panel.resources.displayMetrics.density).roundToInt()
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        panel.layout(0, 0, width, panel.measuredHeight)

        fun bounds(view: View) = Rect().also { rect ->
            view.getDrawingRect(rect)
            panel.offsetDescendantRectToMyCoords(view, rect)
            assertTrue("时间字段必须有有效尺寸", rect.width() > 0 && rect.height() > 0)
            assertTrue("时间字段必须在面板范围内", rect.left >= 0 && rect.top >= 0 &&
                rect.right <= panel.width && rect.bottom <= panel.height)
        }

        val start = bounds(panel.startInput)
        val end = bounds(panel.endInput)
        if (stacked) {
            assertTrue("窄窗和大字号下结束字段必须排列在开始字段下方", end.top >= start.bottom)
            assertEquals(start.left, end.left)
        } else {
            assertEquals(start.top, end.top)
            assertTrue("宽窗下时间字段必须并排且不重叠", end.left >= start.right)
        }
        bounds(panel.startPicker)
        bounds(panel.endPicker)
    }
}
