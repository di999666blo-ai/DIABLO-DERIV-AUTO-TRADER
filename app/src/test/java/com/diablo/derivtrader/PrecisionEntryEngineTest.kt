package com.diablo.derivtrader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecisionEntryEngineTest {
    private val engine = PrecisionEntryEngine(minimumScore = 72)

    @Test fun regularMarketRequiresTickAndClosedCandleAgreement() {
        val candles = risingCandles()
        val ticks = risingTicks()
        val candleSignal = TradeSignal(TradeDirection.UP, 82, "closed candle confirms", candles.last().epoch)
        val result = engine.evaluate(ticks, candles, "R_100", candleSignal)
        assertEquals(TradeDirection.UP, result.direction)
        assertTrue(result.score >= 72)
        assertTrue(result.reason.contains("DIABLO 90"))
    }

    @Test fun regularMarketRejectsConflictingCandleDirection() {
        val candles = risingCandles()
        val ticks = risingTicks()
        val candleSignal = TradeSignal(TradeDirection.DOWN, 90, "conflicting candle", candles.last().epoch)
        val result = engine.evaluate(ticks, candles, "R_100", candleSignal)
        assertEquals(TradeDirection.WAIT, result.direction)
        assertTrue(result.reason.contains("conflict"))
    }

    @Test fun insufficientTickHistoryWaits() {
        val candles = risingCandles()
        val candleSignal = TradeSignal(TradeDirection.UP, 90, "closed candle confirms", candles.last().epoch)
        val result = engine.evaluate(risingTicks().take(8), candles, "R_100", candleSignal)
        assertEquals(TradeDirection.WAIT, result.direction)
        assertTrue(result.reason.contains("Collecting tick confirmation"))
    }

    private fun risingCandles(): List<MarketCandle> = (0 until 40).map { i ->
        val close = 100.0 + i * 0.1
        val open = close - 0.08
        MarketCandle(i.toLong(), open, close + 0.01, open - 0.01, close)
    }

    private fun risingTicks(): List<MarketTick> = (0 until 20).map { i ->
        MarketTick(100L + i, 100.0 + i * 0.02)
    }
}
