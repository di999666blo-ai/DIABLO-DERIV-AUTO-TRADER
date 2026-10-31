package com.diablo.derivtrader

import kotlin.math.abs
import kotlin.math.max

/** Closed-candle adaptive strategy. Strength is setup quality, NOT probability of profit. */
data class MarketCandle(val epoch: Long, val open: Double, val high: Double, val low: Double, val close: Double)
enum class TradeDirection { UP, DOWN, WAIT }
data class TradeSignal(val direction: TradeDirection, val strength: Int, val reason: String, val candleEpoch: Long)

class ScalpingEngine(
    private val fastPeriod: Int = 9,
    private val slowPeriod: Int = 21,
    private val rsiPeriod: Int = 14,
    private val minimumStrength: Int = 72,
    private val minimumCandles: Int = 35
) {
    fun evaluate(candles: List<MarketCandle>): TradeSignal {
        val rows = candles.filter(::validCandle)
        val epoch = rows.lastOrNull()?.epoch ?: 0L
        val required = maxOf(slowPeriod + 2, rsiPeriod + 2, minimumCandles)
        if (rows.size < required) return wait("Waiting for enough closed candles (${rows.size}/$required)", epoch)

        val closes = rows.map { it.close }
        val fast = ema(closes, fastPeriod)
        val slow = ema(closes, slowPeriod)
        val momentum = rsi(closes, rsiPeriod)
        val atr = trueRanges(rows).takeLast(14).average()
        val ranges = trueRanges(rows)
        val baseline = median(ranges.takeLast(30))
        val last = rows.last()
        if (listOf(fast, slow, momentum, atr, baseline).any { !it.isFinite() } || baseline <= 0.0)
            return wait("Indicator calculation unavailable; trading paused", last.epoch)

        // Relative ATR avoids fixed quote-price assumptions across Deriv symbols.
        val volatilityRatio = atr / baseline
        val efficiency = efficiencyRatio(closes, minOf(10, closes.lastIndex))
        val regime = when { efficiency > 0.55 -> "TRENDING"; efficiency < 0.20 -> "RANGING"; else -> "MIXED" }
        if (volatilityRatio > 2.5) return wait("Volatility spike (${fmt(volatilityRatio)}x baseline): stand aside", last.epoch)
        if (volatilityRatio < 0.35) return wait("Dead volatility (${fmt(volatilityRatio)}x baseline): stand aside", last.epoch)
        if (regime == "RANGING") return wait("Ranging market (efficiency ${fmt(efficiency)}): no directional edge", last.epoch)

        val previous = rows[rows.lastIndex - 1]
        val candleRange = max(last.high - last.low, abs(last.close) * 1e-12)
        val bodyRatio = abs(last.close - last.open) / candleRange
        val candleUp = last.close > last.open && last.close > previous.close
        val candleDown = last.close < last.open && last.close < previous.close
        val upTrend = fast > slow && last.close > fast && momentum in 50.0..75.0
        val downTrend = fast < slow && last.close < fast && momentum in 25.0..50.0
        val trendPoints = (abs(fast - slow) / max(abs(slow), 1e-12) * 12000.0).coerceIn(0.0, 25.0)
        val momentumPoints = (abs(momentum - 50.0) * 1.1).coerceIn(0.0, 22.0)
        val structurePoints = when { bodyRatio >= 0.60 -> 18.0; bodyRatio >= 0.40 -> 12.0; else -> 0.0 }
        val efficiencyPoints = (efficiency * 30.0).coerceIn(0.0, 25.0)
        val candidate = when { upTrend && candleUp -> TradeDirection.UP; downTrend && candleDown -> TradeDirection.DOWN; else -> TradeDirection.WAIT }
        val score = if (candidate == TradeDirection.WAIT) 0 else
            (35.0 + trendPoints + momentumPoints + structurePoints + efficiencyPoints).toInt().coerceIn(0, 100)
        val confirmed = candidate != TradeDirection.WAIT && score >= minimumStrength
        val direction = if (confirmed) candidate else TradeDirection.WAIT
        val reason = when {
            candidate == TradeDirection.WAIT -> "Trend, momentum and closed-candle structure are not aligned"
            !confirmed -> "Adaptive setup below quality threshold ($score/$minimumStrength)"
            else -> "Adaptive $regime ${candidate.name.lowercase()} setup confirmed on a closed candle"
        }
        return TradeSignal(direction, score, reason, last.epoch)
    }

    private fun validCandle(c: MarketCandle) =
        c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite() &&
            c.open > 0.0 && c.close > 0.0 && c.high >= maxOf(c.open, c.close, c.low) &&
            c.low <= minOf(c.open, c.close, c.high)

    private fun wait(reason: String, epoch: Long) = TradeSignal(TradeDirection.WAIT, 0, reason, epoch)
    private fun ema(v: List<Double>, p: Int): Double {
        val alpha = 2.0 / (p + 1.0)
        var result = v.take(p).average()
        for (i in p until v.size) result = v[i] * alpha + result * (1.0 - alpha)
        return result
    }
    private fun rsi(v: List<Double>, p: Int): Double {
        var gain = 0.0; var loss = 0.0
        for (i in 1..p) { val d = v[i] - v[i - 1]; if (d >= 0.0) gain += d else loss -= d }
        var ag = gain / p; var al = loss / p
        for (i in p + 1 until v.size) {
            val d = v[i] - v[i - 1]
            ag = (ag * (p - 1) + max(d, 0.0)) / p
            al = (al * (p - 1) + max(-d, 0.0)) / p
        }
        if (al == 0.0) return if (ag == 0.0) 50.0 else 100.0
        val rs = ag / al
        return 100.0 - 100.0 / (1.0 + rs)
    }
    private fun trueRanges(rows: List<MarketCandle>) = (1 until rows.size).map { i ->
        val c = rows[i]; val p = rows[i - 1]
        maxOf(c.high - c.low, abs(c.high - p.close), abs(c.low - p.close))
    }
    private fun efficiencyRatio(v: List<Double>, p: Int): Double {
        if (p <= 0 || v.size <= p) return 0.0
        val net = abs(v.last() - v[v.size - 1 - p])
        var path = 0.0
        for (i in v.size - p until v.size) path += abs(v[i] - v[i - 1])
        return if (path == 0.0) 0.0 else net / path
    }
    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted(); val m = s.size / 2
        return if (s.size % 2 == 0) (s[m - 1] + s[m]) / 2.0 else s[m]
    }
    private fun fmt(v: Double) = "%.2f".format(java.util.Locale.US, v)
}
