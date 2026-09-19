package com.zero.babycare

import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.components.widget.RecordTimerPanelView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.roundToInt

/** 用真实资源测量最窄手机和大字号，防止可点击选项或统计标题再次被固定尺寸裁切。 */
@RunWith(AndroidJUnit4::class)
class CoreRecordLargeFontLayoutTest {
    @Test
    fun eventDetailChoicesAndCollapsedNoteKeepWholeText() = onLargeFontContext { context ->
        val inflater = LayoutInflater.from(context)
        val forms = listOf(
            inflater.inflate(R.layout.layout_event_detail_diaper, null) to
                listOf(R.id.rgStoolConsistency, R.id.rgStoolColor, R.id.rgUrineAmount),
            inflater.inflate(R.layout.layout_event_detail_temperature, null) to listOf(R.id.rgTemperatureLocation)
        )
        forms.forEach { (form, groupIds) ->
            groupIds.forEach { id ->
                val group = form.findViewById<RadioGroup>(id)
                measure(group, 256)
                for (index in 0 until group.childCount) {
                    val choice = group.getChildAt(index) as RadioButton
                    assertTextFits(choice)
                    assertTrue("事件详情选项必须完整位于换行容器内", choice.left >= 0 && choice.right <= group.width &&
                        choice.top >= 0 && choice.bottom <= group.height)
                }
            }
        }
        val event = inflater.inflate(R.layout.fragment_event_record, null)
        val noteRow = event.findViewById<ViewGroup>(R.id.noteCollapsedRow)
        measure(noteRow, 256)
        val label = noteRow.getChildAt(1) as TextView
        assertTextFits(label)
        assertTrue("备注折叠入口必须容纳完整提示", label.top >= 0 && label.bottom <= noteRow.height)
    }

    @Test
    fun dashboardCardsPreserveWholeLabelsAcrossFontAndWindowSizes() {
        listOf(1f, 2f).forEach { fontScale ->
            onLargeFontContext(fontScale) { context ->
                val page = LayoutInflater.from(context).inflate(R.layout.fragment_dashboard, null)
                val feeding = page.findViewById<ViewGroup>(R.id.cardFeeding)
                val sleep = page.findViewById<ViewGroup>(R.id.cardSleep)
                val row = feeding.parent as View
                listOf(288, 768, 288).forEach { width ->
                    measure(row, width)
                    listOf(feeding to R.id.tvFeedingLabel, sleep to R.id.tvSleepLabel).forEach { (card, id) ->
                        val label = card.findViewById<TextView>(id)
                        assertTextFits(label)
                        assertTrue("卡片标题不能越过内容边界", label.left >= card.paddingLeft &&
                            label.right <= card.width - card.paddingRight)
                    }
                    if (fontScale == 2f && width == 288) {
                        assertTrue("窄窗大字号双卡必须纵排", sleep.top >= feeding.bottom)
                    } else {
                        assertEquals("普通字号或宽窗双卡保持并排", feeding.top, sleep.top)
                    }
                }
            }
        }
    }

    @Test
    fun feedingChoicesRemainReadableAndExclusiveWhenWindowWidthChanges() = onLargeFontContext { context ->
        val page = LayoutInflater.from(context).inflate(R.layout.fragment_feeding_record, null)
        try {
            val group = page.findViewById<RadioGroup>(R.id.rgFeedingType)
            val choices = (0 until group.childCount).map { group.getChildAt(it) as RadioButton }
            var callbacks = 0
            group.setOnCheckedChangeListener { _, _ -> callbacks++ }
            var narrowHeight = 0
            listOf(260, 800, 260).forEachIndexed { index, width ->
                measure(group, width)
                choices.forEach { choice ->
                    assertTextFits(choice)
                    assertTrue("选项必须保留至少 48dp 触控高度", choice.height >= 48 * context.resources.displayMetrics.density)
                    assertTrue("选项必须位于单选组内", choice.left >= 0 && choice.right <= group.width &&
                        choice.top >= 0 && choice.bottom <= group.height)
                }
                choices.forEachIndexed { first, view ->
                    choices.drop(first + 1).forEach { other ->
                        assertTrue("换行后的选项不能重叠", !Rect.intersects(
                            Rect(view.left, view.top, view.right, view.bottom),
                            Rect(other.left, other.top, other.right, other.bottom)
                        ))
                    }
                }
                if (index == 0) narrowHeight = group.height
                if (index == 1) assertTrue("宽窗应减少选项占用行数", group.height < narrowHeight)
                if (index == 2) assertEquals(narrowHeight, group.height)
            }
            choices.forEach { choice ->
                choice.performClick()
                assertEquals(choice.id, group.checkedRadioButtonId)
                assertEquals(1, choices.count { it.isChecked })
            }
            assertTrue("单选组必须继续分发真实选择事件", callbacks > 0)
            val firstTime = page.findViewById<ViewGroup>(R.id.rowFirstTime)
            measure(firstTime, 260)
            val firstTimeLabel = firstTime.getChildAt(0) as TextView
            assertTextFits(firstTimeLabel)
            assertTrue("首次尝试选项必须随多行文案增高", firstTimeLabel.top >= firstTime.paddingTop &&
                firstTimeLabel.bottom <= firstTime.height - firstTime.paddingBottom)
        } finally {
            page.findViewById<RecordTimerPanelView>(R.id.timerPanel).timerView.release()
        }
    }

    @Test
    fun calendarHeaderAndSummaryDoNotOverlapOrSplitShortCountsAtLargeFont() = onLargeFontContext { context ->
        val calendar = LayoutInflater.from(context).inflate(R.layout.item_statistics_calendar, null) as ViewGroup
        val title = calendar.findViewById<TextView>(R.id.tvCalendarTitle)
        title.text = context.getString(com.zero.common.R.string.calendar_week_title_format, "September 2026", 4)
        measure(calendar, 288)
        val controls = listOf(R.id.ivPrevious, R.id.ivNext, R.id.tvToday).map { calendar.findViewById<View>(it) }
        assertTextFits(title)
        controls.forEach { assertTrue("月份标题不能覆盖日期导航", title.bottom <= it.top) }
        val dateGrid = calendar.findViewById<View>(R.id.calendarView)
        controls.forEach { assertTrue("日期网格不能覆盖日期导航", dateGrid.top >= it.bottom) }

        val summary = LayoutInflater.from(context).inflate(R.layout.item_statistics_summary, null) as ViewGroup
        val countIds = listOf(R.id.tvFeedingCount, R.id.tvSleepDuration, R.id.tvDiaperCount, R.id.tvOtherCount)
        countIds.forEach { summary.findViewById<TextView>(it).text = context.getString(com.zero.common.R.string.times_count_format, 0) }
        measure(summary, 288)
        countIds.forEach { id ->
            val count = summary.findViewById<TextView>(id)
            assertTextFits(count)
            assertEquals("短数量文案应能保持完整一行", 1, count.lineCount)
        }
        val feeding = summary.findViewById<View>(R.id.llFeedingSummary)
        val sleep = summary.findViewById<View>(R.id.llSleepSummary)
        assertTrue("窄窗大字号应把摘要改为纵排", sleep.top >= feeding.bottom)
        measure(summary, 800)
        assertEquals("恢复宽窗后摘要应重新并排", feeding.top, sleep.top)
    }

    private fun onLargeFontContext(fontScale: Float = 2f, block: (ContextThemeWrapper) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val app = instrumentation.targetContext
            val config = Configuration(app.resources.configuration).apply {
                this.fontScale = fontScale
                setLocale(Locale.US)
            }
            block(ContextThemeWrapper(app.createConfigurationContext(config), com.zero.common.R.style.AppTheme_Girl))
        }
    }

    private fun measure(view: View, widthDp: Int) {
        val width = (widthDp * view.resources.displayMetrics.density).roundToInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
    }

    private fun assertTextFits(view: TextView) {
        val layout = requireNotNull(view.layout)
        assertTrue("文字布局必须具有可见内容", layout.lineCount > 0)
        assertEquals("所有文字必须参与排版", view.text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue("文字不能被固定高度裁切", layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
        for (line in 0 until layout.lineCount) assertEquals("文字不能省略", 0, layout.getEllipsisCount(line))
    }
}
