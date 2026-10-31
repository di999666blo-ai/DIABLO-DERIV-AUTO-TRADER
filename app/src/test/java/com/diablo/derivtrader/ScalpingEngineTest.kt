package com.diablo.derivtrader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpingEngineTest {
    private val engine = ScalpingEngine()

    @Test fun insufficientHistoryWaits() {
        val candles = (0 until 22).map { candle(it.toLong(), 100.0 + it * 0.1) }
        val result = engine.evaluate(candles)
        assertEquals(TradeDirection.WAIT, result.direction)
        assertTrue(result.reason.contains("enough closed candles"))
    }

    @Test fun rangingMarketIsRejected() {
        val candles = (0 until 50).map { i ->
            val close = if (i % 2 == 0) 100.0 else 100.1
            MarketCandle(i.toLong(), close, close + 0.05, close - 0.05, close)
        }
        val result = engine.evaluate(candles)
        assertEquals(TradeDirection.WAIT, result.direction)
        assertTrue(result.reason.contains("Ranging market"))
    }

    @Test fun volatilitySpikeIsRejected() {
        val candles = (0 until 50).map { i ->
            val close = 100.0 + i * 0.01
            if (i >= 36) MarketCandle(i.toLong(), close, close + 1.5, close - 1.5, close)
            else MarketCandle(i.toLong(), close, close + 0.01, close - 0.01, close)
        }
        val result = engine.evaluate(candles)
        assertEquals(TradeDirection.WAIT, result.direction)
        assertTrue(result.reason.contains("Volatility spike"))
    }

    @Test fun malformedCandlesCannotCreateSignal() {
        val candles = (0 until 50).map { i ->
            val close = 100.0 + i
            if (i == 49) MarketCandle(i.toLong(), close, close - 2, close - 3, close)
            else candle(i.toLong(), close)
        }
        assertEquals(TradeDirection.WAIT, engine.evaluate(candles).direction)
    }

    private fun candle(epoch: Long, close: Double) =
        MarketCandle(epoch, close - 0.02, close + 0.03, close - 0.03, close)
}
