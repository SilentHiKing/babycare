package com.zero.babycare.statistics.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * 简易百分位曲线视图
 * 用于展示生长记录的百分位走向（0~100）。
 */
class PercentileChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        color = ContextCompat.getColor(context, com.zero.common.R.color.colorBlue)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, com.zero.common.R.color.colorBlue)
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = ContextCompat.getColor(context, com.zero.common.R.color.colorE3)
    }

    private var points: List<Int> = emptyList()

    /**
     * 设置百分位数据，范围 0~100
     */
    fun setPercentiles(values: List<Int>) {
        points = values.map { it.coerceIn(0, 100) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val padding = resources.displayMetrics.density * 6f
        val usableWidth = width - padding * 2
        val usableHeight = height - padding * 2

        drawGuides(canvas, padding, usableWidth, usableHeight)
        if (points.isEmpty()) return

        val stepX = if (points.size == 1) 0f else usableWidth / (points.size - 1)
        var lastX = padding
        var lastY = toY(points.first(), padding, usableHeight)

        points.forEachIndexed { index, percentile ->
            val x = padding + stepX * index
            val y = toY(percentile, padding, usableHeight)
            if (index > 0) {
                canvas.drawLine(lastX, lastY, x, y, linePaint)
            }
            canvas.drawCircle(x, y, resources.displayMetrics.density * 2f, dotPaint)
            lastX = x
            lastY = y
        }
    }

    /**
     * 绘制参考线，帮助理解百分位高低
     */
    private fun drawGuides(canvas: Canvas, padding: Float, usableWidth: Float, usableHeight: Float) {
        val midY = toY(50, padding, usableHeight)
        val topY = toY(90, padding, usableHeight)
        val bottomY = toY(10, padding, usableHeight)
        canvas.drawLine(padding, topY, padding + usableWidth, topY, guidePaint)
        canvas.drawLine(padding, midY, padding + usableWidth, midY, guidePaint)
        canvas.drawLine(padding, bottomY, padding + usableWidth, bottomY, guidePaint)
    }

    private fun toY(percentile: Int, padding: Float, usableHeight: Float): Float {
        val ratio = percentile / 100f
        return padding + usableHeight * (1f - ratio)
    }
}
