package com.zero.components.base.util

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.zero.components.R
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * BabyCare 统一日期/时间 Sheet。
 *
 * 组件只负责产出时间值，跨天、未来时间等业务约束仍由调用方或 ViewModel 处理。
 */
internal class BabyCareDateTimePopup(
    context: Context,
    title: String,
    private val mode: DialogHelper.DateTimeMode,
    initialTime: Long,
    private val minTime: Long? = null,
    private val maxTime: Long? = null,
    private val yearRange: IntRange? = null,
    private val onConfirm: (Long) -> Unit,
    private val onCancel: (() -> Unit)? = null
) : BabyCareBottomSheetPopup(context) {

    private val initialCalendar = calendarOf(clamp(initialTime))
    private var selectedYear = initialCalendar.get(Calendar.YEAR)
    private var selectedMonth = initialCalendar.get(Calendar.MONTH) + 1
    private var selectedDay = initialCalendar.get(Calendar.DAY_OF_MONTH)
    private var selectedHour = initialCalendar.get(Calendar.HOUR_OF_DAY)
    private var selectedMinute = initialCalendar.get(Calendar.MINUTE)
    private var selectedSecond = initialCalendar.get(Calendar.SECOND)
    private var isBindingColumns = false

    private lateinit var dateGroup: View
    private lateinit var timeGroup: View
    private lateinit var yearColumn: PickerColumn
    private lateinit var monthColumn: PickerColumn
    private lateinit var dayColumn: PickerColumn
    private lateinit var hourColumn: PickerColumn
    private lateinit var minuteColumn: PickerColumn
    private lateinit var secondColumn: PickerColumn
    private lateinit var groupGap: View
    private lateinit var contentTouchArea: View

    override fun getImplLayoutId(): Int = R.layout.popup_babycare_date_time_sheet

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::contentTouchArea.isInitialized) {
            updateSheetDragEnabledForContentTouch(event, contentTouchArea)
        }
        val handled = super.dispatchTouchEvent(event)
        restoreSheetDragAfterContentTouch(event)
        return handled
    }

    override fun onCreate() {
        super.onCreate()
        findViewById<TextView>(R.id.tv_cancel).setOnClickListener {
            onCancel?.invoke()
            dismiss()
        }
        findViewById<TextView>(R.id.tv_confirm).setOnClickListener {
            onConfirm(clamp(buildSelectedCalendar().timeInMillis))
            dismiss()
        }

        dateGroup = findViewById(R.id.picker_date_group)
        timeGroup = findViewById(R.id.picker_time_group)
        groupGap = findViewById(R.id.picker_date_time_group_gap)
        contentTouchArea = findViewById(R.id.picker_frame)

        yearColumn = createColumn(findViewById(R.id.list_year))
        monthColumn = createColumn(findViewById(R.id.list_month))
        dayColumn = createColumn(findViewById(R.id.list_day))
        hourColumn = createColumn(findViewById(R.id.list_hour))
        minuteColumn = createColumn(findViewById(R.id.list_minute))
        secondColumn = createColumn(findViewById(R.id.list_second))
        applyModeVisibility()
        bindAllColumns()
    }

    private fun createColumn(recyclerView: RecyclerView): PickerColumn {
        val layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        val adapter = PickerColumnAdapter(context)
        val snapHelper = LinearSnapHelper()
        val column = PickerColumn(
            recyclerView = recyclerView,
            layoutManager = layoutManager,
            adapter = adapter,
            snapHelper = snapHelper
        )

        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = null
        recyclerView.setHasFixedSize(true)
        snapHelper.attachToRecyclerView(recyclerView)

        // 通过上下 padding 把首尾 item 也放到中心选择线内，避免第一项/最后一项无法被选中。
        recyclerView.doOnLayout {
            syncColumnPadding(column)
            scrollColumnToSelected(column)
        }
        recyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncColumnPadding(column)
        }
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    selectSnappedValue(column)
                }
            }
        })
        return column
    }

    private fun applyModeVisibility() {
        val showYear = showYearColumn()
        val showMonthDay = showMonthDayColumns()
        val showTime = showTimeColumns()
        val showSecond = showSecondColumn()
        val showDateGroup = showYear || showMonthDay
        val showTimeGroup = showTime

        dateGroup.visibility = if (showDateGroup) View.VISIBLE else View.GONE
        timeGroup.visibility = if (showTimeGroup) View.VISIBLE else View.GONE
        yearColumn.recyclerView.visibility = if (showYear) View.VISIBLE else View.GONE
        monthColumn.recyclerView.visibility = if (showMonthDay) View.VISIBLE else View.GONE
        dayColumn.recyclerView.visibility = if (showMonthDay) View.VISIBLE else View.GONE
        groupGap.visibility = if (showDateGroup && showTimeGroup) View.VISIBLE else View.GONE
        hourColumn.recyclerView.visibility = if (showTime) View.VISIBLE else View.GONE
        minuteColumn.recyclerView.visibility = if (showTime) View.VISIBLE else View.GONE
        secondColumn.recyclerView.visibility = if (showSecond) View.VISIBLE else View.GONE
        applyColumnGroupLayout()
    }

    private fun showYearColumn(): Boolean {
        return mode == DialogHelper.DateTimeMode.DATE ||
            mode == DialogHelper.DateTimeMode.DATE_TIME ||
            mode == DialogHelper.DateTimeMode.DATE_TIME_SECOND
    }

    private fun showMonthDayColumns(): Boolean {
        return mode == DialogHelper.DateTimeMode.DATE ||
            mode == DialogHelper.DateTimeMode.DATE_TIME ||
            mode == DialogHelper.DateTimeMode.DATE_TIME_SECOND ||
            mode == DialogHelper.DateTimeMode.MONTH_DAY_TIME ||
            mode == DialogHelper.DateTimeMode.MONTH_DAY_TIME_SECOND
    }

    private fun showTimeColumns(): Boolean {
        return mode == DialogHelper.DateTimeMode.TIME ||
            mode == DialogHelper.DateTimeMode.TIME_SECOND ||
            mode == DialogHelper.DateTimeMode.DATE_TIME ||
            mode == DialogHelper.DateTimeMode.DATE_TIME_SECOND ||
            mode == DialogHelper.DateTimeMode.MONTH_DAY_TIME ||
            mode == DialogHelper.DateTimeMode.MONTH_DAY_TIME_SECOND
    }

    private fun showSecondColumn(): Boolean {
        return mode == DialogHelper.DateTimeMode.TIME_SECOND ||
            mode == DialogHelper.DateTimeMode.DATE_TIME_SECOND ||
            mode == DialogHelper.DateTimeMode.MONTH_DAY_TIME_SECOND
    }

    private fun applyColumnGroupLayout() {
        // 参考常见平面时间选择器：组内列保持紧凑，组间留出更明确的空隙。
        // 年月日和时分秒不能继续按整行均分，否则用户很难看出两组信息关系。
        val dateColumns = listOf(
            yearColumn to if (showYearColumn()) YEAR_COLUMN_WIDTH_DP else 0,
            monthColumn to if (showMonthDayColumns()) COMPACT_COLUMN_WIDTH_DP else 0,
            dayColumn to if (showMonthDayColumns()) COMPACT_COLUMN_WIDTH_DP else 0
        )
        val timeColumns = listOf(
            hourColumn to if (showTimeColumns()) COMPACT_COLUMN_WIDTH_DP else 0,
            minuteColumn to if (showTimeColumns()) COMPACT_COLUMN_WIDTH_DP else 0,
            secondColumn to if (showSecondColumn()) COMPACT_COLUMN_WIDTH_DP else 0
        )

        val dateWidthDp = dateColumns.sumOf { it.second }
        val timeWidthDp = timeColumns.sumOf { it.second }
        val hasBothGroups = dateWidthDp > 0 && timeWidthDp > 0
        val desiredGapDp = if (hasBothGroups) {
            if (showYearColumn()) FULL_DATE_TIME_GROUP_GAP_DP else MONTH_DAY_TIME_GROUP_GAP_DP
        } else {
            0
        }
        val desiredTotalPx = dp(dateWidthDp + timeWidthDp + desiredGapDp)
        val availableWidth = (resources.displayMetrics.widthPixels - dp(PICKER_HORIZONTAL_PADDING_DP * 2))
            .coerceAtLeast(dp(MIN_PICKER_AVAILABLE_WIDTH_DP))
        val scale = (availableWidth.toFloat() / desiredTotalPx.toFloat()).coerceAtMost(1f)

        applyColumnWidths(dateColumns, scale)
        applyColumnWidths(timeColumns, scale)
        val gapWidthPx = if (hasBothGroups) (dp(desiredGapDp) * scale).roundToInt() else 0

        setViewWidth(groupGap, gapWidthPx)
    }

    private fun applyColumnWidths(columns: List<Pair<PickerColumn, Int>>, scale: Float): Int {
        var totalWidth = 0
        columns.forEach { (column, widthDp) ->
            if (widthDp == 0) return@forEach
            val widthPx = (dp(widthDp) * scale).roundToInt()
            setViewWidth(column.recyclerView, widthPx)
            totalWidth += widthPx
        }
        return totalWidth
    }

    private fun setViewWidth(view: View, width: Int) {
        val params = view.layoutParams
        if (params.width != width) {
            params.width = width
            view.layoutParams = params
        }
    }

    private fun bindAllColumns() {
        isBindingColumns = true
        bindYearColumn()
        bindMonthColumn()
        bindDayColumn()
        bindHourColumn()
        bindMinuteColumn()
        bindSecondColumn()
        isBindingColumns = false
    }

    private fun bindYearColumn() {
        val values = yearValues()
        selectedYear = selectedYear.coerceIn(values.first(), values.last())
        bindColumn(
            column = yearColumn,
            values = values,
            selectedValue = selectedYear,
            label = { context.getString(com.zero.common.R.string.picker_year_format, it) },
            onSelected = {
                selectedYear = it
                normalizeDateTime()
                bindMonthColumn()
                bindDayColumn()
                bindHourColumn()
                bindMinuteColumn()
                bindSecondColumn()
            }
        )
    }

    private fun bindMonthColumn() {
        val values = monthValues()
        selectedMonth = selectedMonth.coerceIn(values.first(), values.last())
        bindColumn(
            column = monthColumn,
            values = values,
            selectedValue = selectedMonth,
            label = { context.getString(com.zero.common.R.string.picker_month_format, it) },
            onSelected = {
                selectedMonth = it
                normalizeDateTime()
                bindDayColumn()
                bindHourColumn()
                bindMinuteColumn()
                bindSecondColumn()
            }
        )
    }

    private fun bindDayColumn() {
        val values = dayValues()
        selectedDay = selectedDay.coerceIn(values.first(), values.last())
        bindColumn(
            column = dayColumn,
            values = values,
            selectedValue = selectedDay,
            label = { context.getString(com.zero.common.R.string.picker_day_format, it) },
            onSelected = {
                selectedDay = it
                normalizeDateTime()
                bindHourColumn()
                bindMinuteColumn()
                bindSecondColumn()
            }
        )
    }

    private fun bindHourColumn() {
        val values = hourValues()
        selectedHour = selectedHour.coerceIn(values.first(), values.last())
        bindColumn(
            column = hourColumn,
            values = values,
            selectedValue = selectedHour,
            label = { context.getString(com.zero.common.R.string.picker_hour_format, it) },
            onSelected = {
                selectedHour = it
                normalizeDateTime()
                bindMinuteColumn()
                bindSecondColumn()
            }
        )
    }

    private fun bindMinuteColumn() {
        val values = minuteValues()
        selectedMinute = selectedMinute.coerceIn(values.first(), values.last())
        bindColumn(
            column = minuteColumn,
            values = values,
            selectedValue = selectedMinute,
            label = { context.getString(com.zero.common.R.string.picker_minute_format, it) },
            onSelected = {
                selectedMinute = it
                normalizeDateTime()
                bindSecondColumn()
            }
        )
    }

    private fun bindSecondColumn() {
        val values = secondValues()
        selectedSecond = selectedSecond.coerceIn(values.first(), values.last())
        bindColumn(
            column = secondColumn,
            values = values,
            selectedValue = selectedSecond,
            label = { context.getString(com.zero.common.R.string.picker_second_format, it) },
            onSelected = {
                selectedSecond = it
                normalizeDateTime()
            }
        )
    }

    private fun bindColumn(
        column: PickerColumn,
        values: List<Int>,
        selectedValue: Int,
        label: (Int) -> String,
        onSelected: (Int) -> Unit
    ) {
        val selectedIndex = values.indexOf(selectedValue).coerceAtLeast(0)
        column.values = values
        column.selectedIndex = selectedIndex
        column.onSelected = onSelected
        column.adapter.submitValues(values.map(label), selectedIndex)
        scrollColumnToSelected(column)
    }

    private fun syncColumnPadding(column: PickerColumn) {
        val verticalPadding = ((column.recyclerView.height - dp(PICKER_ITEM_HEIGHT_DP)) / 2).coerceAtLeast(0)
        if (column.recyclerView.paddingTop != verticalPadding) {
            column.recyclerView.setPadding(0, verticalPadding, 0, verticalPadding)
        }
    }

    private fun scrollColumnToSelected(column: PickerColumn) {
        column.recyclerView.post {
            syncColumnPadding(column)
            column.layoutManager.scrollToPositionWithOffset(column.selectedIndex, 0)
        }
    }

    private fun selectSnappedValue(column: PickerColumn) {
        val snapView = column.snapHelper.findSnapView(column.layoutManager) ?: return
        val position = column.layoutManager.getPosition(snapView)
        if (position == RecyclerView.NO_POSITION || position == column.selectedIndex) return

        column.selectedIndex = position
        column.adapter.setSelectedIndex(position)
        if (isBindingColumns) return

        // 只有用户滚动产生的新值才回写状态；联动刷新列时只更新展示，避免递归触发。
        val callback = column.onSelected ?: return
        column.values.getOrNull(position)?.let(callback)
    }

    private fun normalizeDateTime() {
        selectedMonth = selectedMonth.coerceIn(monthValues().first(), monthValues().last())
        selectedDay = selectedDay.coerceIn(dayValues().first(), dayValues().last())
        selectedHour = selectedHour.coerceIn(hourValues().first(), hourValues().last())
        selectedMinute = selectedMinute.coerceIn(minuteValues().first(), minuteValues().last())
        selectedSecond = selectedSecond.coerceIn(secondValues().first(), secondValues().last())
    }

    private fun yearValues(): List<Int> {
        val minYear = minCalendar()?.get(Calendar.YEAR)
        val maxYear = maxCalendar()?.get(Calendar.YEAR)
        val start = yearRange?.first ?: minYear ?: (selectedYear - DEFAULT_YEAR_LOOKBACK)
        val end = yearRange?.last ?: maxYear ?: selectedYear
        val normalizedStart = minOf(start, selectedYear)
        val normalizedEnd = maxOf(end, selectedYear)
        return (normalizedStart..normalizedEnd).toList()
    }

    private fun monthValues(): List<Int> {
        if (!showMonthDayColumns()) return (1..12).toList()
        val start = if (selectedYear == minCalendar()?.get(Calendar.YEAR)) {
            minCalendar()?.get(Calendar.MONTH)?.plus(1) ?: 1
        } else {
            1
        }
        val end = if (selectedYear == maxCalendar()?.get(Calendar.YEAR)) {
            maxCalendar()?.get(Calendar.MONTH)?.plus(1) ?: 12
        } else {
            12
        }
        return (start..end).toList()
    }

    private fun dayValues(): List<Int> {
        if (!showMonthDayColumns()) return (1..31).toList()
        val maxDay = Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)

        val start = if (sameYearMonth(minCalendar())) {
            minCalendar()?.get(Calendar.DAY_OF_MONTH) ?: 1
        } else {
            1
        }
        val end = if (sameYearMonth(maxCalendar())) {
            maxCalendar()?.get(Calendar.DAY_OF_MONTH) ?: maxDay
        } else {
            maxDay
        }
        return (start..end).toList()
    }

    private fun hourValues(): List<Int> {
        if (!showTimeColumns()) return listOf(selectedHour)
        val start = if (sameYearMonthDay(minCalendar())) minCalendar()?.get(Calendar.HOUR_OF_DAY) ?: 0 else 0
        val end = if (sameYearMonthDay(maxCalendar())) maxCalendar()?.get(Calendar.HOUR_OF_DAY) ?: 23 else 23
        return (start..end).toList()
    }

    private fun minuteValues(): List<Int> {
        if (!showTimeColumns()) return listOf(selectedMinute)
        val start = if (sameYearMonthDayHour(minCalendar())) minCalendar()?.get(Calendar.MINUTE) ?: 0 else 0
        val end = if (sameYearMonthDayHour(maxCalendar())) maxCalendar()?.get(Calendar.MINUTE) ?: 59 else 59
        return (start..end).toList()
    }

    private fun secondValues(): List<Int> {
        if (!showSecondColumn()) return listOf(selectedSecond)
        val start = if (sameYearMonthDayHourMinute(minCalendar())) minCalendar()?.get(Calendar.SECOND) ?: 0 else 0
        val end = if (sameYearMonthDayHourMinute(maxCalendar())) maxCalendar()?.get(Calendar.SECOND) ?: 59 else 59
        return (start..end).toList()
    }

    private fun sameYearMonth(calendar: Calendar?): Boolean {
        return calendar != null &&
            selectedYear == calendar.get(Calendar.YEAR) &&
            selectedMonth == calendar.get(Calendar.MONTH) + 1
    }

    private fun sameYearMonthDay(calendar: Calendar?): Boolean {
        return calendar != null && sameYearMonth(calendar) && selectedDay == calendar.get(Calendar.DAY_OF_MONTH)
    }

    private fun sameYearMonthDayHour(calendar: Calendar?): Boolean {
        return calendar != null && sameYearMonthDay(calendar) && selectedHour == calendar.get(Calendar.HOUR_OF_DAY)
    }

    private fun sameYearMonthDayHourMinute(calendar: Calendar?): Boolean {
        return calendar != null && sameYearMonthDayHour(calendar) && selectedMinute == calendar.get(Calendar.MINUTE)
    }

    private fun buildSelectedCalendar(): Calendar {
        return Calendar.getInstance().apply {
            timeInMillis = initialCalendar.timeInMillis
            if (showMonthDayColumns()) {
                set(Calendar.YEAR, selectedYear)
                set(Calendar.MONTH, selectedMonth - 1)
                set(Calendar.DAY_OF_MONTH, selectedDay)
            }
            if (showTimeColumns()) {
                set(Calendar.HOUR_OF_DAY, selectedHour)
                set(Calendar.MINUTE, selectedMinute)
                set(Calendar.SECOND, if (showSecondColumn()) selectedSecond else 0)
            } else {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun minCalendar(): Calendar? = minTime?.let(::calendarOf)

    private fun maxCalendar(): Calendar? = maxTime?.let(::calendarOf)

    private fun calendarOf(time: Long): Calendar {
        return Calendar.getInstance().apply {
            timeInMillis = time
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun clamp(time: Long): Long {
        val min = minTime
        val max = maxTime
        return when {
            min != null && time < min -> min
            max != null && time > max -> max
            else -> time
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private data class PickerColumn(
        val recyclerView: RecyclerView,
        val layoutManager: LinearLayoutManager,
        val adapter: PickerColumnAdapter,
        val snapHelper: LinearSnapHelper,
        var values: List<Int> = emptyList(),
        var selectedIndex: Int = 0,
        var onSelected: ((Int) -> Unit)? = null
    )

    private data class PickerColumnItem(val label: String)

    private class PickerColumnAdapter(
        viewContext: Context
    ) : BaseQuickAdapter<PickerColumnItem, PickerColumnAdapter.ViewHolder>() {

        private var selectedIndex: Int = 0
        private val primaryColor = viewContext.resolveThemeColor(com.zero.common.R.attr.colorTextPrimary)
        private val secondaryColor = viewContext.resolveThemeColor(com.zero.common.R.attr.colorTextSecondary)
        private val hintColor = viewContext.resolveThemeColor(com.zero.common.R.attr.colorTextHint)

        fun submitValues(labels: List<String>, selectedIndex: Int) {
            this.selectedIndex = selectedIndex
            submitList(labels.map(::PickerColumnItem))
        }

        fun setSelectedIndex(index: Int) {
            val oldIndex = selectedIndex
            selectedIndex = index
            if (oldIndex in items.indices) notifyItemChanged(oldIndex)
            if (index in items.indices) notifyItemChanged(index)
        }

        override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): ViewHolder {
            val textView = TextView(context).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                TextViewCompat.setTextAppearance(this, com.zero.common.R.style.TextAppearance_BabyCare_Body1)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, DATE_TIME_ITEM_TEXT_SIZE_SP)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (PICKER_ITEM_HEIGHT_DP * resources.displayMetrics.density).toInt()
                )
            }
            return ViewHolder(textView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int, item: PickerColumnItem?) {
            val distance = abs(position - selectedIndex)
            val isSelected = distance == 0
            holder.textView.text = item?.label.orEmpty()
            holder.textView.setTextColor(
                when {
                    isSelected -> primaryColor
                    distance == 1 -> secondaryColor
                    else -> hintColor
                }
            )
            holder.textView.typeface = Typeface.create(
                if (isSelected) "sans-serif-medium" else "sans-serif",
                Typeface.NORMAL
            )
        }

        class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
    }

    private companion object {
        const val DEFAULT_YEAR_LOOKBACK = 20
        const val PICKER_ITEM_HEIGHT_DP = 54
        const val DATE_TIME_ITEM_TEXT_SIZE_SP = 16f
        const val PICKER_HORIZONTAL_PADDING_DP = 16
        const val MIN_PICKER_AVAILABLE_WIDTH_DP = 320
        const val YEAR_COLUMN_WIDTH_DP = 64
        const val COMPACT_COLUMN_WIDTH_DP = 58
        const val MONTH_DAY_TIME_GROUP_GAP_DP = 56
        const val FULL_DATE_TIME_GROUP_GAP_DP = 32
    }
}
