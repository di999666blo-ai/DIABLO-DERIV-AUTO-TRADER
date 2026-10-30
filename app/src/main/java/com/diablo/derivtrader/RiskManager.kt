package com.diablo.derivtrader

data class RiskSettings(
    val stake: Double = 1.0,
    val multiplier: Int = 10,
    val takeProfit: Double = 2.0,
    val stopLoss: Double = 1.0,
    val maxOpenPositions: Int = 1,
    val dailyLossLimit: Double = 5.0,
    val maxConsecutiveLosses: Int = 3,
    val maxTradesPerDay: Int = 20,
    val cooldownMillis: Long = 30_000L
)

data class RiskSnapshot(
    val realizedPnlToday: Double,
    val consecutiveLosses: Int,
    val tradesToday: Int,
    val openPositions: Int,
    val lastEntryAtMillis: Long,
    val emergencyStopped: Boolean = false
)

data class RiskDecision(val allowed: Boolean, val reason: String)

class RiskManager(private val settings: RiskSettings) {
    fun canEnter(state: RiskSnapshot, nowMillis: Long = System.currentTimeMillis()): RiskDecision {
        if (state.emergencyStopped) return RiskDecision(false, "Emergency stop is active")
        if (!settings.stake.isFinite() || settings.stake <= 0.0 ||
            settings.multiplier <= 0 ||
            !settings.takeProfit.isFinite() || settings.takeProfit <= 0.0 ||
            !settings.stopLoss.isFinite() || settings.stopLoss <= 0.0 ||
            !settings.dailyLossLimit.isFinite() || settings.dailyLossLimit <= 0.0 ||
            settings.maxConsecutiveLosses <= 0 || settings.maxTradesPerDay <= 0 ||
            settings.maxOpenPositions <= 0 || settings.cooldownMillis < 0L) {
            return RiskDecision(false, "Risk configuration is invalid; trading blocked")
        }
        if (!state.realizedPnlToday.isFinite()) return RiskDecision(false, "Daily P/L is invalid; trading blocked")
        if (state.realizedPnlToday <= -settings.dailyLossLimit) return RiskDecision(false, "Daily loss limit reached")
        if (state.consecutiveLosses >= settings.maxConsecutiveLosses) return RiskDecision(false, "Consecutive-loss limit reached")
        if (state.tradesToday >= settings.maxTradesPerDay) return RiskDecision(false, "Daily trade limit reached")
        if (state.openPositions >= settings.maxOpenPositions) return RiskDecision(false, "Maximum open positions reached")
        if (state.lastEntryAtMillis > 0L && nowMillis - state.lastEntryAtMillis < settings.cooldownMillis) {
            return RiskDecision(false, "Entry cooldown active")
        }
        return RiskDecision(true, "Risk checks passed")
    }

    fun exitReason(openProfit: Double): String? = when {
        !openProfit.isFinite() -> null
        openProfit >= settings.takeProfit -> "Take-profit reached"
        openProfit <= -settings.stopLoss -> "Stop-loss reached"
        else -> null
    }

    companion object {
        /** Deterministic local checks for the risk gates; this does not replace live Demo testing. */
        fun selfTest(): Boolean {
            val manager = RiskManager(RiskSettings())
            val clear = RiskSnapshot(0.0, 0, 0, 0, 0L)
            val now = 100_000L
            return manager.canEnter(clear, now).allowed &&
                !manager.canEnter(clear.copy(emergencyStopped = true), now).allowed &&
                !manager.canEnter(clear.copy(realizedPnlToday = -5.0), now).allowed &&
                !manager.canEnter(clear.copy(consecutiveLosses = 3), now).allowed &&
                !manager.canEnter(clear.copy(tradesToday = 20), now).allowed &&
                !manager.canEnter(clear.copy(openPositions = 1), now).allowed &&
                !manager.canEnter(clear.copy(lastEntryAtMillis = 90_000L), now).allowed &&
                manager.exitReason(2.0) == "Take-profit reached" &&
                manager.exitReason(-1.0) == "Stop-loss reached" &&
                manager.exitReason(0.0) == null
        }
    }
}
