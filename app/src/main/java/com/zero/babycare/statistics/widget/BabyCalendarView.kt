package com.zero.babycare.statistics.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.zero.babycare.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs

/**
 * 宝宝日历控件
 * 支持周视图和月视图切换
 */
class BabyCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ==================== 配置常量 ====================
    companion object {
        private const val WEEK_ROWS = 1
        private const val MAX_MONTH_ROWS = 6
        private const val DAYS_IN_WEEK = 7
        
        // 动画时长
        private const val EXPAND_DURATION = 300L
    }

    // ==================== 视图模式 ====================
    enum class ViewMode {
        WEEK,   // 周视图
        MONTH   // 月视图
    }

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
        strokeWidth = 2f
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
    private var selectedRadius: Float = 0f

    // ==================== 状态 ====================
    private var currentMode: ViewMode = ViewMode.WEEK
    private var selectedDate: LocalDate = LocalDate.now()  // 用户选中的日期（保持不变）
    private var displayMonth: YearMonth = YearMonth.now()  // 月视图显示的月份
    private var displayWeekStart: LocalDate = LocalDate.now()  // 周视图显示的周的开始日期
    private var datesWithRecords: Set<LocalDate> = emptySet()
    
    // 动画相关
    private var animatedRowCount: Float = WEEK_ROWS.toFloat()
    private var expandAnimator: ValueAnimator? = null

    // ==================== 监听器 ====================
    private var onDateSelectedListener: ((LocalDate) -> Unit)? = null
    private var onMonthChangedListener: ((YearMonth) -> Unit)? = null
    private var onModeChangedListener: ((ViewMode) -> Unit)? = null

    // ==================== 手势检测 ====================
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // 处理点击，返回是否成功处理
            return handleClick(e.x, e.y)
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null) return false
            val deltaX = e2.x - e1.x
            val deltaY = e2.y - e1.y
            
            // 水平滑动：切换周/月
            if (abs(deltaX) > abs(deltaY) && abs(deltaX) > 100) {
                if (deltaX > 0) {
                    // 向右滑动 -> 上一周/月
                    navigatePrevious()
                } else {
                    // 向左滑动 -> 下一周/月
                    navigateNext()
                }
                return true
            }
            
            // 垂直滑动：展开/收起
            if (abs(deltaY) > abs(deltaX) && abs(deltaY) > 50) {
                if (deltaY > 0 && currentMode == ViewMode.WEEK) {
                    // 向下滑动 -> 展开月视图
                    setViewMode(ViewMode.MONTH)
                } else if (deltaY < 0 && currentMode == ViewMode.MONTH) {
                    // 向上滑动 -> 收起为周视图
                    setViewMode(ViewMode.WEEK)
                }
                return true
            }
            
            return false
        }
    })

    // ==================== 星期标题 ====================
    private val weekDayLabels = arrayOf("日", "一", "二", "三", "四", "五", "六")

    init {
        initColors()
        initSizes()
        // 初始化显示周的开始日期为当前选中日期所在周
        displayWeekStart = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    }

    private fun initColors() {
        weekDayTextColor = ContextCompat.getColor(context, com.zero.common.R.color.Black40)
        dateTextColor = ContextCompat.getColor(context, com.zero.common.R.color.Black90)
        selectedTextColor = ContextCompat.getColor(context, com.zero.common.R.color.White100)
        todayTextColor = ContextCompat.getColor(context, com.zero.common.R.color.feeding_primary)
        otherMonthTextColor = ContextCompat.getColor(context, com.zero.common.R.color.Black30)
        selectedBgColor = ContextCompat.getColor(context, com.zero.common.R.color.feeding_primary)
        todayBgColor = ContextCompat.getColor(context, com.zero.common.R.color.feeding_primary)
        dotColor = ContextCompat.getColor(context, com.zero.common.R.color.feeding_secondary)
    }

    private fun initSizes() {
        val density = resources.displayMetrics.density
        weekDayTextSize = 12 * density
        dateTextSize = 16 * density
        weekDayHeight = 32 * density
        dotRadius = 3 * density
        selectedRadius = 18 * density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        cellWidth = width / DAYS_IN_WEEK.toFloat()
        cellHeight = cellWidth * 0.9f
        
        val height = (weekDayHeight + cellHeight * animatedRowCount).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        drawWeekDayLabels(canvas)
        drawDates(canvas)
    }

    /**
     * 绘制星期标题
     */
    private fun drawWeekDayLabels(canvas: Canvas) {
        weekDayPaint.textSize = weekDayTextSize
        weekDayPaint.color = weekDayTextColor
        
        val y = weekDayHeight / 2 + weekDayTextSize / 3
        for (i in 0 until DAYS_IN_WEEK) {
            val x = cellWidth * i + cellWidth / 2
            canvas.drawText(weekDayLabels[i], x, y, weekDayPaint)
        }
    }

    /**
     * 绘制日期
     */
    private fun drawDates(canvas: Canvas) {
        val dates = getDisplayDates()
        val today = LocalDate.now()
        
        datePaint.textSize = dateTextSize
        
        for ((index, date) in dates.withIndex()) {
            val row = index / DAYS_IN_WEEK
            val col = index % DAYS_IN_WEEK
            
            // 超出当前动画行数的不绘制
            if (row >= animatedRowCount) continue
            
            val centerX = cellWidth * col + cellWidth / 2
            val centerY = weekDayHeight + cellHeight * row + cellHeight / 2
            
            val isSelected = date == selectedDate
            val isToday = date == today
            val isCurrentMonth = date.month == displayMonth.month
            val hasRecord = datesWithRecords.contains(date)
            
            // 绘制选中背景
            if (isSelected) {
                selectedBgPaint.color = selectedBgColor
                canvas.drawCircle(centerX, centerY, selectedRadius, selectedBgPaint)
            } else if (isToday) {
                // 今天的圆环边框
                todayBgPaint.color = todayBgColor
                canvas.drawCircle(centerX, centerY, selectedRadius - 2, todayBgPaint)
            }
            
            // 绘制日期文字
            datePaint.color = when {
                isSelected -> selectedTextColor
                isToday -> todayTextColor
                isCurrentMonth || currentMode == ViewMode.WEEK -> dateTextColor
                else -> otherMonthTextColor
            }
            datePaint.isFakeBoldText = isSelected || isToday
            
            val textY = centerY + dateTextSize / 3
            canvas.drawText(date.dayOfMonth.toString(), centerX, textY, datePaint)
            
            // 绘制记录标记点（在日期下方）
            // 注意：选中状态下不显示标记点，避免视觉冲突
            if (hasRecord && !isSelected) {
                dotPaint.color = dotColor
                val dotY = centerY + selectedRadius + dotRadius + 2
                canvas.drawCircle(centerX, dotY, dotRadius, dotPaint)
            }
        }
    }

    /**
     * 获取要显示的日期列表
     */
    private fun getDisplayDates(): List<LocalDate> {
        return when (currentMode) {
            ViewMode.WEEK -> getWeekDates()
            ViewMode.MONTH -> getMonthDates()
        }
    }

    /**
     * 获取当前显示周的日期
     */
    private fun getWeekDates(): List<LocalDate> {
        // 使用 displayWeekStart 而不是 selectedDate，这样切换周时不会改变选中日期
        return (0 until DAYS_IN_WEEK).map { displayWeekStart.plusDays(it.toLong()) }
    }

    /**
     * 获取显示月份的所有日期（包含前后月份的补齐）
     */
    private fun getMonthDates(): List<LocalDate> {
        val firstDayOfMonth = displayMonth.atDay(1)
        val lastDayOfMonth = displayMonth.atEndOfMonth()
        
        // 获取第一周的周日（补齐前面的日期）
        val calendarStart = firstDayOfMonth.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        
        // 计算需要显示的周数
        // 从第一周的周日到本月最后一天的总天数
        val daysFromStartToEnd = java.time.temporal.ChronoUnit.DAYS.between(calendarStart, lastDayOfMonth) + 1
        // 向上取整到周数
        val rowCount = ((daysFromStartToEnd + DAYS_IN_WEEK - 1) / DAYS_IN_WEEK).toInt()
        // 确保至少显示4周，最多6周
        val finalRowCount = rowCount.coerceIn(4, MAX_MONTH_ROWS)
        
        return (0 until finalRowCount * DAYS_IN_WEEK).map { calendarStart.plusDays(it.toLong()) }
    }

    /**
     * 处理点击事件
     */
    private fun handleClick(x: Float, y: Float): Boolean {
        // 检查是否点击在日期区域
        if (y < weekDayHeight) return false
        
        // 计算列和行
        val col = (x / cellWidth).toInt().coerceIn(0, DAYS_IN_WEEK - 1)
        val row = ((y - weekDayHeight) / cellHeight).toInt()
        
        // 检查行是否在有效范围内（使用向上取整，确保动画过程中的行也能点击）
        val maxRow = animatedRowCount.toInt() + if (animatedRowCount % 1 > 0.1f) 1 else 0
        if (row < 0 || row >= maxRow) return false
        
        val dates = getDisplayDates()
        val index = row * DAYS_IN_WEEK + col
        
        if (index in dates.indices) {
            val clickedDate = dates[index]
            
            // 使用 setSelectedDate 方法确保状态正确更新
            // 注意：即使日期相同也会调用 invalidate() 重绘
            setSelectedDate(clickedDate, notify = true)
            
            return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 先让手势检测器处理（处理滑动等手势）
        val gestureHandled = gestureDetector.onTouchEvent(event)
        
        // 如果是点击事件（ACTION_UP），确保处理点击
        if (event.action == MotionEvent.ACTION_UP) {
            // 无论手势检测器是否处理，都尝试处理点击
            // 因为手势检测器可能处理了滑动，但点击也应该被处理
            val clickHandled = handleClick(event.x, event.y)
            if (clickHandled) {
                return true
            }
        }
        
        // 如果手势检测器处理了，返回 true
        // 否则调用父类处理
        return gestureHandled || super.onTouchEvent(event)
    }

    // ==================== 公开方法 ====================

    /**
     * 设置选中的日期
     */
    fun setSelectedDate(date: LocalDate, notify: Boolean = true) {
        val dateChanged = date != selectedDate
        selectedDate = date
        
        // 更新 displayMonth 为选中日期所在月份（月视图时）
        if (currentMode == ViewMode.MONTH) {
            displayMonth = YearMonth.from(date)
        }
        
        // 更新 displayWeekStart 为选中日期所在周的开始（周视图时）
        if (currentMode == ViewMode.WEEK) {
            displayWeekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        }
        
        // 如果切换视图模式后需要重新计算行数
        if (currentMode == ViewMode.MONTH) {
            val targetRows = getMonthDates().size / DAYS_IN_WEEK
            if (targetRows.toFloat() != animatedRowCount) {
                animatedRowCount = targetRows.toFloat()
                requestLayout()
            }
        }
        
        // 即使日期相同也要重绘，确保选中状态正确显示
        invalidate()
        
        if (notify && dateChanged) {
            onDateSelectedListener?.invoke(date)
        }
    }

    /**
     * 获取选中的日期
     */
    fun getSelectedDate(): LocalDate = selectedDate

    /**
     * 设置有记录的日期集合
     */
    fun setDatesWithRecords(dates: Set<LocalDate>) {
        datesWithRecords = dates
        invalidate()
    }

    /**
     * 设置视图模式
     */
    fun setViewMode(mode: ViewMode, animate: Boolean = true) {
        if (mode == currentMode) return
        
        currentMode = mode
        
        // 切换到月视图时，确保 displayMonth 与 selectedDate 同步
        if (mode == ViewMode.MONTH) {
            displayMonth = YearMonth.from(selectedDate)
            onMonthChangedListener?.invoke(displayMonth)
        }
        
        // 切换到周视图时，确保 displayWeekStart 与 selectedDate 同步
        if (mode == ViewMode.WEEK) {
            displayWeekStart = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        }
        
        onModeChangedListener?.invoke(mode)
        
        val targetRows = when (mode) {
            ViewMode.WEEK -> WEEK_ROWS
            ViewMode.MONTH -> {
                val dates = getMonthDates()
                dates.size / DAYS_IN_WEEK
            }
        }
        
        if (animate) {
            animateRowChange(targetRows.toFloat())
        } else {
            animatedRowCount = targetRows.toFloat()
            requestLayout()
            invalidate()
        }
    }

    /**
     * 获取当前视图模式
     */
    fun getViewMode(): ViewMode = currentMode

    /**
     * 切换视图模式
     */
    fun toggleViewMode() {
        setViewMode(
            if (currentMode == ViewMode.WEEK) ViewMode.MONTH else ViewMode.WEEK
        )
    }

    /**
     * 导航到上一周/月
     * 只改变显示范围，不改变用户选中的日期
     */
    fun navigatePrevious() {
        when (currentMode) {
            ViewMode.WEEK -> {
                // 周视图：切换到上一周，但保持 selectedDate 不变
                displayWeekStart = displayWeekStart.minusWeeks(1)
                invalidate()
            }
            ViewMode.MONTH -> {
                // 月视图：切换到上一月，但保持 selectedDate 不变
                displayMonth = displayMonth.minusMonths(1)
                
                // 重新计算行数
                val targetRows = getMonthDates().size / DAYS_IN_WEEK
                animatedRowCount = targetRows.toFloat()
                requestLayout()
                
                onMonthChangedListener?.invoke(displayMonth)
                invalidate()
            }
        }
    }

    /**
     * 导航到下一周/月
     * 只改变显示范围，不改变用户选中的日期
     */
    fun navigateNext() {
        when (currentMode) {
            ViewMode.WEEK -> {
                // 周视图：切换到下一周，但保持 selectedDate 不变
                displayWeekStart = displayWeekStart.plusWeeks(1)
                invalidate()
            }
            ViewMode.MONTH -> {
                // 月视图：切换到下一月，但保持 selectedDate 不变
                displayMonth = displayMonth.plusMonths(1)
                
                // 重新计算行数
                val targetRows = getMonthDates().size / DAYS_IN_WEEK
                animatedRowCount = targetRows.toFloat()
                requestLayout()
                
                onMonthChangedListener?.invoke(displayMonth)
                invalidate()
            }
        }
    }

    /**
     * 跳转到今天
     */
    fun goToToday() {
        setSelectedDate(LocalDate.now())
    }

    /**
     * 获取当前显示的年月
     */
    fun getDisplayMonth(): YearMonth = displayMonth

    /**
     * 获取格式化的标题（如 "2024年1月 第2周"）
     */
    fun getFormattedTitle(): String {
        return when (currentMode) {
            ViewMode.WEEK -> {
                // 周视图：使用当前显示周（displayWeekStart）的月份和周数
                val displayMonth = YearMonth.from(displayWeekStart)
                val monthFormatter = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINESE)
                val monthStr = displayMonth.format(monthFormatter)
                
                // 计算显示周是该月的第几周
                val weekOfMonth = displayWeekStart.get(WeekFields.of(Locale.CHINESE).weekOfMonth())
                "$monthStr 第${weekOfMonth}周"
            }
            ViewMode.MONTH -> {
                // 月视图：使用 displayMonth
                val monthFormatter = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINESE)
                displayMonth.format(monthFormatter)
            }
        }
    }

    // ==================== 监听器设置 ====================

    fun setOnDateSelectedListener(listener: (LocalDate) -> Unit) {
        onDateSelectedListener = listener
    }

    fun setOnMonthChangedListener(listener: (YearMonth) -> Unit) {
        onMonthChangedListener = listener
    }

    fun setOnModeChangedListener(listener: (ViewMode) -> Unit) {
        onModeChangedListener = listener
    }

    // ==================== 动画 ====================

    private fun animateRowChange(targetRows: Float) {
        expandAnimator?.cancel()
        
        expandAnimator = ValueAnimator.ofFloat(animatedRowCount, targetRows).apply {
            duration = EXPAND_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                animatedRowCount = animator.animatedValue as Float
                requestLayout()
                invalidate()
            }
            start()
        }
    }
}

