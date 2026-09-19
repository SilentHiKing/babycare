package com.zero.babycare.statistics.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.zero.common.R as CommonR

/** 百分位走向视图；完整数据留在状态层，渲染按实际宽度保留可辨认的趋势和极值。 */
class PercentileChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val curvePath = Path()
    private val guidePath = Path()
    private var points = intArrayOf()
    private var markerCoordinates = floatArrayOf()
    private var markerRadius = 0f
    private var geometryDirty = true

    /** 保留完整快照；同值重绑不重建 Path，调用方后续修改列表也不会改变已缓存曲线。 */
    fun setPercentiles(values: List<Int>) {
        val snapshot = IntArray(values.size) { values[it].coerceIn(0, 100) }
        if (points.contentEquals(snapshot)) return
        points = snapshot
        geometryDirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        geometryDirty = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        geometryDirty = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (geometryDirty) rebuildGeometry()
        canvas.drawPath(guidePath, guidePaint)
        canvas.drawPath(curvePath, linePaint)
        for (index in markerCoordinates.indices step 2) {
            canvas.drawCircle(markerCoordinates[index], markerCoordinates[index + 1], markerRadius, dotPaint)
        }
    }

    /** 只在数据、尺寸或配置变化时构造图形，滚动重绘不重复分配或遍历原始数千个点。 */
    private fun rebuildGeometry() {
        geometryDirty = false
        curvePath.rewind()
        guidePath.rewind()
        markerCoordinates = floatArrayOf()
        val metrics = resources.displayMetrics
        val inset = metrics.density * 6f
        val plotWidth = width - inset * 2
        val plotHeight = height - inset * 2
        if (plotWidth <= 0f || plotHeight <= 0f) return

        markerRadius = metrics.density * 2f
        linePaint.strokeWidth = metrics.density * 2f
        linePaint.color = ContextCompat.getColor(context, CommonR.color.event_growth)
        dotPaint.color = linePaint.color
        guidePaint.strokeWidth = resources.getDimension(CommonR.dimen.surface_stroke_width)
        guidePaint.color = ContextCompat.getColor(context, CommonR.color.control_border_default)
        for (percentile in intArrayOf(10, 50, 90)) {
            val y = toY(percentile, inset, plotHeight)
            guidePath.moveTo(inset, y)
            guidePath.lineTo(inset + plotWidth, y)
        }
        if (points.isEmpty()) return

        // 每个约 4dp 的可视桶最多留下两个极值；大字号时适度降低密度，不按物理像素堆顶点。
        val bucketWidth = maxOf(markerRadius * 2f,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 4f, metrics))
        val indices = PercentileChartSampler.sampleIndices(points, plotWidth, bucketWidth)
        val stepX = if (points.size == 1) 0f else plotWidth / points.lastIndex
        val showMarkers = points.size == 1 || stepX >= bucketWidth
        if (showMarkers) markerCoordinates = FloatArray(indices.size * 2)
        indices.forEachIndexed { position, originalIndex ->
            val x = inset + stepX * originalIndex
            val y = toY(points[originalIndex], inset, plotHeight)
            if (position == 0) curvePath.moveTo(x, y) else curvePath.lineTo(x, y)
            if (showMarkers) {
                markerCoordinates[position * 2] = x
                markerCoordinates[position * 2 + 1] = y
            }
        }
    }

    private fun toY(percentile: Int, inset: Float, plotHeight: Float) =
        inset + plotHeight * (1f - percentile / 100f)
}
