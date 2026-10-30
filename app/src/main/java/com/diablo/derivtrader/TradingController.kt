package com.diablo.derivtrader

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
    val cooldownMillis: Long = 30_000L,
    val initialRealizedPnl: Double = 0.0,
    val initialConsecutiveLosses: Int = 0,
    val initialTradesToday: Int = 0
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

/** One shared risk gate and one-position cap across up to 20 selected markets. */
class TradingController(
    private val api: DerivApi,
    private val config: TraderConfig,
    private val onStatus: (String) -> Unit,
    private val onTrade: (TradeRecord) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val activeSymbols = linkedSetOf<String>()
    private val subscriptions = linkedMapOf<String, String>()
    private val builders = hashMapOf<String, TickCandleBuilder>()
    private val candleHistory = hashMapOf<String, ArrayList<MarketCandle>>()
    private val seenSignals = hashMapOf<String, MutableSet<Long>>()
    private val engine = ScalpingEngine()
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
    private var currency = config.currency
    private var lastEntryMillis = 0L
    private var realizedPnl = config.initialRealizedPnl
    private var consecutiveLosses = config.initialConsecutiveLosses
    private var tradesToday = config.initialTradesToday
    private var openContractId: Long? = null
    private var openContractSymbol = ""
    private var closeRequestSent = false
    private var emergencyStop = false
    private var watchdog: ScheduledExecutorService? = null
    private val staleMarkets = hashSetOf<String>()

    @Synchronized
    fun start(selectedSymbol: String, accountCurrency: String = config.currency) =
        start(listOf(selectedSymbol), accountCurrency)

    @Synchronized
    fun start(selectedSymbols: List<String>, accountCurrency: String = config.currency) {
        if (!api.connected) {
            onStatus("Connect Deriv Demo before starting the scanner.")
            return
        }
        if (running.get()) {
            onStatus("Scanner is already running.")
            return
        }
        val requested = selectedSymbols.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (requested.isEmpty() || requested.size > 20) {
            onStatus("Select between 1 and 20 markets.")
            return
        }
        activeSymbols.clear()
        activeSymbols.addAll(requested)
        subscriptions.clear()
        builders.clear()
        candleHistory.clear()
        seenSignals.clear()
        requested.forEach {
            builders[it] = TickCandleBuilder(config.candleSeconds)
            candleHistory[it] = arrayListOf()
            seenSignals[it] = hashSetOf()
        }
        currency = accountCurrency
        emergencyStop = false
        staleMarkets.clear()
        running.set(true)
        watchdog?.shutdownNow()
        watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diablo-feed-watchdog").apply { isDaemon = true }
        }.also { scheduler ->
            scheduler.scheduleAtFixedRate({
                synchronized(this) {
                    if (!running.get()) return@scheduleAtFixedRate
                    val now = System.currentTimeMillis() / 1000L
                    activeSymbols.forEach { market ->
                        val builder = builders[market] ?: return@forEach
                        if (builder.isStale(now, 15L) && staleMarkets.add(market)) {
                            onStatus("$market feed stale (>15s). No entries will be taken from that market until ticks resume.")
                        }
                    }
                }
            }, 15L, 5L, TimeUnit.SECONDS)
        }
        onStatus("Checking multiplier contracts on ${requested.size} selected market(s)…")
        checkNextMarket(requested, 0)
    }

    private fun checkNextMarket(symbols: List<String>, index: Int) {
        if (!running.get()) return
        if (index >= symbols.size) {
            onStatus("Multiplier checks complete. Subscribing to live ticks on ${subscriptions.size} markets.")
            return
        }
        val symbol = symbols[index]
        api.contractsFor(symbol) { result ->
            result.onSuccess { response ->
                val available = response.optJSONObject("contracts_for")?.optJSONArray("available")
                val types = (0 until (available?.length() ?: 0)).mapNotNull {
                    available?.optJSONObject(it)?.optString("contract_type")
                }
                if (!types.contains("MULTUP") || !types.contains("MULTDOWN")) {
                    synchronized(this) { activeSymbols.remove(symbol) }
                    onStatus("$symbol skipped: both MULTUP and MULTDOWN are not available.")
                    checkNextMarket(symbols, index + 1)
                    return@onSuccess
                }
                api.subscribeTicks(symbol) { tickResult ->
                    tickResult.onSuccess { tickResponse ->
                        val id = tickResponse.optJSONObject("subscription")?.optString("id")
                        synchronized(this) {
                            if (!id.isNullOrBlank() && running.get()) subscriptions[symbol] = id
                        }
                        onStatus("$symbol live tick subscription active.")
                        checkNextMarket(symbols, index + 1)
                    }.onFailure {
                        synchronized(this) { activeSymbols.remove(symbol) }
                        onStatus("$symbol tick subscription failed: ${it.message}")
                        checkNextMarket(symbols, index + 1)
                    }
                }
            }.onFailure {
                synchronized(this) { activeSymbols.remove(symbol) }
                onStatus("$symbol contract check failed: ${it.message}")
                checkNextMarket(symbols, index + 1)
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
        val symbol = tick.optString("symbol")
        if (symbol !in activeSymbols) return
        synchronized(this) { staleMarkets.remove(symbol) }
        val quote = tick.optDouble("quote", Double.NaN)
        val epoch = tick.optLong("epoch", 0L)
        val completed = builders[symbol]?.onTick(MarketTick(epoch, quote)) ?: return
        synchronized(this) {
            if (symbol in staleMarkets) return
            val candles = candleHistory[symbol] ?: return
            candles.add(completed)
            if (candles.size > 300) candles.removeAt(0)
            val signal = engine.evaluate(candles.toList())
            onStatus("$symbol | ${signal.direction} | setup ${signal.strength}/100 | ${signal.reason}")
            if (signal.direction == TradeDirection.WAIT || seenSignals[symbol]?.add(signal.candleEpoch) != true) return
            if (openContractId != null) return
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
            requestEntry(symbol, signal)
        }
    }

    @Synchronized
    private fun requestEntry(symbol: String, signal: TradeSignal) {
        if (!running.get() || openContractId != null || emergencyStop) return
        onStatus("Requesting ${signal.direction} multiplier proposal on $symbol…")
        api.multiplierProposal(symbol, signal.direction.name, config.stake, config.multiplier, currency) { result ->
            result.onSuccess { response ->
                val proposal = response.optJSONObject("proposal")
                val proposalId = proposal?.optString("id").orEmpty()
                val askPrice = proposal?.optDouble("ask_price", Double.NaN) ?: Double.NaN
                if (proposalId.isBlank() || !askPrice.isFinite() || askPrice <= 0.0) {
                    onStatus("$symbol proposal missing a valid ID/ask price; no purchase made.")
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
                            openContractSymbol = symbol
                            closeRequestSent = false
                            lastEntryMillis = System.currentTimeMillis()
                            tradesToday++
                        }
                        onTrade(TradeRecord(id, symbol, signal.direction.name, askPrice, 0.0, "OPEN", signal.reason))
                        onStatus("Demo contract opened: $id on $symbol. Monitoring live P/L.")
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
            if (!isSold && !isExpired && exitReason != null && !closeRequestSent) {
                closeRequestSent = true
                onStatus("$exitReason. Sending close request for $id…")
                api.sell(id) { sellResult ->
                    sellResult.onSuccess { onStatus("Close request accepted for $id.") }
                        .onFailure {
                            synchronized(this) { closeRequestSent = false }
                            onStatus("Close request failed: ${it.message}. Position remains open and monitored.")
                        }
                }
            }
            if (isSold || isExpired) {
                realizedPnl += profit
                consecutiveLosses = if (profit < 0.0) consecutiveLosses + 1 else 0
                openContractId = null
                closeRequestSent = false
                onTrade(TradeRecord(id, openContractSymbol, contract.optString("contract_type"), contract.optDouble("buy_price"), profit, "CLOSED", contract.optString("status")))
                onStatus("Contract $id closed. P/L $profit | Session P/L $realizedPnl")
                if (realizedPnl <= -config.dailyLossLimit || consecutiveLosses >= config.maxConsecutiveLosses || tradesToday >= config.maxTradesPerDay) {
                    onStatus("Risk lock activated. Scanner stopped.")
                    stop()
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        emergencyStop = true
        watchdog?.shutdownNow()
        watchdog = null
        subscriptions.values.toList().forEach { id -> api.unsubscribe(id) { } }
        subscriptions.clear()
        onStatus("Scanner stopped. No new entries will be opened.")
    }

    fun isRunning(): Boolean = running.get()
}
