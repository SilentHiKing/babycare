package com.zero.babycare

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.statistics.widget.PercentileChartView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import com.zero.common.R as CommonR

/** 通过实际像素检验换数据和窗口尺寸后的缓存更新，避免旧 Path 留在重用的 View 中。 */
@RunWith(AndroidJUnit4::class)
class PercentileChartRenderingTest {
    @Test
    fun changingDataAndSizeRefreshesCurveWhileRepeatedDrawIsStable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext,
                CommonR.style.AppTheme_Boy)
            val view = PercentileChartView(context)
            val density = context.resources.displayMetrics.density
            val width = (120 * density).roundToInt()
            val height = (60 * density).roundToInt()
            val inset = (6 * density).roundToInt()
            val growth = ContextCompat.getColor(context, CommonR.color.event_growth)
            view.layout(0, 0, width, height)
            view.setPercentiles(listOf(0, 100))
            val first = draw(view)
            try {
                assertEquals(growth, first.getPixel(width - inset, inset))
                val repeated = draw(view)
                try { assertTrue(first.sameAs(repeated)) } finally { repeated.recycle() }

                view.setPercentiles(listOf(100, 0))
                val changed = draw(view)
                try {
                    assertNotEquals(growth, changed.getPixel(width - inset, inset))
                    assertEquals(growth, changed.getPixel(width - inset, height - inset))
                } finally { changed.recycle() }

                view.layout(0, 0, width * 2, height)
                val resized = draw(view)
                try { assertEquals(growth, resized.getPixel(width * 2 - inset, height - inset)) }
                finally { resized.recycle() }

                view.setPercentiles(emptyList())
                val cleared = draw(view)
                try { assertNotEquals(growth, cleared.getPixel(width * 2 - inset, height - inset)) }
                finally { cleared.recycle() }
            } finally { first.recycle() }
        }
    }

    private fun draw(view: PercentileChartView): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
}
