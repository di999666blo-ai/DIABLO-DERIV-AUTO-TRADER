package com.diablo.derivtrader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Closed-candle trend/momentum filter. Scores are setup strength, NOT win probabilities. */
data class MarketCandle(
    val epoch: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

enum class TradeDirection { UP, DOWN, WAIT }

data class TradeSignal(
    val direction: TradeDirection,
    val strength: Int,
    val reason: String,
    val candleEpoch: Long
)

class ScalpingEngine(
    private val fastPeriod: Int = 9,
    private val slowPeriod: Int = 21,
    private val rsiPeriod: Int = 14,
    private val minimumStrength: Int = 70
) {
    fun evaluate(candles: List<MarketCandle>): TradeSignal {
        if (candles.size < max(slowPeriod + 2, rsiPeriod + 2)) {
            return TradeSignal(TradeDirection.WAIT, 0, "Waiting for enough closed candles", candles.lastOrNull()?.epoch ?: 0L)
        }
        val closes = candles.map { it.close }
        val fast = ema(closes, fastPeriod)
        val slow = ema(closes, slowPeriod)
        val rsi = rsi(closes, rsiPeriod)
        val last = candles.last()
        val previous = candles[candles.lastIndex - 1]
        val range = max(last.high - last.low, 1e-12)
        val bodyRatio = abs(last.close - last.open) / range
        val rising = last.close > last.open && last.close > previous.close
        val falling = last.close < last.open && last.close < previous.close

        var up = 0
        var down = 0
        if (fast > slow) up += 30 else if (fast < slow) down += 30
        if (last.close > fast) up += 20 else if (last.close < fast) down += 20
        if (rsi in 52.0..68.0) up += 20
        if (rsi in 32.0..48.0) down += 20
        if (rising) up += 20
        if (falling) down += 20
        if (bodyRatio >= 0.55) {
            if (last.close > last.open) up += 10 else down += 10
        }

        val direction = when {
            up >= minimumStrength && up - down >= 25 -> TradeDirection.UP
            down >= minimumStrength && down - up >= 25 -> TradeDirection.DOWN
            else -> TradeDirection.WAIT
        }
        val strength = max(up, down).coerceIn(0, 100)
        val reason = when (direction) {
            TradeDirection.UP -> "Bullish EMA alignment, momentum and candle confirmation"
            TradeDirection.DOWN -> "Bearish EMA alignment, momentum and candle confirmation"
            TradeDirection.WAIT -> "No sufficiently aligned setup; wait"
        }
        return TradeSignal(direction, strength, reason, last.epoch)
    }

    private fun ema(values: List<Double>, period: Int): Double {
        val alpha = 2.0 / (period + 1.0)
        var result = values.take(period).average()
        for (i in period until values.size) result = values[i] * alpha + result * (1.0 - alpha)
        return result
    }

    private fun rsi(values: List<Double>, period: Int): Double {
        var gains = 0.0
        var losses = 0.0
        for (i in 1..period) {
            val change = values[i] - values[i - 1]
            if (change >= 0) gains += change else losses -= change
        }
        var avgGain = gains / period
        var avgLoss = losses / period
        for (i in period + 1 until values.size) {
            val change = values[i] - values[i - 1]
            avgGain = (avgGain * (period - 1) + max(change, 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + max(-change, 0.0)) / period
        }
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }
}
