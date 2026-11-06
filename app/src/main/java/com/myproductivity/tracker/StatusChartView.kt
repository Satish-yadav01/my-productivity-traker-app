package com.myproductivity.tracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class StatusChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        color = ContextCompat.getColor(context, R.color.muted)
        textAlign = Paint.Align.CENTER
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chart_grid)
        strokeWidth = density
    }
    private val labels = mutableListOf<String>()
    private val values = mutableListOf<Int>()
    private val colors = listOf(
        ContextCompat.getColor(context, R.color.brand),
        ContextCompat.getColor(context, R.color.chart_in_progress),
        ContextCompat.getColor(context, R.color.chart_completed)
    )

    fun setData(labels: List<String>, values: List<Int>) {
        require(labels.size == values.size && (labels.isEmpty() || labels.size == colors.size))
        this.labels.clear()
        this.labels.addAll(labels)
        this.values.clear()
        this.values.addAll(values.map { it.coerceAtLeast(0) })
        contentDescription = labels.zip(values).joinToString(", ") { (label, value) ->
            "$label: $value"
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (labels.isEmpty()) return

        val left = paddingLeft.toFloat() + 8f * density
        val right = width.toFloat() - paddingRight - 8f * density
        val top = paddingTop.toFloat() + 24f * density
        val baseline = height.toFloat() - paddingBottom - 32f * density
        val plotHeight = (baseline - top).coerceAtLeast(1f)
        val maxValue = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val slotWidth = (right - left) / labels.size
        val barWidth = minOf(42f * density, slotWidth * 0.46f)

        for (line in 0..2) {
            val y = top + plotHeight * line / 2f
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        labels.forEachIndexed { index, label ->
            val centerX = left + slotWidth * (index + 0.5f)
            val barHeight = plotHeight * values[index] / maxValue.toFloat()
            val rect = RectF(
                centerX - barWidth / 2f,
                baseline - barHeight,
                centerX + barWidth / 2f,
                baseline
            )
            barPaint.color = colors[index]
            canvas.drawRoundRect(rect, 7f * density, 7f * density, barPaint)

            val countY = (rect.top - 7f * density).coerceAtLeast(13f * density)
            canvas.drawText(values[index].toString(), centerX, countY, textPaint)
            canvas.drawText(label, centerX, height.toFloat() - paddingBottom - 8f * density, textPaint)
        }
    }
}
