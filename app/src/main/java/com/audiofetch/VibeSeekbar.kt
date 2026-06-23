package com.audiofetch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class VibeSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var progress = 0f  // 0.0 - 1.0
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (!isDragging) invalidate()
        }

    var onSeek: ((Float) -> Unit)? = null  // callback with 0.0-1.0 value

    private var isDragging = false
    private var dragProgress = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val trackRect = RectF()
    private var accentColor = Color.parseColor("#00FFA2")

    fun setAccentColor(color: Int) {
        accentColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val trackH = 4f
        val trackY = h / 2f - trackH / 2f
        val currentProgress = if (isDragging) dragProgress else progress

        // Track background
        trackPaint.color = Color.argb(30, 255, 255, 255)
        trackRect.set(0f, trackY, w, trackY + trackH)
        canvas.drawRoundRect(trackRect, trackH / 2, trackH / 2, trackPaint)

        // Progress fill
        val r = Color.red(accentColor)
        val g = Color.green(accentColor)
        val b = Color.blue(accentColor)
        fillPaint.color = Color.argb(200, r, g, b)
        fillPaint.setShadowLayer(8f, 0f, 0f, Color.argb(120, r, g, b))
        trackRect.set(0f, trackY, w * currentProgress, trackY + trackH)
        canvas.drawRoundRect(trackRect, trackH / 2, trackH / 2, fillPaint)
        fillPaint.clearShadowLayer()

        // Thumb
        val thumbX = w * currentProgress
        val thumbR = if (isDragging) 10f else 7f
        glowPaint.color = Color.argb(80, r, g, b)
        glowPaint.setShadowLayer(16f, 0f, 0f, Color.argb(150, r, g, b))
        canvas.drawCircle(thumbX, h / 2, thumbR + 4f, glowPaint)
        glowPaint.clearShadowLayer()

        thumbPaint.color = accentColor
        canvas.drawCircle(thumbX, h / 2, thumbR, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                isDragging = true
                dragProgress = (event.x / width).coerceIn(0f, 1f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                progress = dragProgress
                onSeek?.invoke(progress)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }
}
