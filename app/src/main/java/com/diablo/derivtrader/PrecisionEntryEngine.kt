package com.diablo.derivtrader

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

data class PrecisionSignal(
    val direction: TradeDirection,
    val score: Int,
    val reason: String,
    val tickScore: Int,
    val candleScore: Int,
    val volatility: String,
    val regime: String
)

/** Native DIABLO 90 gate. Scores measure setup alignment, not profit probability. */
class PrecisionEntryEngine(
    private val minimumScore: Int = 72,
    private val minimumTicks: Int = 14
) {
    fun evaluate(ticks: List<MarketTick>, candles: List<MarketCandle>, symbol: String,
                 candleSignal: TradeSignal): PrecisionSignal {
        val upper = symbol.uppercase()
        val spikeMarket = upper.startsWith("BOOM") || upper.startsWith("CRASH")
        val context = candleContext(candles)
        val tick = tickSignal(ticks, upper)
        fun wait(reason: String) = PrecisionSignal(TradeDirection.WAIT, 0, reason,
            tick.first, candleSignal.strength, context.first, context.second)

        if (candles.size < 35 || context.first == "UNKNOWN")
            return wait("Insufficient closed-candle context; collecting more candles")
        if (context.first == "HIGH") return wait("Candle volatility filter blocked entry")
        val tickDirection = tick.second
        if (tickDirection == TradeDirection.WAIT) return wait(tick.third)
        if (candleSignal.direction != TradeDirection.WAIT && candleSignal.direction != tickDirection)
            return wait("Tick and candle directions conflict")
        if (!spikeMarket && candleSignal.direction != tickDirection)
            return wait("Waiting for candle trend/momentum agreement")
        if (spikeMarket && candleSignal.direction == TradeDirection.WAIT && context.second == "RANGING")
            return wait("Market structure is ranging after spike; stand aside")

        val candleWeight = if (spikeMarket) 0.10 else 0.40
        val candlePoints = if (spikeMarket && candleSignal.direction == TradeDirection.WAIT) 50 else candleSignal.strength
        val score = (tick.first * (1.0 - candleWeight) + candlePoints * candleWeight).toInt()
        if (score < minimumScore) return wait("Combined setup score ${score} is below threshold ${minimumScore}")
        return PrecisionSignal(tickDirection, score,
            "DIABLO 90: live tick trigger and closed-candle context confirmed",
            tick.first, candleSignal.strength, context.first, context.second)
    }

    // Returns tick score, direction, reason.
    private fun tickSignal(input: List<MarketTick>, symbol: String): Triple<Int, TradeDirection, String> {
        val rows = input.filter { it.quote.isFinite() && it.quote > 0.0 }.takeLast(64)
        if (rows.size < minimumTicks)
            return Triple(0, TradeDirection.WAIT, "Collecting tick confirmation (${rows.size}/${minimumTicks})")
        val changes = rows.zipWithNext { a, b -> b.quote - a.quote }
        val magnitudes = changes.map { abs(it) }.filter { it > 0.0 }.sorted()
        if (magnitudes.size < 8)
            return Triple(0, TradeDirection.WAIT, "Insufficient price movement to establish tick baseline")
        val median = magnitudes[magnitudes.size / 2]
        if (median <= 0.0) return Triple(0, TradeDirection.WAIT, "Market is stationary")

        val boom = symbol.startsWith("BOOM")
        val crash = symbol.startsWith("CRASH")
        if (boom || crash) {
            val expected = if (boom) 1 else -1
            val tail = changes.takeLast(8)
            val spikeIndex = tail.indexOfFirst { it * expected > 0 && abs(it) >= median * 4.0 }
            if (spikeIndex < 0) return Triple(0, TradeDirection.WAIT,
                if (boom) "Waiting for an observed upward Boom spike" else "Waiting for an observed downward Crash spike")
            val after = tail.drop(spikeIndex + 1)
            if (after.size < 2) return Triple(0, TradeDirection.WAIT, "Spike detected; waiting for two follow-through ticks")
            val spike = tail[spikeIndex]
            val held = after.take(2).all { it * expected >= 0.0 }
            val postSpikeIndex = rows.size - tail.size + spikeIndex + 1
            val postSpike = rows.getOrNull(postSpikeIndex)?.quote
                ?: return Triple(0, TradeDirection.WAIT, "Spike reference price unavailable")
            val postQuotes = rows.drop(postSpikeIndex).map { it.quote }
            val favorable = if (expected > 0) postQuotes.maxOrNull() ?: postSpike else postQuotes.minOrNull() ?: postSpike
            val current = rows.last().quote
            val retrace = max(0.0, if (expected > 0) favorable - current else current - favorable)
            if (!held || retrace >= abs(spike) * 0.5)
                return Triple(0, TradeDirection.WAIT, "Spike failed follow-through; stand aside")
            return Triple(76, if (expected > 0) TradeDirection.UP else TradeDirection.DOWN,
                "Observed characteristic spike with two confirming follow-through ticks")
        }

        val tail = changes.takeLast(8)
        val net = tail.sum()
        val aligned = tail.count { it != 0.0 && it * net > 0.0 }
        val move = abs(net) / median
        if (aligned < 6 || move < 3.0)
            return Triple(0, TradeDirection.WAIT, "Waiting for sustained tick momentum")
        val score = minOf(90, 65 + aligned * 2 + minOf(9, floor(move).toInt()))
        return Triple(score, if (net > 0.0) TradeDirection.UP else TradeDirection.DOWN,
            "Sustained tick momentum confirmed across recent price stream")
    }

    // Mirrors the DIABLO 90 volatility/regime guard using closed OHLC candles.
    private fun candleContext(rows: List<MarketCandle>): Pair<String, String> {
        if (rows.size < 35) return "UNKNOWN" to "UNKNOWN"
        val ranges = (1 until rows.size).map { i ->
            val c = rows[i]; val p = rows[i - 1]
            maxOf(c.high - c.low, abs(c.high - p.close), abs(c.low - p.close))
        }
        if (ranges.size < 14) return "UNKNOWN" to "UNKNOWN"
        val sorted = ranges.takeLast(30).sorted()
        val mid = sorted.size / 2
        val baseline = if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
        if (baseline <= 0.0) return "UNKNOWN" to "UNKNOWN"
        val ratio = ranges.takeLast(14).average() / baseline
        val volatility = when { ratio < 0.35 -> "LOW"; ratio > 2.5 -> "HIGH"; else -> "NORMAL" }
        val closes = rows.map { it.close }
        val period = minOf(10, closes.lastIndex)
        val net = abs(closes.last() - closes[closes.size - 1 - period])
        var path = 0.0
        for (i in closes.size - period until closes.size) path += abs(closes[i] - closes[i - 1])
        val efficiency = if (path == 0.0) 0.0 else net / path
        val regime = when { efficiency > 0.55 -> "TRENDING"; efficiency < 0.20 -> "RANGING"; else -> "MIXED" }
        return volatility to regime
    }
}
