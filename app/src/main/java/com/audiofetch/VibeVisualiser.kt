package com.audiofetch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class VibeVisualizer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var fftData = ByteArray(0)
    private var accentColor = Color.parseColor("#00FFA2")
    private val smoothed = FloatArray(128)
    private val smoothFactor = 0.75f

    // Rect for rounded bar drawing
    private val barRect = RectF()

    fun setAccentColor(color: Int) {
        accentColor = color
        invalidate()
    }

    fun updateFft(data: ByteArray) {
        if (data.size != smoothed.size) {
            fftData = data.copyOf()
        } else {
            // Smooth the data for organic animation
            for (i in data.indices) {
                val raw = (data[i].toInt() and 0xFF).toFloat()
                smoothed[i] = smoothed[i] * smoothFactor + raw * (1f - smoothFactor)
            }
            fftData = data.copyOf()
        }
        invalidate()
    }

    fun clear() {
        // Animate bars back to zero smoothly
        for (i in smoothed.indices) {
            smoothed[i] *= 0.85f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (smoothed.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val barCount = 40
        val totalGap = w * 0.3f
        val barWidth = (w - totalGap) / barCount
        val gap = totalGap / (barCount - 1)
        val r = Color.red(accentColor)
        val g = Color.green(accentColor)
        val b = Color.blue(accentColor)

        for (i in 0 until barCount) {
            val dataIndex = (i.toFloat() / barCount * smoothed.size * 0.7f).toInt()
                .coerceIn(0, smoothed.size - 1)
            val value = smoothed[dataIndex] / 255f
            val barH = (value * h * 0.85f).coerceAtLeast(3f)
            val x = i * (barWidth + gap)
            val alpha = ((0.4f + value * 0.6f) * 255).toInt().coerceIn(0, 255)

            paint.color = Color.argb(alpha, r, g, b)
            paint.setShadowLayer(12f, 0f, 0f, Color.argb((alpha * 0.6f).toInt(), r, g, b))

            barRect.set(x, h - barH, x + barWidth, h)
            val cornerR = min(barWidth / 2f, 4f)
            canvas.drawRoundRect(barRect, cornerR, cornerR, paint)
        }

        paint.clearShadowLayer()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        setLayerType(LAYER_TYPE_NONE, null)
    }

    init {
        // Hardware layer needed for shadow blur
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }
}
