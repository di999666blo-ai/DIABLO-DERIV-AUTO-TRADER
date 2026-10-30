package com.diablo.derivtrader

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

data class TraderConfig(
    val candleSeconds: Long = 5L,
    val stake: Double = 1.0,
    val multiplier: Int = 10,
    val currency: String = "USD",
    val takeProfit: Double = 2.0,
    val stopLoss: Double = 1.0,
    val dailyLossLimit: Double = 5.0,
    val maxConsecutiveLosses: Int = 3,
    val maxTradesPerDay: Int = 20,
    val cooldownMillis: Long = 30_000L
)

data class TradeRecord(
    val contractId: Long,
    val symbol: String,
    val direction: String,
    val buyPrice: Double,
    val profit: Double,
    val status: String,
    val detail: String
)

/**
 * Demo-only orchestration: live ticks -> closed candles -> signal -> risk checks ->
 * proposal -> buy -> monitor -> optional TP/SL close.
 */
class TradingController(
    private val api: DerivApi,
    private val config: TraderConfig,
    private val onStatus: (String) -> Unit,
    private val onTrade: (TradeRecord) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val candles = ArrayList<MarketCandle>()
    private val engine = ScalpingEngine()
    private val candleBuilder = TickCandleBuilder(config.candleSeconds)
    private val risk = RiskManager(
        RiskSettings(
            stake = config.stake,
            multiplier = config.multiplier,
            takeProfit = config.takeProfit,
            stopLoss = config.stopLoss,
            maxOpenPositions = 1,
            dailyLossLimit = config.dailyLossLimit,
            maxConsecutiveLosses = config.maxConsecutiveLosses,
            maxTradesPerDay = config.maxTradesPerDay,
            cooldownMillis = config.cooldownMillis
        )
    )
    private var symbol: String = ""
    private var currency: String = config.currency
    private var subscriptionId: String? = null
    private var lastEntryMillis = 0L
    private var realizedPnl = 0.0
    private var consecutiveLosses = 0
    private var tradesToday = 0
    private var openContractId: Long? = null
    private var emergencyStop = false
    private val seenSignals = HashSet<Long>()

    @Synchronized
    fun start(selectedSymbol: String, accountCurrency: String = config.currency) {
        if (!api.connected) {
            onStatus("Connect Deriv Demo before starting the scanner.")
            return
        }
        if (running.get()) {
            onStatus("Scanner is already running.")
            return
        }
        symbol = selectedSymbol
        currency = accountCurrency
        if (symbol.isBlank()) {
            onStatus("Select a market first.")
            return
        }
        running.set(true)
        emergencyStop = false
        candles.clear()
        seenSignals.clear()
        onStatus("Checking MULTUP/MULTDOWN availability for $symbol…")
        api.contractsFor(symbol) { result ->
            result.onSuccess { response ->
                val available = response.optJSONObject("contracts_for")?.optJSONArray("available")
                val types = (0 until (available?.length() ?: 0)).mapNotNull {
                    available?.optJSONObject(it)?.optString("contract_type")
                }
                if (!types.contains("MULTUP") || !types.contains("MULTDOWN")) {
                    running.set(false)
                    onStatus("This market does not confirm both MULTUP and MULTDOWN. Scanner stopped.")
                    return@onSuccess
                }
                api.subscribeTicks(symbol) { tickResult ->
                    tickResult.onSuccess { tickResponse ->
                        val sub = tickResponse.optJSONObject("subscription")
                        subscriptionId = sub?.optString("id")?.takeIf { !it.isNullOrBlank() }
                        onStatus("Live ticks subscribed: $symbol. Auto-entry is active in Demo only.")
                    }.onFailure {
                        running.set(false)
                        onStatus("Tick subscription failed: ${it.message}")
                    }
                }
            }.onFailure {
                running.set(false)
                onStatus("Multiplier check failed: ${it.message}")
            }
        }
    }

    fun onDerivMessage(message: JSONObject) {
        message.optJSONObject("proposal_open_contract")?.let {
            onContractUpdate(it)
            return
        }
        if (!running.get()) return
        val tick = message.optJSONObject("tick") ?: return
        val quote = tick.optDouble("quote", Double.NaN)
        val epoch = tick.optLong("epoch", 0L)
        val completed = candleBuilder.onTick(MarketTick(epoch, quote)) ?: return
        synchronized(this) {
            candles.add(completed)
            if (candles.size > 300) candles.removeAt(0)
            val signal = engine.evaluate(candles.toList())
            onStatus("$symbol | ${signal.direction} | setup ${signal.strength}/100 | ${signal.reason}")
            if (signal.direction == TradeDirection.WAIT || !seenSignals.add(signal.candleEpoch)) return
            if (openContractId != null) {
                onStatus("Signal ignored: an open position is already being managed.")
                return
            }
            val snapshot = RiskSnapshot(
                realizedPnlToday = realizedPnl,
                consecutiveLosses = consecutiveLosses,
                tradesToday = tradesToday,
                openPositions = if (openContractId == null) 0 else 1,
                lastEntryAtMillis = lastEntryMillis,
                emergencyStopped = emergencyStop
            )
            val decision = risk.canEnter(snapshot)
            if (!decision.allowed) {
                onStatus("Entry blocked: ${decision.reason}")
                return
            }
            requestEntry(signal)
        }
    }

    @Synchronized
    private fun requestEntry(signal: TradeSignal) {
        if (!running.get() || openContractId != null || emergencyStop) return
        onStatus("Requesting ${signal.direction} multiplier proposal…")
        api.multiplierProposal(symbol, signal.direction.name, config.stake, config.multiplier, currency) { result ->
            result.onSuccess { response ->
                val proposal = response.optJSONObject("proposal")
                val proposalId = proposal?.optString("id").orEmpty()
                val askPrice = proposal?.optDouble("ask_price", Double.NaN) ?: Double.NaN
                if (proposalId.isBlank() || !askPrice.isFinite() || askPrice <= 0.0) {
                    onStatus("Proposal response missing a valid ID or ask price; no purchase made.")
                    return@onSuccess
                }
                api.buy(proposalId, askPrice) { buyResult ->
                    buyResult.onSuccess { bought ->
                        val buy = bought.optJSONObject("buy")
                        val id = buy?.optLong("contract_id", 0L) ?: 0L
                        if (id <= 0L) {
                            onStatus("Buy response did not contain a valid contract ID.")
                            return@onSuccess
                        }
                        synchronized(this) {
                            openContractId = id
                            lastEntryMillis = System.currentTimeMillis()
                            tradesToday++
                        }
                        onTrade(TradeRecord(id, symbol, signal.direction.name, askPrice, 0.0, "OPEN", signal.reason))
                        onStatus("Demo contract opened: $id. Monitoring live P/L.")
                        api.monitor(id) { monitorResult ->
                            monitorResult.onFailure { onStatus("Contract monitor request failed: ${it.message}") }
                        }
                    }.onFailure { onStatus("Buy rejected: ${it.message}") }
                }
            }.onFailure { onStatus("Proposal rejected: ${it.message}") }
        }
    }

    fun onContractUpdate(contract: JSONObject) {
        val id = contract.optLong("contract_id", 0L)
        synchronized(this) {
            if (id == 0L || id != openContractId) return
            val profit = contract.optDouble("profit", 0.0)
            val isSold = contract.optBoolean("is_sold", false)
            val isExpired = contract.optBoolean("is_expired", false)
            val exitReason = risk.exitReason(profit)
            if (!isSold && !isExpired && exitReason != null) {
                onStatus("$exitReason. Sending close request for $id…")
                api.sell(id) { sellResult ->
                    sellResult.onSuccess { onStatus("Close request accepted for $id.") }
                        .onFailure { onStatus("Close request failed: ${it.message}. Position remains open and monitored.") }
                }
            }
            if (isSold || isExpired) {
                realizedPnl += profit
                consecutiveLosses = if (profit < 0.0) consecutiveLosses + 1 else 0
                openContractId = null
                onTrade(TradeRecord(id, symbol, contract.optString("contract_type"), contract.optDouble("buy_price"), profit, "CLOSED", contract.optString("status")))
                onStatus("Contract $id closed. P/L $profit | Daily P/L $realizedPnl")
                if (realizedPnl <= -config.dailyLossLimit || consecutiveLosses >= config.maxConsecutiveLosses) {
                    emergencyStop = true
                    running.set(false)
                    onStatus("Risk lock activated. Scanner stopped.")
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        emergencyStop = true
        subscriptionId?.let { id ->
            api.unsubscribe(id) { }
        }
        subscriptionId = null
        onStatus("Scanner stopped. No new trades will be opened.")
    }

    fun isRunning(): Boolean = running.get()
}
