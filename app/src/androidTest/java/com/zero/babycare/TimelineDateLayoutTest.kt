package com.zero.babycare

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.statistics.adapter.TimelineAdapter
import com.zero.babycare.statistics.mapper.StatisticsTimelineMapper
import com.zero.babycare.statistics.model.TimelineEditTarget
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.TemperatureData
import com.zero.common.ext.getThemeColor
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 生产 Mapper + Adapter 在真实窄窗大字帧中展示跨年原时间，点击仍指向同一原记录。 */
@RunWith(AndroidJUnit4::class)
class TimelineDateLayoutTest {
    @Test fun crossYearDateFitsAndClickKeepsOriginalRecordIdentity() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitInitialData(scenario)
            listOf("boy" to com.zero.common.R.style.AppTheme_Boy,
                "girl" to com.zero.common.R.style.AppTheme_Girl).forEach { (gender, theme) ->
                listOf("day" to Configuration.UI_MODE_NIGHT_NO,
                    "night" to Configuration.UI_MODE_NIGHT_YES).forEach { (mode, nightMode) ->
                    assertTheme(scenario, theme, nightMode, "$gender-$mode")
                }
            }
            assertTheme(scenario, com.zero.common.R.style.AppTheme_Boy, Configuration.UI_MODE_NIGHT_NO,
                "boy-day-default", widthDp = 360, fontScale = 1f)
        } finally {
            scenario.close()
        }
    }

    private fun assertTheme(
        scenario: ActivityScenario<MainActivity>, theme: Int, nightMode: Int, themeName: String,
        widthDp: Int = 288, fontScale: Float = 2f
    ) {
        var host: RecyclerView? = null
        try {
            val selected = mutableListOf<TimelineEditTarget>()
            lateinit var adapter: TimelineAdapter
            lateinit var mapper: StatisticsTimelineMapper
            scenario.onActivity { activity ->
                val configuration = Configuration(activity.resources.configuration).apply {
                    this.fontScale = fontScale
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                    setLocale(Locale.US)
                }
                val context = ContextThemeWrapper(activity.createConfigurationContext(configuration), theme)
                // Android 编译会折叠资源边界空格，检查实际资源而非 XML 源码中的外观。
                assertEquals(" · ", context.getString(com.zero.common.R.string.list_separator_dot))
                assertEquals("Left side 10 min", context.getString(com.zero.common.R.string.feeding_breast_side_minutes_format,
                    context.getString(com.zero.common.R.string.left_breast), 10L))
                mapper = StatisticsTimelineMapper(object : StatisticsTimelineMapper.Strings {
                    override fun get(resId: Int): String = context.getString(resId)
                    override fun format(resId: Int, vararg args: Any): String = context.getString(resId, *args)
                }, ZoneId.of("Asia/Shanghai"))
                adapter = TimelineAdapter { selected += it.editTarget }
                host = RecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context)
                    itemAnimator = null
                    overScrollMode = View.OVER_SCROLL_NEVER
                    setBackgroundColor(context.getThemeColor(com.zero.common.R.attr.colorBackground))
                    this.adapter = adapter
                }
                activity.findViewById<ViewGroup>(android.R.id.content).addView(host,
                    FrameLayout.LayoutParams((widthDp * context.resources.displayMetrics.density).roundToInt(), ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val start = Instant.parse("2025-12-31T15:00:00Z").toEpochMilli()
            val end = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
            val items = listOf(
                mapper.mapFeeding(FeedingRecord(feedingId = 9001, babyId = 9000, feedingType = FeedingType.BREAST.type,
                    feedingStart = start, feedingEnd = end, feedingDuration = 15 * 60_000L,
                    feedingDurationBreastLeft = 10 * 60_000L, feedingDurationBreastRight = 5 * 60_000L)),
                mapper.mapSleep(SleepRecord(sleepId = 9002, babyId = 9000,
                    sleepStart = start, sleepEnd = end, sleepDuration = end - start)),
                mapper.mapEvent(EventRecord(eventId = 9003, babyId = 9000,
                    type = EventType.HEALTH_TEMPERATURE, time = start,
                    extraData = TemperatureData(37.6, TemperatureData.LOCATION_FOREHEAD).toJson()))
            )
            val list = requireNotNull(host)
            items.forEach { item ->
                val frame = CountDownLatch(1)
                val listener = object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (!hasPendingLayout(list) && !list.hasPendingAdapterUpdates() && list.findViewHolderForAdapterPosition(0) != null) {
                            list.viewTreeObserver.removeOnPreDrawListener(this)
                            frame.countDown()
                        }
                        return true
                    }
                }
                try {
                    scenario.onActivity {
                        list.viewTreeObserver.addOnPreDrawListener(listener)
                        adapter.submitList(listOf(item))
                    }
                    assertTrue("时间轴未完成真实布局帧", frame.await(10, TimeUnit.SECONDS))
                    scenario.onActivity {
                        val root = requireNotNull(list.findViewHolderForAdapterPosition(0)).itemView as ViewGroup
                        val id = if (item.editTarget is TimelineEditTarget.Sleep) R.id.tvStartTime else R.id.tvTime
                        val text = root.findViewById<TextView>(id)
                        assertEquals(if (item.editTarget is TimelineEditTarget.Event) "23:00" else "2025-12-31 23:00", text.text.toString())
                        val details = "主题=$themeName，目标=${item.editTarget}"
                        assertFullText(root, text, details)
                        val bounds = Rect(0, 0, text.width, text.height)
                        root.offsetDescendantRectToMyCoords(text, bounds)
                        val icon = root.findViewById<View>(R.id.ivIcon)
                        val iconBounds = Rect(0, 0, icon.width, icon.height)
                        root.offsetDescendantRectToMyCoords(icon, iconBounds)
                        assertTrue("日期不能盖住内容；$details", bounds.bottom <= iconBounds.top)
                        val row = root.findViewById<LinearLayout>(R.id.rowTimelineContent)
                        val textColumn = root.findViewById<View>(R.id.llTextContent)
                        val columnBounds = Rect(0, 0, textColumn.width, textColumn.height)
                        root.offsetDescendantRectToMyCoords(textColumn, columnBounds)
                        val iconSize = root.resources.getDimensionPixelSize(R.dimen.statistics_timeline_icon_size)
                        assertEquals("自适应不能拉伸图标；$details", iconSize, icon.width)
                        assertEquals("自适应不能拉伸图标；$details", iconSize, icon.height)
                        if (fontScale >= 2f) {
                            assertEquals("大字号必须改为纵排；$details", LinearLayout.VERTICAL, row.orientation)
                            assertEquals("大字号正文须使用整行宽度；$details", row.width - row.paddingLeft - row.paddingRight, textColumn.width)
                            assertTrue("纵排正文必须位于图标下方；$details", columnBounds.top >= iconBounds.bottom)
                        } else {
                            assertEquals("默认360dp保留紧凑横排；$details", LinearLayout.HORIZONTAL, row.orientation)
                            assertTrue("横排正文必须位于图标右侧；$details", columnBounds.left >= iconBounds.right)
                        }
                        val title = root.findViewById<TextView>(R.id.tvTitle)
                        assertEquals(item.titleText, title.text.toString())
                        assertFullText(root, title, details)
                        val detailId = if (item.editTarget is TimelineEditTarget.Sleep) R.id.tvDuration else R.id.tvDetail
                        val detail = root.findViewById<TextView>(detailId)
                        assertEquals(item.detailText, detail.text.toString())
                        assertFullText(root, detail, details)
                        assertTrue("标题不能遮挡详情；$details", title.bottom <= detail.top)
                        assertTrue(root.performClick())
                        assertEquals(item.editTarget, selected.last())
                    }
                    val recordType = when (item.editTarget) {
                        is TimelineEditTarget.Sleep -> "sleep"
                        is TimelineEditTarget.Feeding -> "feeding"
                        is TimelineEditTarget.Event -> "event"
                        is TimelineEditTarget.LegacyGrowth -> "legacy-growth"
                    }
                    captureScreenshot(scenario, list, "$themeName-$recordType")
                } finally {
                    scenario.onActivity {
                        if (list.viewTreeObserver.isAlive) list.viewTreeObserver.removeOnPreDrawListener(listener)
                    }
                }
            }
            assertEquals(listOf(TimelineEditTarget.Feeding(9001), TimelineEditTarget.Sleep(9002), TimelineEditTarget.Event(9003)), selected)
        } finally {
            scenario.onActivity {
                host?.adapter = null
                (host?.parent as? ViewGroup)?.removeView(host)
            }
        }
    }

    /** 检查真实排版和绘制边界；TextView.text 保留全文并不代表没有 ellipsis 或裁字。 */
    private fun assertFullText(root: ViewGroup, text: TextView, identity: String) {
        val layout = requireNotNull(text.layout)
        val bounds = Rect(0, 0, text.width, text.height)
        root.offsetDescendantRectToMyCoords(text, bounds)
        val details = "$identity，字段=${text.resources.getResourceEntryName(text.id)}，文字=${text.width}x${text.height}，layout=${layout.width}x${layout.height}，行=${layout.lineCount}，卡片=${root.width}x${root.height}"
        assertEquals("正文必须完整排版；$details", text.text.length, layout.getLineEnd(layout.lineCount - 1))
        val availableWidth = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
        for (line in 0 until layout.lineCount) {
            assertEquals("正文不得省略；$details", 0, layout.getEllipsisCount(line))
            assertTrue("实际文字不能溢出；$details，line=$line，left=${layout.getLineLeft(line)}，right=${layout.getLineRight(line)}",
                layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= availableWidth + 1f)
        }
        assertTrue("正文不能横向越界；$details", bounds.left >= root.paddingLeft && bounds.right <= root.width - root.paddingRight)
        assertTrue("正文必须完整位于卡片内；$details", bounds.top >= root.paddingTop && bounds.bottom <= root.height - root.paddingBottom)
        assertTrue("每行必须完整显示；$details", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
    }

    /** 仅按显式测试参数保存合成列表，主线程绘制、测试线程写文件，不采集真实宝宝界面。 */
    private fun captureScreenshot(scenario: ActivityScenario<MainActivity>, host: RecyclerView, name: String) {
        if (!InstrumentationRegistry.getArguments().getString("captureScreenshots").toBoolean()) return
        lateinit var bitmap: Bitmap
        scenario.onActivity {
            val row = requireNotNull(host.findViewHolderForAdapterPosition(0)).itemView
            bitmap = Bitmap.createBitmap(host.width, row.bottom.coerceIn(1, host.height), Bitmap.Config.ARGB_8888)
            host.draw(Canvas(bitmap))
        }
        try {
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "audit-screens")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "phase9-timeline-$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun hasPendingLayout(view: View): Boolean = view.visibility != View.GONE &&
        (view.isLayoutRequested || (view is ViewGroup && (0 until view.childCount).any { hasPendingLayout(view.getChildAt(it)) }))

    private fun awaitInitialData(scenario: ActivityScenario<MainActivity>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value }
            if (ready) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("宝宝首帧尚未完成")
    }
}
