package com.zero.babycare.statistics.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.zero.babycare.statistics.mapper.StatisticsDateRange
import com.zero.common.ext.getThemeColor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 宝宝日历控件
 * 支持周视图和月视图切换
 *
 * 修复点（核心逻辑）：
 * 1) 点击只走 GestureDetector，避免一次点击处理两次
 * 2) “是否本月”判断改为 YearMonth 级别（年+月）
 * 3) animatedRowCount 直接参与高度测量，避免展开/折叠时按整数行跳变
 * 4) 周起始日统一使用 WeekFields(firstDayOfWeek)，月补齐/周起始/标题周数一致
 * 5) onMeasure 尊重 MeasureSpec
 * 6) detach 时 cancel animator
 */
class BabyCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val WEEK_ROWS = 1
        private const val MAX_MONTH_ROWS = 6
        private const val DAYS_IN_WEEK = 7
        private const val EXPAND_DURATION = 300L
        private const val DATE_ROW_HEIGHT_FACTOR = 0.72f
        private const val MIN_DATE_CELL_HEIGHT_DP = 48f
    }

    enum class ViewMode { WEEK, MONTH }

    // ==================== 画笔 ====================
    private val weekDayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val selectedBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val todayBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ==================== 颜色 ====================
    private var weekDayTextColor: Int = 0
    private var dateTextColor: Int = 0
    private var selectedTextColor: Int = 0
    private var todayTextColor: Int = 0
    private var otherMonthTextColor: Int = 0
    private var selectedBgColor: Int = 0
    private var todayBgColor: Int = 0
    private var dotColor: Int = 0

    // ==================== 尺寸 ====================
    private var weekDayTextSize: Float = 0f
    private var dateTextSize: Float = 0f
    private var cellWidth: Float = 0f
    private var cellHeight: Float = 0f
    private var weekDayHeight: Float = 0f
    private var dotRadius: Float = 0f
    private var recordDotTextGap: Float = 0f
    private var selectedRadius: Float = 0f

    // ==================== 周规则（统一） ====================
    private val weekFields: WeekFields = StatisticsDateRange.statisticsWeekFields()
    private val firstDayOfWeek: DayOfWeek = weekFields.firstDayOfWeek

    // ==================== 状态 ====================
    private var currentMode: ViewMode = ViewMode.WEEK
    private var selectedDate: LocalDate = LocalDate.now()  // 用户选中的日期
    private var displayMonth: YearMonth = YearMonth.now()  // 月视图显示的月份
    private var displayWeekStart: LocalDate = LocalDate.now()  // 周视图显示的周起始日
    private var datesWithRecords: Set<LocalDate> = emptySet()

    // 动画相关
    private var animatedRowCount: Float = WEEK_ROWS.toFloat()
    private var expandAnimator: ValueAnimator? = null
    private var transitionDates: List<LocalDate>? = null

    /**
     * 是否在翻月时自动调整 selectedDate 到新月份（可选，默认保持原行为：不调整）
     * - true：翻到新月份后，把 selectedDate 调整到该月（尽量保持日号，越界则取月末）
     * - false：只变 displayMonth，不动 selectedDate
     */
    var adjustSelectionOnNavigate: Boolean = false

    // ==================== 监听器 ====================
    private var onDateSelectedListener: ((LocalDate) -> Unit)? = null
    private var onMonthChangedListener: ((YearMonth) -> Unit)? = null
    private var onModeChangedListener: ((ViewMode) -> Unit)? = null

    // ==================== 手势检测 ====================
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // ✅ 只在这里处理点击，避免 ACTION_UP 再处理一次
                val handled = handleClick(e.x, e.y)
                if (handled) performClick()
                return handled
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val deltaX = e2.x - e1.x
                val deltaY = e2.y - e1.y
                val absX = abs(deltaX)
                val absY = abs(deltaY)

                // 用系统阈值/速度做基本兜底，避免硬编码像素
                val horizontal = absX > absY && absX > touchSlop * 4 && abs(velocityX) > minFlingVelocity
                val vertical = absY > absX && absY > touchSlop * 3 && abs(velocityY) > minFlingVelocity

                if (horizontal) {
                    if (deltaX > 0) navigatePrevious() else navigateNext()
                    return true
                }

                if (vertical) {
                    if (deltaY > 0 && currentMode == ViewMode.WEEK) {
                        setViewMode(ViewMode.MONTH)
                        return true
                    } else if (deltaY < 0 && currentMode == ViewMode.MONTH) {
                        setViewMode(ViewMode.WEEK)
                        return true
                    }
                }

                return false
            }
        }
    )

    // ==================== 星期标题（按 firstDayOfWeek 排序） ====================
    private val weekDayLabels: Array<String> = buildWeekDayLabels(firstDayOfWeek)
    private val accessibilityHelper = CalendarAccessibilityHelper()

    init {
        initColors()
        initSizes()
        // 初始化 displayWeekStart：与周规则一致
        displayWeekStart = selectedDate.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        isFocusable = true
        isFocusableInTouchMode = true
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    private fun initColors() {
        weekDayTextColor = getThemeColor(com.zero.common.R.attr.colorTextSecondary)
        dateTextColor = getThemeColor(com.zero.common.R.attr.colorTextPrimary)
        selectedTextColor = getThemeColor(com.zero.common.R.attr.colorBrandForeground)
        todayTextColor = selectedTextColor
        otherMonthTextColor = getThemeColor(com.zero.common.R.attr.colorTextHint)
        selectedBgColor = ContextCompat.getColor(context, com.zero.common.R.color.control_surface_selected)
        todayBgColor = selectedTextColor
        dotColor = getThemeColor(com.zero.common.R.attr.colorTextSecondary)
        todayBgPaint.strokeWidth = resources.getDimension(com.zero.common.R.dimen.surface_stroke_width)
    }

    private fun initSizes() {
        val density = resources.displayMetrics.density
        // 自绘文字同样从公共 sp 样式取值，保留系统字号与字体族设置。
        applyTextAppearance(weekDayPaint, com.zero.common.R.style.TextAppearance_BabyCare_Label)
        applyTextAppearance(datePaint, com.zero.common.R.style.TextAppearance_BabyCare_Body2)
        weekDayTextSize = weekDayPaint.textSize
        dateTextSize = datePaint.textSize
        weekDayHeight = max(24 * density, weekDayPaint.fontSpacing + 8 * density)
        dotRadius = 2.5f * density
        recordDotTextGap = 4 * density
        selectedRadius = max(14 * density, datePaint.fontSpacing / 2 + 4 * density)
    }

    private fun applyTextAppearance(paint: Paint, style: Int) {
        val appearance = context.obtainStyledAttributes(
            style, androidx.appcompat.R.styleable.TextAppearance
        )
        try {
            paint.textSize = appearance.getDimension(
                androidx.appcompat.R.styleable.TextAppearance_android_textSize, paint.textSize
            )
            paint.typeface = Typeface.create(
                appearance.getString(androidx.appcompat.R.styleable.TextAppearance_android_fontFamily),
                appearance.getInt(androidx.appcompat.R.styleable.TextAppearance_android_textStyle, Typeface.NORMAL)
            )
        } finally {
            appearance.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val measuredWidth = when (widthMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> widthSize
            else -> suggestedMinimumWidth
        }

        // 绘制和点击都基于 View 的 padding 内部区域，避免日历自绘内容越过统计主区统一边界。
        val contentWidth = max(0, measuredWidth - paddingLeft - paddingRight)
        cellWidth = contentWidth / DAYS_IN_WEEK.toFloat()
        // 日期行至少 48dp 高，保证自绘日期的纵向点击区域可稳定命中。
        cellHeight = max(
            cellWidth * DATE_ROW_HEIGHT_FACTOR,
            max(
                MIN_DATE_CELL_HEIGHT_DP * resources.displayMetrics.density,
                max(selectedRadius * 2, dateTextSize + 2 * (recordDotTextGap + dotRadius)) +
                    8 * resources.displayMetrics.density
            )
        )

        val desiredHeight = (paddingTop + weekDayHeight + cellHeight * animatedRowCount + paddingBottom).toInt()
        val measuredHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> min(desiredHeight, heightSize)
            else -> desiredHeight
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        accessibilityHelper.invalidateRoot()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawWeekDayLabels(canvas)
        drawDates(canvas)
    }

    private fun drawWeekDayLabels(canvas: Canvas) {
        weekDayPaint.textSize = weekDayTextSize
        weekDayPaint.color = weekDayTextColor

        val contentLeft = paddingLeft.toFloat()
        val y = paddingTop + weekDayHeight / 2 -
            (weekDayPaint.fontMetrics.ascent + weekDayPaint.fontMetrics.descent) / 2
        for (i in 0 until DAYS_IN_WEEK) {
            val x = contentLeft + cellWidth * i + cellWidth / 2
            canvas.drawText(weekDayLabels[i], x, y, weekDayPaint)
        }
    }

    private fun drawDates(canvas: Canvas) {
        val dates = transitionDates ?: getDisplayDates()
        val today = LocalDate.now()

        datePaint.textSize = dateTextSize

        val contentLeft = paddingLeft.toFloat()
        val weekModeWithoutTransition = transitionDates == null && currentMode == ViewMode.WEEK
        for ((index, date) in dates.withIndex()) {
            val row = index / DAYS_IN_WEEK

            val col = index % DAYS_IN_WEEK
            val centerX = contentLeft + cellWidth * col + cellWidth / 2
            val centerY = paddingTop + weekDayHeight + cellHeight * row + cellHeight / 2

            val isSelected = date == selectedDate
            val isToday = date == today
            val isCurrentMonth = YearMonth.from(date) == displayMonth
            val hasRecord = datesWithRecords.contains(date)
            val shouldDrawRecordDot = hasRecord && !isSelected && !isToday

            if (isSelected) {
                selectedBgPaint.color = selectedBgColor
                canvas.drawCircle(centerX, centerY, selectedRadius, selectedBgPaint)
            }
            if (isSelected || isToday) {
                todayBgPaint.color = todayBgColor
                canvas.drawCircle(centerX, centerY, selectedRadius, todayBgPaint)
            }

            datePaint.color = when {
                isSelected -> selectedTextColor
                isToday -> todayTextColor
                isCurrentMonth || weekModeWithoutTransition -> dateTextColor
                else -> otherMonthTextColor
            }
            datePaint.isFakeBoldText = isSelected || isToday
            // 今天恰好被选中时仍保留独立提示，不再用第二层底色叠加。
            datePaint.isUnderlineText = isSelected && isToday

            val textY = centerY - (datePaint.fontMetrics.ascent + datePaint.fontMetrics.descent) / 2
            canvas.drawText(date.dayOfMonth.toString(), centerX, textY, datePaint)

            if (index == accessibilityHelper.keyboardFocusedVirtualViewId ||
                index == accessibilityHelper.accessibilityFocusedVirtualViewId
            ) {
                val bounds = dateBounds(index)
                val inset = 2 * resources.displayMetrics.density
                todayBgPaint.color = todayBgColor
                canvas.drawRoundRect(
                    bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset,
                    inset * 2, inset * 2, todayBgPaint
                )
            }

            if (shouldDrawRecordDot) {
                dotPaint.color = dotColor
                // 记录点跟随日期文字，而不是跟随选中圆半径；否则月视图行高紧凑时会贴到下一行状态。
                val dotY = centerY + dateTextSize / 2 + recordDotTextGap
                canvas.drawCircle(centerX, dotY, dotRadius, dotPaint)
            }
        }
    }

    private fun getDisplayDates(): List<LocalDate> {
        return when (currentMode) {
            ViewMode.WEEK -> getWeekDates()
            ViewMode.MONTH -> getMonthDates()
        }
    }

    private fun getWeekDates(): List<LocalDate> {
        return (0 until DAYS_IN_WEEK).map { displayWeekStart.plusDays(it.toLong()) }
    }

    private fun getMonthDates(): List<LocalDate> {
        val firstDayOfMonth = displayMonth.atDay(1)
        val lastDayOfMonth = displayMonth.atEndOfMonth()

        // ✅ 用统一周起始日补齐
        val calendarStart = firstDayOfMonth.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

        val daysFromStartToEnd = ChronoUnit.DAYS.between(calendarStart, lastDayOfMonth) + 1
        val rowCount = ((daysFromStartToEnd + DAYS_IN_WEEK - 1) / DAYS_IN_WEEK).toInt()
        val finalRowCount = rowCount.coerceIn(4, MAX_MONTH_ROWS)

        return (0 until finalRowCount * DAYS_IN_WEEK).map { calendarStart.plusDays(it.toLong()) }
    }

    private fun handleClick(x: Float, y: Float): Boolean {
        val index = dateIndexAt(x, y)
        if (index == ExploreByTouchHelper.INVALID_ID) return false
        return selectVisibleDate(index)
    }

    /** 手势、朗读焦点和键盘共用实际绘制的日期与边界，展开动画期间也不能点错日期。 */
    private fun dateIndexAt(x: Float, y: Float): Int {
        val contentX = x - paddingLeft
        val contentY = y - paddingTop
        if (contentY < weekDayHeight || y >= height - paddingBottom) return ExploreByTouchHelper.INVALID_ID
        if (cellWidth <= 0f || cellHeight <= 0f) return ExploreByTouchHelper.INVALID_ID

        val contentWidth = max(0, width - paddingLeft - paddingRight).toFloat()
        if (contentX < 0f || contentX >= contentWidth) return ExploreByTouchHelper.INVALID_ID

        val col = (contentX / cellWidth).toInt()
        val row = ((contentY - weekDayHeight) / cellHeight).toInt()
        val index = row * DAYS_IN_WEEK + col
        return if (index in displayedDates().indices && !dateBounds(index).isEmpty) index
        else ExploreByTouchHelper.INVALID_ID
    }

    private fun displayedDates(): List<LocalDate> = transitionDates ?: getDisplayDates()

    private fun dateBounds(index: Int): Rect {
        val row = index / DAYS_IN_WEEK
        val column = index % DAYS_IN_WEEK
        val top = (paddingTop + weekDayHeight + row * cellHeight).toInt()
        return Rect(
            (paddingLeft + column * cellWidth).toInt(),
            top,
            (paddingLeft + (column + 1) * cellWidth).toInt(),
            min((paddingTop + weekDayHeight + (row + 1) * cellHeight).toInt(), height - paddingBottom)
        ).apply { if (bottom <= top) setEmpty() }
    }

    private fun selectVisibleDate(index: Int): Boolean {
        if (!isEnabled || dateBounds(index).isEmpty) return false
        val date = displayedDates().getOrNull(index) ?: return false
        // 动画内选择使用当时可见日期，并立即结束过渡以同步命中区、行高和朗读内容。
        expandAnimator?.cancel()
        transitionDates = null
        if (currentMode == ViewMode.WEEK) animatedRowCount = WEEK_ROWS.toFloat()
        setSelectedDate(date, notify = true)
        requestLayout()
        val selectedIndex = displayedDates().indexOf(date)
        if (accessibilityHelper.keyboardFocusedVirtualViewId != ExploreByTouchHelper.INVALID_ID) {
            accessibilityHelper.requestKeyboardFocusForVirtualView(selectedIndex)
        }
        accessibilityHelper.sendEventForVirtualView(selectedIndex, AccessibilityEvent.TYPE_VIEW_CLICKED)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        // ✅ 不再在 ACTION_UP 二次处理点击
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        accessibilityHelper.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        accessibilityHelper.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    /** 自绘网格的每一天都是独立按钮节点，选择会走与触摸相同的页面回调。 */
    private inner class CalendarAccessibilityHelper : ExploreByTouchHelper(this@BabyCalendarView) {
        override fun getVirtualViewAt(x: Float, y: Float): Int = dateIndexAt(x, y)

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            displayedDates().indices.filterTo(virtualViewIds) { !dateBounds(it).isEmpty }
        }

        override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
            node.className = "android.widget.GridView"
            node.setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(
                displayedDates().size / DAYS_IN_WEEK, DAYS_IN_WEEK, false,
                AccessibilityNodeInfoCompat.CollectionInfoCompat.SELECTION_MODE_SINGLE
            ))
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val date = displayedDates().getOrNull(virtualViewId)
            if (date == null) {
                // 月份切换后系统可能短暂请求旧节点；提供空边界避免暴露已消失日期。
                node.contentDescription = ""
                node.setBoundsInParent(Rect())
                node.isEnabled = false
                return
            }
            var description = date.format(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(resources.configuration.locales[0])
            )
            if (date == LocalDate.now()) {
                description = context.getString(com.zero.common.R.string.calendar_today_date_format, description)
            }
            if (date in datesWithRecords) {
                description = context.getString(com.zero.common.R.string.calendar_recorded_date_format, description)
            }
            node.contentDescription = description
            node.className = "android.widget.Button"
            node.isEnabled = isEnabled
            node.isSelected = date == selectedDate
            node.isClickable = isEnabled
            node.setBoundsInParent(dateBounds(virtualViewId))
            node.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(
                virtualViewId / DAYS_IN_WEEK, 1, virtualViewId % DAYS_IN_WEEK, 1, false, date == selectedDate
            ))
            if (isEnabled) node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean =
            action == AccessibilityNodeInfoCompat.ACTION_CLICK && selectVisibleDate(virtualViewId)

        override fun onVirtualViewKeyboardFocusChanged(virtualViewId: Int, hasFocus: Boolean) {
            invalidate()
        }
    }

    // ==================== 公开方法 ====================

    fun setSelectedDate(date: LocalDate, notify: Boolean = true) {
        val dateChanged = date != selectedDate
        selectedDate = date

        if (currentMode == ViewMode.MONTH) {
            displayMonth = YearMonth.from(date)
        }

        if (currentMode == ViewMode.WEEK) {
            displayWeekStart = date.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        }

        if (currentMode == ViewMode.MONTH) {
            val targetRows = getMonthDates().size / DAYS_IN_WEEK
            val target = targetRows.toFloat()
            if (target != animatedRowCount) {
                animatedRowCount = target
                requestLayout()
            }
        }

        invalidate()

        accessibilityHelper.invalidateRoot()
        if (notify && dateChanged) {
            onDateSelectedListener?.invoke(date)
        }
    }

    fun getSelectedDate(): LocalDate = selectedDate

    fun setDatesWithRecords(dates: Set<LocalDate>) {
        datesWithRecords = dates
        invalidate()
        accessibilityHelper.invalidateRoot()
    }

    fun setViewMode(mode: ViewMode, animate: Boolean = true) {
        if (mode == currentMode) return
        val previousMode = currentMode
        val collapseTransitionDates = if (animate && previousMode == ViewMode.MONTH && mode == ViewMode.WEEK) {
            getMonthDates()
        } else {
            null
        }
        currentMode = mode

        if (mode == ViewMode.MONTH) {
            displayMonth = YearMonth.from(selectedDate)
            onMonthChangedListener?.invoke(displayMonth)
        } else {
            displayWeekStart = selectedDate.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        }

        onModeChangedListener?.invoke(mode)

        val targetRows = when (mode) {
            ViewMode.WEEK -> WEEK_ROWS
            ViewMode.MONTH -> getMonthDates().size / DAYS_IN_WEEK
        }

        if (animate) {
            // 折叠时保留月视图日期并交给高度裁剪，避免内容先消失再缩短造成空白跳动。
            transitionDates = collapseTransitionDates
            animateRowChange(targetRows.toFloat()) {
                transitionDates = null
                accessibilityHelper.invalidateRoot()
            }
        } else {
            transitionDates = null
            animatedRowCount = targetRows.toFloat()
            requestLayout()
            invalidate()
        }
        accessibilityHelper.invalidateRoot()
    }

    fun getViewMode(): ViewMode = currentMode

    fun toggleViewMode() {
        setViewMode(if (currentMode == ViewMode.WEEK) ViewMode.MONTH else ViewMode.WEEK)
    }

    fun navigatePrevious() {
        when (currentMode) {
            ViewMode.WEEK -> {
                displayWeekStart = displayWeekStart.minusWeeks(1)
                invalidate()
            }
            ViewMode.MONTH -> {
                displayMonth = displayMonth.minusMonths(1)
                transitionDates = null

                val targetRows = getMonthDates().size / DAYS_IN_WEEK
                animatedRowCount = targetRows.toFloat()
                requestLayout()

                if (adjustSelectionOnNavigate) {
                    val desiredDay = selectedDate.dayOfMonth
                    val newDay = min(desiredDay, displayMonth.lengthOfMonth())
                    setSelectedDate(displayMonth.atDay(newDay), notify = true)
                }

                onMonthChangedListener?.invoke(displayMonth)
                invalidate()
            }
        }
        accessibilityHelper.invalidateRoot()
    }

    fun navigateNext() {
        when (currentMode) {
            ViewMode.WEEK -> {
                displayWeekStart = displayWeekStart.plusWeeks(1)
                invalidate()
            }
            ViewMode.MONTH -> {
                displayMonth = displayMonth.plusMonths(1)
                transitionDates = null

                val targetRows = getMonthDates().size / DAYS_IN_WEEK
                animatedRowCount = targetRows.toFloat()
                requestLayout()

                if (adjustSelectionOnNavigate) {
                    val desiredDay = selectedDate.dayOfMonth
                    val newDay = min(desiredDay, displayMonth.lengthOfMonth())
                    setSelectedDate(displayMonth.atDay(newDay), notify = true)
                }

                onMonthChangedListener?.invoke(displayMonth)
                invalidate()
            }
        }
        accessibilityHelper.invalidateRoot()
    }

    fun goToToday() {
        setSelectedDate(LocalDate.now())
    }

    fun getDisplayMonth(): YearMonth = displayMonth

    /**
     * 获取格式化的标题（如 "2024年1月 第2周"）
     * 周视图：用“本周中间那天”决定月份与周数，避免跨月周标题跳回上个月。
     */
    fun getFormattedTitle(): String {
        val monthFormatter = DateTimeFormatter.ofPattern(
            context.getString(com.zero.common.R.string.calendar_month_title_pattern),
            Locale.getDefault()
        )
        return when (currentMode) {
            ViewMode.WEEK -> {
                val midDate = displayWeekStart.plusDays(3) // 7 天中的中间值
                val ym = YearMonth.from(midDate)
                val monthStr = ym.format(monthFormatter)
                val weekOfMonth = midDate.get(weekFields.weekOfMonth())
                context.getString(com.zero.common.R.string.calendar_week_title_format, monthStr, weekOfMonth)
            }
            ViewMode.MONTH -> displayMonth.format(monthFormatter)
        }
    }

    fun setOnDateSelectedListener(listener: (LocalDate) -> Unit) {
        onDateSelectedListener = listener
    }

    fun setOnMonthChangedListener(listener: (YearMonth) -> Unit) {
        onMonthChangedListener = listener
    }

    fun setOnModeChangedListener(listener: (ViewMode) -> Unit) {
        onModeChangedListener = listener
    }

    private fun animateRowChange(targetRows: Float, onEnd: () -> Unit = {}) {
        expandAnimator?.cancel()
        expandAnimator = ValueAnimator.ofFloat(animatedRowCount, targetRows).apply {
            duration = EXPAND_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                animatedRowCount = animator.animatedValue as Float
                requestLayout()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    if (expandAnimator === animation) {
                        expandAnimator = null
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (expandAnimator === animation) {
                        animatedRowCount = targetRows
                        expandAnimator = null
                        onEnd()
                        requestLayout()
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    override fun performClick(): Boolean {
        // 将手势点击同步给无障碍服务；日期选择本身仍由带坐标的手势处理。
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        expandAnimator?.cancel()
        expandAnimator = null
        transitionDates = null
    }

    private fun buildWeekDayLabels(first: DayOfWeek): Array<String> {
        // 星期缩写按资源读取，再根据 firstDayOfWeek 旋转，避免应用内语言切换后仍显示中文。
        val base = listOf(
            context.getString(com.zero.common.R.string.calendar_weekday_sunday_short),
            context.getString(com.zero.common.R.string.calendar_weekday_monday_short),
            context.getString(com.zero.common.R.string.calendar_weekday_tuesday_short),
            context.getString(com.zero.common.R.string.calendar_weekday_wednesday_short),
            context.getString(com.zero.common.R.string.calendar_weekday_thursday_short),
            context.getString(com.zero.common.R.string.calendar_weekday_friday_short),
            context.getString(com.zero.common.R.string.calendar_weekday_saturday_short)
        )
        // DayOfWeek: MONDAY=1 ... SUNDAY=7；base: 0=日 ... 6=六
        val firstIndexInBase = when (first) {
            DayOfWeek.SUNDAY -> 0
            DayOfWeek.MONDAY -> 1
            DayOfWeek.TUESDAY -> 2
            DayOfWeek.WEDNESDAY -> 3
            DayOfWeek.THURSDAY -> 4
            DayOfWeek.FRIDAY -> 5
            DayOfWeek.SATURDAY -> 6
        }
        return Array(DAYS_IN_WEEK) { i -> base[(firstIndexInBase + i) % DAYS_IN_WEEK] }
    }
}
