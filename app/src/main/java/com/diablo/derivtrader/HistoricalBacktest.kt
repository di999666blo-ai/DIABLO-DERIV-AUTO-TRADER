package com.diablo.derivtrader

data class BacktestResult(
    val candlesProcessed: Int,
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val skipped: Int
) {
    val winRatePercent: Double
        get() = if (trades == 0) 0.0 else wins * 100.0 / trades
}

/**
 * No-lookahead candle replay: the strategy sees only bars before the next outcome bar.
 * This is a candle-only research estimate, not a simulation of Deriv fills or live DIABLO 90 ticks.
 */
class HistoricalBacktest(private val engine: ScalpingEngine = ScalpingEngine()) {
    fun run(input: List<MarketCandle>): BacktestResult {
        val rows = input
            .filter {
                it.epoch > 0L && it.open.isFinite() && it.high.isFinite() &&
                    it.low.isFinite() && it.close.isFinite() && it.open > 0.0 &&
                    it.close > 0.0 && it.high >= maxOf(it.open, it.close, it.low) &&
                    it.low <= minOf(it.open, it.close, it.high)
            }
            .distinctBy { it.epoch }
            .sortedBy { it.epoch }
        if (rows.size < 37) return BacktestResult(rows.size, 0, 0, 0, 0)

        var trades = 0
        var wins = 0
        var losses = 0
        for (nextIndex in 35 until rows.size) {
            val visibleBars = rows.subList(0, nextIndex)
            val signal = engine.evaluate(visibleBars)
            if (signal.direction == TradeDirection.WAIT) continue
            val entry = rows[nextIndex - 1].close
            val exit = rows[nextIndex].close
            val move = exit - entry
            if (move == 0.0) continue
            trades++
            val correct = (signal.direction == TradeDirection.UP && move > 0.0) ||
                (signal.direction == TradeDirection.DOWN && move < 0.0)
            if (correct) wins++ else losses++
        }
        return BacktestResult(rows.size, trades, wins, losses, rows.size - 35 - trades)
    }
}
