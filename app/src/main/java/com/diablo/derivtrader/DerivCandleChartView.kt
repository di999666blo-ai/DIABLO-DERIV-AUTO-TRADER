package com.diablo.derivtrader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import org.json.JSONArray
import kotlin.math.max
import kotlin.math.min

class DerivCandleChartView(context: Context) : View(context) {
    private data class Candle(val open: Double, val high: Double, val low: Double, val close: Double)
    private val candles = mutableListOf<Candle>()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF263B5B.toInt(); strokeWidth = 1f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8EA1C0.toInt(); textSize = 10f }
    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00FF7A.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF0A1F.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val upFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00FF7A.toInt(); style = Paint.Style.FILL }
    private val downFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF0A1F.toInt(); style = Paint.Style.FILL }
    private var message = "Load a market chart while connected to Deriv."
    private var symbol = ""
    private var timeframe = ""

    init {
        setBackgroundColor(0xFF07101D.toInt())
        minimumHeight = (240 * resources.displayMetrics.density).toInt()
        contentDescription = "Deriv live candle chart"
    }

    fun setLoading(label: String) {
        message = label
        invalidate()
    }

    fun setCandles(code: String, period: String, rows: JSONArray) {
        candles.clear()
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            val o = item.optDouble("open", Double.NaN)
            val h = item.optDouble("high", Double.NaN)
            val l = item.optDouble("low", Double.NaN)
            val c = item.optDouble("close", Double.NaN)
            if (listOf(o,h,l,c).all { it.isFinite() } && o > 0 && c > 0 && h >= max(o,c) && l <= min(o,c)) {
                candles.add(Candle(o,h,l,c))
            }
        }
        symbol = code
        timeframe = period
        message = if (candles.isEmpty()) "No valid candle data returned by Deriv." else ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val left = 12f*d
        val right = width - 54f*d
        val top = 26f*d
        val bottom = height - 28f*d
        canvas.drawText(if (symbol.isBlank()) "DIABLO • DERIV CHART" else "$symbol  •  $timeframe", left, 16f*d, textPaint)
        if (candles.isEmpty() || right <= left || bottom <= top) {
            canvas.drawText(message, left, height/2f, textPaint)
            return
        }
        val low = candles.minOf { it.low }
        val high = candles.maxOf { it.high }
        val span = (high-low).takeIf { it > 0.0 } ?: (high*0.001).coerceAtLeast(0.000001)
        for (i in 0..4) {
            val y = top + (bottom-top)*i/4f
            canvas.drawLine(left,y,right,y,gridPaint)
            val price = high - span*i/4.0
            canvas.drawText(String.format(java.util.Locale.US,"%.5f",price),right+4f*d,y+3f*d,textPaint)
        }
        val step = (right-left)/candles.size
        val bodyWidth = (step*0.62f).coerceAtLeast(1.5f*d)
        candles.forEachIndexed { index, candle ->
            val x = left + step*(index+0.5f)
            fun y(price: Double) = bottom - ((price-low)/span).toFloat()*(bottom-top)
            val rising = candle.close >= candle.open
            val stroke = if (rising) upPaint else downPaint
            val fill = if (rising) upFill else downFill
            canvas.drawLine(x,y(candle.high),x,y(candle.low),stroke)
            val bodyTop = min(y(candle.open),y(candle.close))
            val bodyBottom = max(y(candle.open),y(candle.close))
            val rect = RectF(x-bodyWidth/2f,bodyTop,x+bodyWidth/2f,max(bodyBottom,bodyTop+1f*d))
            canvas.drawRect(rect,fill)
        }
        canvas.drawText("OLDER",left,bottom+18f*d,textPaint)
        canvas.drawText("LATEST",right-34f*d,bottom+18f*d,textPaint)
    }
}
