package com.inweb.app.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Tiny "sparkline" chart — draws a smooth cubic line + soft gradient
 * fill for a rolling window of the last N data points.
 *
 * Used on the dashboard's CPU / Network stat cards to give at-a-glance
 * trends without any charting library.
 *
 * Public API:
 *   sparkline.push(value)   — appends a value (0..1 recommended)
 *   sparkline.clear()       — reset the buffer
 *   sparkline.lineColor     — customise line colour
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Rolling window of normalised values (0..1). */
    private val buffer = ArrayDeque<Float>()
    var maxPoints: Int = 40
        set(value) { field = value.coerceAtLeast(4); trim(); invalidate() }

    /** Auto-scale y-axis to buffer max? If false, uses 0..1. */
    var autoScale: Boolean = false

    var lineColor: Int = 0xFF14B8A6.toInt()
        set(value) { field = value; linePaint.color = value; refreshFillGradient(); invalidate() }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4f
        color = lineColor
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val path = Path()
    private val fillPath = Path()

    fun push(v: Float) {
        buffer.addLast(v.coerceIn(0f, if (autoScale) Float.MAX_VALUE else 1f))
        trim()
        invalidate()
    }

    fun clear() { buffer.clear(); invalidate() }

    private fun trim() {
        while (buffer.size > maxPoints) buffer.removeFirst()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshFillGradient()
    }

    private fun refreshFillGradient() {
        if (width == 0 || height == 0) return
        val topAlpha    = (Color.alpha(lineColor) * 0.55f).toInt().coerceIn(0, 255)
        val bottomAlpha = 0
        val top    = Color.argb(topAlpha,    Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor))
        val bottom = Color.argb(bottomAlpha, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor))
        fillPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val n = buffer.size
        if (n < 2 || width == 0 || height == 0) return

        val padY = linePaint.strokeWidth
        val plotH = (height - padY * 2)
        val stepX = width.toFloat() / (maxPoints - 1)

        val maxV = if (autoScale) (buffer.maxOrNull() ?: 1f).coerceAtLeast(0.0001f) else 1f
        fun yFor(v: Float): Float = height - padY - (v / maxV).coerceIn(0f, 1f) * plotH

        path.reset()
        fillPath.reset()

        // Start at the right edge and walk backwards so newest data sits on the right.
        val startIdx = (maxPoints - n).coerceAtLeast(0)
        var prevX = startIdx * stepX
        var prevY = yFor(buffer[0])
        path.moveTo(prevX, prevY)
        fillPath.moveTo(prevX, height.toFloat())
        fillPath.lineTo(prevX, prevY)

        // Smooth cubic Bézier between consecutive points.
        for (i in 1 until n) {
            val x = (startIdx + i) * stepX
            val y = yFor(buffer[i])
            val midX = (prevX + x) / 2f
            path.cubicTo(midX, prevY, midX, y, x, y)
            fillPath.cubicTo(midX, prevY, midX, y, x, y)
            prevX = x; prevY = y
        }
        // Close fill down to the baseline.
        fillPath.lineTo(prevX, height.toFloat())
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
