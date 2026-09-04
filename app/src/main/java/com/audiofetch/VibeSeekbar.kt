package com.audiofetch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sin

class VibeSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var progress = 0f  // 0.0 - 1.0
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (!isDragging) invalidate()
        }

    var isPlaying = false
        set(value) {
            field = value
            invalidate()
        }

    var onSeek: ((Float) -> Unit)? = null  // callback with 0.0-1.0 value

    private var isDragging = false
    private var dragProgress = 0f

    private var wavePhase = 0f
    private var currentAmplitude = 0f

    private val wavePath = Path()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val trackH = 4f * density
        val trackY = h / 2f
        val currentProgress = if (isDragging) dragProgress else progress
        val fillWidth = w * currentProgress

        // Background track line
        trackPaint.color = Color.argb(30, 255, 255, 255)
        trackRect.set(0f, trackY - trackH / 2f, w, trackY + trackH / 2f)
        canvas.drawRoundRect(trackRect, trackH / 2f, trackH / 2f, trackPaint)

        // Wave amplitude interpolation (smooth damping)
        val targetAmplitude = if (isPlaying && !isDragging && currentProgress > 0.01f) 4.5f * density else 0f
        currentAmplitude += (targetAmplitude - currentAmplitude) * 0.15f

        val r = Color.red(accentColor)
        val g = Color.green(accentColor)
        val b = Color.blue(accentColor)

        // Draw active progress (wavy line or flat rounded stroke)
        if (fillWidth > 0f) {
            wavePaint.color = Color.argb(235, r, g, b)
            wavePaint.strokeWidth = trackH * 1.1f

            wavePath.reset()
            wavePath.moveTo(0f, trackY)

            val waveLength = 32f * density
            val step = 4f * density
            var x = 0f
            while (x <= fillWidth) {
                val radians = (x / waveLength) * 2f * Math.PI.toFloat() + wavePhase
                val y = trackY + sin(radians) * currentAmplitude
                wavePath.lineTo(x, y)
                x += step
            }
            // Ensure path connects to fillWidth exact point
            val endRad = (fillWidth / waveLength) * 2f * Math.PI.toFloat() + wavePhase
            val endY = trackY + sin(endRad) * currentAmplitude
            wavePath.lineTo(fillWidth, endY)

            canvas.drawPath(wavePath, wavePaint)
        }

        // M3 Expressive Thumb
        val thumbX = fillWidth
        val thumbY = if (fillWidth > 0f && currentAmplitude > 0.1f) {
            val endRad = (fillWidth / (32f * density)) * 2f * Math.PI.toFloat() + wavePhase
            trackY + sin(endRad) * currentAmplitude
        } else trackY

        val thumbR = if (isDragging) 10f * density else 7f * density
        glowPaint.color = Color.argb(80, r, g, b)
        glowPaint.setShadowLayer(16f * density, 0f, 0f, Color.argb(150, r, g, b))
        canvas.drawCircle(thumbX, thumbY, thumbR + 4f * density, glowPaint)
        glowPaint.clearShadowLayer()

        thumbPaint.color = accentColor
        canvas.drawCircle(thumbX, thumbY, thumbR, thumbPaint)

        // Continuously animate phase when active
        if (isPlaying || currentAmplitude > 0.05f) {
            wavePhase += 0.12f
            if (wavePhase > 2f * Math.PI.toFloat()) wavePhase -= 2f * Math.PI.toFloat()
            postInvalidateOnAnimation()
        }
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
