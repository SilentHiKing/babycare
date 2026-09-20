package com.zero.babycare

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.statistics.adapter.StatisticsGrowthPercentileAdapter
import com.zero.babycare.statistics.model.GrowthPercentileOverview
import com.zero.babycare.statistics.model.GrowthPercentilePoint
import com.zero.babycare.statistics.model.GrowthPercentileSeries
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.zero.common.R as CommonR

/** 合成模型通过生产 Adapter 绑定真实窗口；不改宝宝、语言设置或业务数据库。 */
@RunWith(AndroidJUnit4::class)
class GrowthPercentileExplanationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun datedMeasurementsReachRealAccessibilityNodeAndRebindingClearsOldHistory() = withActivity(requireTouchExploration = true) { scenario ->
        withChart(scenario, 320, 1f, CommonR.style.AppTheme_Boy, Configuration.UI_MODE_NIGHT_NO) { fixture ->
            // 同一天两次测量不能合并；跨年和不等日距都要保留，而不是只剩最后一个 P 值。
            val points = listOf(
                GrowthPercentilePoint(LocalDate.of(2025, 12, 30), 10),
                GrowthPercentilePoint(LocalDate.of(2025, 12, 30), 20),
                GrowthPercentilePoint(LocalDate.of(2025, 12, 31), 50),
                GrowthPercentilePoint(LocalDate.of(2026, 2, 15), 90)
            )
            bind(scenario, fixture, overview(points))
            assertAccessibleHistory(scenario, fixture, points)

            // 真实重用同一 holder，验证缺数据和 null 模型不会留下旧宝宝的日期或节点描述。
            for (empty in listOf(overview(emptyList()), null)) {
                bind(scenario, fixture, empty)
                scenario.onActivity {
                    assertEquals(View.GONE, fixture.chart.visibility)
                    assertNull(fixture.chart.contentDescription)
                    assertEquals(View.GONE, fixture.period.visibility)
                    assertEquals("", fixture.period.text.toString())
                }
                assertTrue("空态不能保留可见的历史曲线节点", visibleChartDescriptions().isEmpty())
            }

            val replacement = (0 until 12).map {
                GrowthPercentilePoint(LocalDate.of(2026, 3, 1).plusDays((it * it).toLong()), 30 + it)
            }
            bind(scenario, fixture, overview(replacement))
            assertAccessibleHistory(scenario, fixture, replacement)
            scenario.onActivity {
                assertTrue("新序列不能残留上一年的测量", !fixture.chart.contentDescription.contains("2025"))
            }
        }
    }

    @Test
    fun explanationsFitNarrowAndWideLayoutsAtBothFontSizesAndBabyThemes() = withActivity { scenario ->
        val points = listOf(
            GrowthPercentilePoint(LocalDate.of(2025, 12, 30), 10),
            GrowthPercentilePoint(LocalDate.of(2026, 1, 1), 50),
            GrowthPercentilePoint(LocalDate.of(2026, 11, 30), 90)
        )
        for (theme in listOf(CommonR.style.AppTheme_Boy, CommonR.style.AppTheme_Girl)) {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                for (font in listOf(1f, 2f)) {
                    for (width in listOf(320, 600)) {
                        withChart(scenario, width, font, theme, night) { fixture ->
                            bind(scenario, fixture, overview(points, allSeries = true))
                            scenario.onActivity {
                                val identity = "width=$width font=$font theme=$theme night=$night"
                                val note = fixture.root.findViewById<TextView>(R.id.tvGrowthPercentileChartNote)
                                assertEquals(fixture.root.context.getString(CommonR.string.statistics_growth_percentile_chart_note), note.text.toString())
                                assertFullText(fixture.root, note, identity)
                                listOf(R.id.itemGrowthPercentileWeight, R.id.itemGrowthPercentileHeight,
                                    R.id.itemGrowthPercentileHead).forEach { id ->
                                    val item = fixture.root.findViewById<ViewGroup>(id)
                                    val period = item.findViewById<TextView>(R.id.tvGrowthPercentilePeriod)
                                    assertEquals(periodText(period, points), period.text.toString())
                                    assertFullText(item, period, identity)
                                    val chart = item.findViewById<View>(R.id.chartGrowthPercentile)
                                    assertTrue("日期与曲线不能重叠：$identity", period.bottom <= chart.top)
                                    assertTrue("新说明不能盖住最新值：$identity",
                                        chart.bottom <= (item.findViewById<View>(R.id.tvGrowthPercentileValue).parent as View).top)
                                }
                            }
                            if (width == 320) {
                                val gender = if (theme == CommonR.style.AppTheme_Boy) "boy" else "girl"
                                val mode = if (night == Configuration.UI_MODE_NIGHT_YES) "night" else "day"
                                captureScreenshot(scenario, fixture.root, "320dp-font${font.toInt()}-$gender-$mode")
                            }
                        }
                    }
                }
            }
        }
    }

    private data class Fixture(
        val scroll: NestedScrollView,
        val root: ViewGroup,
        val adapter: StatisticsGrowthPercentileAdapter,
        val holder: StatisticsGrowthPercentileAdapter.PercentileViewHolder
    ) {
        private val weight get() = root.findViewById<ViewGroup>(R.id.itemGrowthPercentileWeight)
        val chart: View get() = weight.findViewById(R.id.chartGrowthPercentile)
        val period: TextView get() = weight.findViewById(R.id.tvGrowthPercentilePeriod)
    }

    private fun withActivity(
        requireTouchExploration: Boolean = false,
        block: (ActivityScenario<MainActivity>) -> Unit
    ) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        try {
            await(scenario) { ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value }
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                if (requireTouchExploration) flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            }
            if (requireTouchExploration) {
                // View 的读屏焦点要求触摸探索已开启；不能只取到节点就假定服务前置状态就绪。
                await(scenario, "UiAutomation 未启用触摸探索，无法验证真实读屏焦点") {
                    val manager = it.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                    manager.isEnabled && manager.isTouchExplorationEnabled
                }
            }
            block(scenario)
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            scenario.close()
        }
    }

    private fun withChart(
        scenario: ActivityScenario<MainActivity>, widthDp: Int, fontScale: Float,
        theme: Int, night: Int, block: (Fixture) -> Unit
    ) {
        lateinit var fixture: Fixture
        var siblings = emptyList<Pair<View, Int>>()
        scenario.onActivity { activity ->
            val configuration = Configuration(activity.resources.configuration).apply {
                this.fontScale = fontScale
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                setLocale(Locale.US)
            }
            val context = ContextThemeWrapper(activity.createConfigurationContext(configuration), theme)
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            siblings = (0 until content.childCount).map { content.getChildAt(it) }.map { it to it.importantForAccessibility }
            siblings.forEach { (view, _) -> view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            val scroll = NestedScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
            val adapter = StatisticsGrowthPercentileAdapter()
            val holder = adapter.createViewHolder(scroll, 0) as StatisticsGrowthPercentileAdapter.PercentileViewHolder
            val root = holder.itemView as ViewGroup
            scroll.addView(root)
            fixture = Fixture(scroll, root, adapter, holder)
            // 宽度矩阵测真实挂载子树的排版；600dp 可超出手机视窗，只读节点测试使用320dp。
            content.addView(scroll, FrameLayout.LayoutParams(
                (widthDp * context.resources.displayMetrics.density).roundToInt(), content.height))
        }
        try {
            block(fixture)
        } finally {
            scenario.onActivity {
                (fixture.scroll.parent as? ViewGroup)?.removeView(fixture.scroll)
                siblings.forEach { (view, flag) -> view.importantForAccessibility = flag }
            }
        }
    }

    private fun bind(scenario: ActivityScenario<MainActivity>, fixture: Fixture, model: GrowthPercentileOverview?) {
        scenario.onActivity {
            fixture.adapter.updatePercentile(model)
            fixture.adapter.bindViewHolder(fixture.holder, 0)
        }
        await(scenario) { fixture.root.isAttachedToWindow && settled(fixture.root) }
    }

    private fun assertAccessibleHistory(
        scenario: ActivityScenario<MainActivity>, fixture: Fixture, points: List<GrowthPercentilePoint>
    ) {
        var expected = ""
        scenario.onActivity {
            val context = fixture.root.context
            val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.US)
            val measurements = points.joinToString("\n") {
                context.getString(CommonR.string.statistics_growth_percentile_point_accessibility_format,
                    it.date.format(formatter), it.percentile)
            }
            expected = context.getString(CommonR.string.statistics_growth_percentile_chart_accessibility_format,
                context.getString(CommonR.string.statistics_growth_weight), periodText(fixture.period, points), measurements)
            assertEquals(periodText(fixture.period, points), fixture.period.text.toString())
            fixture.chart.requestRectangleOnScreen(Rect(0, 0, fixture.chart.width, fixture.chart.height), true)
        }
        await(scenario) { settled(fixture.scroll) }
        val deadline = SystemClock.uptimeMillis() + 5_000L
        do {
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                try {
                    val nodes = root.findAccessibilityNodeInfosByViewId("com.zero.babycare:id/chartGrowthPercentile")
                    try {
                        val node = nodes.firstOrNull { it.isVisibleToUser && it.contentDescription?.toString() == expected }
                        if (node != null) {
                            // 对已获焦的节点重复请求会返回 false，先清焦再验证一次真实状态转换。
                            if (node.isAccessibilityFocused) {
                                assertTrue("已有读屏焦点须可清除", node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS))
                                instrumentation.waitForIdleSync()
                                assertTrue("节点须已清除读屏焦点", node.refresh() && !node.isAccessibilityFocused)
                            }
                            assertTrue("完整曲线描述须可获读屏焦点", node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                            instrumentation.waitForIdleSync()
                            assertTrue("真实节点须保持读屏焦点", node.refresh() && node.isAccessibilityFocused)
                            return
                        }
                    } finally { nodes.forEach { it.recycle() } }
                } finally { root.recycle() }
            }
            SystemClock.sleep(30L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("真实窗口未提供完整日期/P序列；实际=${visibleChartDescriptions()} 预期=$expected")
    }

    private fun visibleChartDescriptions(): List<String> {
        instrumentation.waitForIdleSync()
        val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        try {
            val nodes = root.findAccessibilityNodeInfosByViewId("com.zero.babycare:id/chartGrowthPercentile")
            return try { nodes.filter { it.isVisibleToUser }.map { it.contentDescription.toString() } }
            finally { nodes.forEach { it.recycle() } }
        } finally { root.recycle() }
    }

    private fun periodText(view: View, points: List<GrowthPercentilePoint>): String {
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.US)
        return view.context.getString(CommonR.string.statistics_growth_percentile_period_format,
            points.size, points.first().date.format(formatter), points.last().date.format(formatter))
    }

    /** 只在显式审查参数开启时绘制合成根视图，不截取下方真实 Activity 或宝宝资料。 */
    private fun captureScreenshot(scenario: ActivityScenario<MainActivity>, root: View, name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
        lateinit var bitmap: Bitmap
        scenario.onActivity {
            bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
        }
        try {
            val directory = File(instrumentation.targetContext.filesDir, "audit-screens")
            check(directory.isDirectory || directory.mkdirs()) { "无法创建百分位截图目录" }
            File(directory, "phase12-percentile-$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "无法保存合成百分位截图" }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun overview(points: List<GrowthPercentilePoint>, allSeries: Boolean = false): GrowthPercentileOverview {
        fun series(label: Int, unit: Int, values: List<GrowthPercentilePoint>) = GrowthPercentileSeries(
            label, unit, values.lastOrNull()?.let { 7.5 }, values.lastOrNull()?.percentile, values)
        return GrowthPercentileOverview(
            series(CommonR.string.statistics_growth_weight, CommonR.string.weight_unit, points),
            series(CommonR.string.statistics_growth_height, CommonR.string.height_unit, if (allSeries) points else emptyList()),
            series(CommonR.string.statistics_growth_head, CommonR.string.height_unit, if (allSeries) points else emptyList()),
            CommonR.string.statistics_growth_percentile_note_who)
    }

    private fun assertFullText(parent: ViewGroup, text: TextView, identity: String) {
        val layout = requireNotNull(text.layout)
        val bounds = Rect(0, 0, text.width, text.height).also { parent.offsetDescendantRectToMyCoords(text, it) }
        val detail = "$identity id=${text.resources.getResourceEntryName(text.id)} bounds=$bounds " +
            "view=${text.width}x${text.height} layout=${layout.width}x${layout.height}"
        val available = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
        assertEquals("说明须完整排版：$detail", text.text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue("说明高度不能裁切：$detail", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
        for (line in 0 until layout.lineCount) {
            assertEquals("说明不得省略：$detail", 0, layout.getEllipsisCount(line))
            assertTrue("说明不得横向越界：$detail", layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= available + 1f)
        }
        assertTrue("说明须位于容器内：$detail", bounds.left >= parent.paddingLeft &&
            bounds.right <= parent.width - parent.paddingRight && bounds.top >= parent.paddingTop &&
            bounds.bottom <= parent.height - parent.paddingBottom)
    }

    private fun settled(view: View): Boolean = view.visibility == View.GONE ||
        (!view.isLayoutRequested && (view !is ViewGroup || (0 until view.childCount).all { settled(view.getChildAt(it)) }))

    private fun await(
        scenario: ActivityScenario<MainActivity>,
        message: String = "百分位说明未完成真实布局",
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
