package com.diablo.derivtrader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator
import kotlin.math.max

/**
 * Branded startup screen. Progress advances only as MainActivity completes
 * local startup stages; the ECG trace itself remains continuously animated.
 */
class DiabloSplashView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 10, 31)
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 10, 31)
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        setShadowLayer(15f * density, 0f, 0f, Color.rgb(255, 0, 30))
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 10, 31)
        textAlign = Paint.Align.CENTER
        textSize = 29f * density
        typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
        letterSpacing = 0.08f
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(142, 161, 192)
        textAlign = Paint.Align.CENTER
        textSize = 9.5f * density
        letterSpacing = 0.16f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(244, 247, 255)
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
        letterSpacing = 0.12f
        typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
    }
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 10, 31)
        textAlign = Paint.Align.RIGHT
        textSize = 10f * density
        typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(30, 52, 89)
        style = Paint.Style.FILL
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 10, 31)
        style = Paint.Style.FILL
        setShadowLayer(8f * density, 0f, 0f, Color.rgb(255, 0, 30))
    }

    private var progress = 0
    private var status = "POWERING UP"
    private var startedAt = System.currentTimeMillis()
    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.rgb(2, 4, 10))
    }

    fun setProgress(value: Int, message: String) {
        val next = value.coerceIn(progress, 100)
        status = message
        animator?.cancel()
        animator = ValueAnimator.ofInt(progress, next).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(Color.rgb(2, 4, 10))
        val cx = w / 2f
        val cy = h * 0.40f

        // Subtle terminal grid.
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(22, 70, 100, 145)
            strokeWidth = 1f * density
        }
        var gx = 0f
        while (gx < w) {
            canvas.drawLine(gx, cy - 75f * density, gx, cy + 75f * density, grid)
            gx += 28f * density
        }
        for (i in -2..2) {
            val y = cy + i * 28f * density
            canvas.drawLine(0f, y, w, y, grid)
        }

        // ECG trace sweeps horizontally. The sharp spike repeats like a pulse reader.
        val span = w * 0.88f
        val left = (w - span) / 2f
        val baseline = cy
        val phase = ((System.currentTimeMillis() - startedAt) % 2300L) / 2300f
        val shift = phase * 34f * density
        val path = Path()
        val points = 220
        for (i in 0..points) {
            val x = left + span * i / points
            val local = ((x - left + shift) / (34f * density)) % 1f
            val pulse = when {
                local < 0.56f -> 0f
                local < 0.64f -> -7f * density * ((local - 0.56f) / 0.08f)
                local < 0.70f -> -7f * density + 37f * density * ((local - 0.64f) / 0.06f)
                local < 0.76f -> 30f * density - 30f * density * ((local - 0.70f) / 0.06f)
                local < 0.84f -> -5f * density * (1f - (local - 0.76f) / 0.08f)
                else -> 0f
            }
            val y = baseline + pulse
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, linePaint)

        val titleY = cy + 112f * density
        canvas.drawText("DIABLO", cx, titleY, titlePaint)
        canvas.drawText("DERIV AUTO TRADER", cx, titleY + 23f * density, subPaint)
        canvas.drawText("MULTI-MARKET  /  AUTOMATED EXECUTION", cx, titleY + 43f * density, subPaint)

        val barWidth = w * 0.72f
        val barLeft = (w - barWidth) / 2f
        val barTop = titleY + 84f * density
        val barHeight = 5f * density
        val rect = RectF(barLeft, barTop, barLeft + barWidth, barTop + barHeight)
        canvas.drawRoundRect(rect, barHeight, barHeight, trackPaint)
        val filled = RectF(barLeft, barTop, barLeft + barWidth * (progress / 100f), barTop + barHeight)
        if (progress > 0) canvas.drawRoundRect(filled, barHeight, barHeight, progressPaint)

        canvas.drawText(status, cx, barTop + 27f * density, statusPaint)
        canvas.drawText("$progress%", barLeft + barWidth, barTop + 48f * density, percentPaint)
        canvas.drawText("TRADE SMART  •  CONTROL RISK", cx, h - 34f * density, subPaint)

        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
