package com.diablo.derivtrader

data class MarketTick(val epochSeconds: Long, val quote: Double)

/** Builds time-based OHLC candles from Deriv ticks; emits a candle only after its interval closes. */
class TickCandleBuilder(private val intervalSeconds: Long) {
    init { require(intervalSeconds > 0L) }

    private var bucketStart: Long? = null
    private var open = 0.0
    private var high = 0.0
    private var low = 0.0
    private var close = 0.0
    private var lastTickEpoch = 0L

    @Synchronized
    fun onTick(tick: MarketTick): MarketCandle? {
        if (!tick.quote.isFinite() || tick.quote <= 0.0 || tick.epochSeconds < lastTickEpoch) return null
        lastTickEpoch = tick.epochSeconds
        val bucket = (tick.epochSeconds / intervalSeconds) * intervalSeconds
        val current = bucketStart
        if (current == null) {
            start(bucket, tick.quote)
            return null
        }
        if (bucket < current) return null
        if (bucket == current) {
            high = maxOf(high, tick.quote)
            low = minOf(low, tick.quote)
            close = tick.quote
            return null
        }
        val completed = MarketCandle(current, open, high, low, close)
        start(bucket, tick.quote)
        return completed
    }

    @Synchronized
    fun isStale(nowEpochSeconds: Long, maximumAgeSeconds: Long = 10L): Boolean =
        lastTickEpoch == 0L || nowEpochSeconds - lastTickEpoch > maximumAgeSeconds

    private fun start(bucket: Long, price: Double) {
        bucketStart = bucket
        open = price
        high = price
        low = price
        close = price
    }
}
