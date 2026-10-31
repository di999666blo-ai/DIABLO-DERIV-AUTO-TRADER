package com.diablo.derivtrader

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoricalBacktestTest {
    @Test fun shortHistoryDoesNotProduceTrades() {
        val rows = (1L..20L).map { candle(it, 100.0 + it * 0.1) }
        val result = HistoricalBacktest().run(rows)
        assertEquals(20, result.candlesProcessed)
        assertEquals(0, result.trades)
    }

    @Test fun inputIsSortedAndDuplicateEpochsAreRemoved() {
        val rows = (1L..40L).map { candle(it, 100.0 + it * 0.1) }
        val result = HistoricalBacktest().run(rows.reversed() + rows.last())
        assertEquals(40, result.candlesProcessed)
    }

    private fun candle(epoch: Long, close: Double) =
        MarketCandle(epoch, close - 0.08, close + 0.01, close - 0.09, close)
}
