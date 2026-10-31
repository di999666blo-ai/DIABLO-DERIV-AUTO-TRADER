package com.diablo.derivtrader

import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class TraderConfig(
    val accountMode: String = "demo",
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
    val initialTradesToday: Int = 0,
    val maxOpenPositions: Int = 1,
    val maxTradesPerMarket: Int = 1,
    val stakeProgressionEnabled: Boolean = false,
    val stakeProgression: List<Double> = listOf(1.0, 2.0, 3.0, 4.0, 5.0),
    val initialProgressionIndex: Int = 0,
    val marketTradeLimits: Map<String, Int> = emptyMap()
)

data class TradeRecord(
    val contractId: Long,
    val symbol: String,
    val direction: String,
    val buyPrice: Double,
    val exitPrice: Double,
    val profit: Double,
    val status: String,
    val detail: String,
    val progressionIndex: Int = 0
)

private data class OpenPosition(
    val id: Long,
    val symbol: String,
    val direction: String,
    val buyPrice: Double,
    var subscriptionId: String? = null,
    var closeRequested: Boolean = false,
    var settled: Boolean = false
)

/** Multi-market Deriv multiplier engine. Every purchased contract is tracked independently. */
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
    private val pendingSymbols = linkedSetOf<String>()
    private val positions = linkedMapOf<Long, OpenPosition>()
    private val marketTradeCounts = hashMapOf<String, Int>()
    private val engine = ScalpingEngine()
    private val risk = RiskManager(RiskSettings(
        stake = config.stake, multiplier = config.multiplier,
        takeProfit = config.takeProfit, stopLoss = config.stopLoss,
        maxOpenPositions = config.maxOpenPositions,
        dailyLossLimit = config.dailyLossLimit,
        maxConsecutiveLosses = config.maxConsecutiveLosses,
        maxTradesPerDay = config.maxTradesPerDay,
        cooldownMillis = config.cooldownMillis
    ))
    private var currency = config.currency
    private var lastEntryMillis = 0L
    private var realizedPnl = config.initialRealizedPnl
    private var consecutiveLosses = config.initialConsecutiveLosses
    private var tradesToday = config.initialTradesToday
    private var progressionIndex = config.initialProgressionIndex.coerceIn(0, config.stakeProgression.lastIndex.coerceAtLeast(0))
    private var emergencyStop = false
    private var watchdog: ScheduledExecutorService? = null
    private val staleMarkets = hashSetOf<String>()
    private val receivedTickCounts = hashMapOf<String, Long>()
    private val lastTickWallMillis = hashMapOf<String, Long>()
    private val lastDiagnosticAtMillis = hashMapOf<String, Long>()
    private val requiredClosedCandles = 35

    @Synchronized fun start(selectedSymbol: String, accountCurrency: String = config.currency) =
        start(listOf(selectedSymbol), accountCurrency)

    @Synchronized fun start(selectedSymbols: List<String>, accountCurrency: String = config.currency) {
        if (!api.connected || api.connectedMode != config.accountMode ||
            (config.accountMode != "demo" && config.accountMode != "real")) {
            onStatus("Safety lock: authenticated Deriv account does not match selected mode."); return
        }
        if (running.get()) { onStatus("Scanner is already running."); return }
        val requested = selectedSymbols.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (requested.isEmpty() || requested.size > 20) { onStatus("Select between 1 and 20 markets."); return }
        if (config.maxOpenPositions !in 1..20 || config.maxTradesPerMarket !in 1..100 ||
            config.stakeProgression.isEmpty() || config.stakeProgression.any { !it.isFinite() || it <= 0.0 }) {
            onStatus("Invalid position, per-market quantity or stake progression settings. Trading blocked."); return
        }
        activeSymbols.clear(); activeSymbols.addAll(requested)
        subscriptions.clear(); builders.clear(); candleHistory.clear(); seenSignals.clear()
        receivedTickCounts.clear(); lastTickWallMillis.clear(); lastDiagnosticAtMillis.clear()
        pendingSymbols.clear(); marketTradeCounts.clear()
        requested.forEach { symbol ->
            builders[symbol] = TickCandleBuilder(config.candleSeconds)
            candleHistory[symbol] = arrayListOf()
            seenSignals[symbol] = hashSetOf()
            marketTradeCounts[symbol] = 0
        }
        currency = accountCurrency
        emergencyStop = false
        staleMarkets.clear()
        running.set(true)
        watchdog?.shutdownNow()
        watchdog = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "diablo-feed-watchdog").apply { isDaemon = true }
        }.also { scheduler ->
            scheduler.scheduleAtFixedRate({
                synchronized(this) {
                    if (!running.get()) return@scheduleAtFixedRate
                    val now = System.currentTimeMillis() / 1000L
                    activeSymbols.forEach { symbol ->
                        val builder = builders[symbol] ?: return@forEach
                        if (builder.isStale(now, 15L) && staleMarkets.add(symbol))
                            onStatus("$symbol feed stale (>15s). Entries paused until ticks resume.")
                    }
                }
            }, 15L, 5L, TimeUnit.SECONDS)
        }
        onStatus("Checking MULTUP/MULTDOWN on ${requested.size} markets…")
        checkNextMarket(requested, 0)
    }

    private fun checkNextMarket(symbols: List<String>, index: Int) {
        if (!running.get()) return
        if (index >= symbols.size) {
            onStatus("Contract checks complete. Live tick feeds: ${subscriptions.size}."); return
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
                    onStatus("$symbol skipped: both multiplier directions unavailable.")
                    checkNextMarket(symbols, index + 1); return@onSuccess
                }
                api.subscribeTicks(symbol) { tickResult ->
                    tickResult.onSuccess { tickResponse ->
                        val id = tickResponse.optJSONObject("subscription")?.optString("id")
                        synchronized(this) { if (!id.isNullOrBlank() && running.get()) subscriptions[symbol] = id }
                        onStatus("$symbol tick subscription acknowledged. Waiting for incoming tick messages…")
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
        message.optJSONObject("proposal_open_contract")?.let { onContractUpdate(it); return }
        if (!running.get()) return
        val tick = message.optJSONObject("tick") ?: return
        val symbol = tick.optString("symbol")
        if (symbol !in activeSymbols) return
        val nowMillis = System.currentTimeMillis()
        val tickCount = synchronized(this) {
            staleMarkets.remove(symbol)
            receivedTickCounts[symbol] = (receivedTickCounts[symbol] ?: 0L) + 1L
            lastTickWallMillis[symbol] = nowMillis
            receivedTickCounts[symbol] ?: 0L
        }
        val quote = tick.optDouble("quote", Double.NaN)
        val epoch = tick.optLong("epoch", 0L)
        val candle = builders[symbol]?.onTick(MarketTick(epoch, quote))
        if (candle == null) {
            synchronized(this) {
                val lastDiagnostic = lastDiagnosticAtMillis[symbol] ?: 0L
                if (nowMillis - lastDiagnostic >= 5_000L) {
                    lastDiagnosticAtMillis[symbol] = nowMillis
                    val closedCount = candleHistory[symbol]?.size ?: 0
                    onStatus("$symbol | FEED LIVE | ticks $tickCount | closed candles $closedCount/$requiredClosedCandles | collecting closed candles")
                }
            }
            return
        }
        synchronized(this) {
            if (symbol in staleMarkets) return
            val candles = candleHistory[symbol] ?: return
            candles.add(candle); if (candles.size > 300) candles.removeAt(0)
            val signal = engine.evaluate(candles.toList())
            val lastTickAge = ((System.currentTimeMillis() - (lastTickWallMillis[symbol] ?: System.currentTimeMillis())) / 1000L).coerceAtLeast(0L)
            onStatus("$symbol | ${signal.direction} | setup ${signal.strength}/100 | candles ${candles.size}/$requiredClosedCandles | ticks ${receivedTickCounts[symbol] ?: 0L} | last tick ${lastTickAge}s | ${signal.reason}")
            if (signal.direction == TradeDirection.WAIT || seenSignals[symbol]?.add(signal.candleEpoch) != true) return
            if (!running.get() || symbol in pendingSymbols || positions.values.any { it.symbol == symbol }) return
            val pairLimit = config.marketTradeLimits[symbol] ?: config.maxTradesPerMarket
            if (pairLimit !in 1..100 || (marketTradeCounts[symbol] ?: 0) >= pairLimit) {
                onStatus("$symbol per-market trade quantity limit reached."); return
            }
            val decision = risk.canEnter(RiskSnapshot(
                realizedPnlToday = realizedPnl,
                consecutiveLosses = consecutiveLosses,
                tradesToday = tradesToday,
                openPositions = positions.size + pendingSymbols.size,
                lastEntryAtMillis = lastEntryMillis,
                emergencyStopped = emergencyStop
            ))
            if (!decision.allowed) { onStatus("Entry blocked: ${decision.reason}"); return }
            requestEntry(symbol, signal)
        }
    }

    @Synchronized private fun requestEntry(symbol: String, signal: TradeSignal) {
        if (!running.get() || symbol in pendingSymbols || positions.values.any { it.symbol == symbol } ||
            positions.size + pendingSymbols.size >= config.maxOpenPositions || emergencyStop) return
        pendingSymbols.add(symbol)
        val stakeNow = if (config.stakeProgressionEnabled)
            config.stakeProgression[progressionIndex.coerceIn(0, config.stakeProgression.lastIndex)]
        else config.stake
        onStatus("Requesting ${signal.direction} proposal on $symbol (stake $stakeNow)…")
        api.multiplierProposal(symbol, signal.direction.name, stakeNow, config.multiplier, currency,
            config.takeProfit, config.stopLoss) { result ->
            result.onSuccess proposal@ { response ->
                val proposal = response.optJSONObject("proposal")
                val proposalId = proposal?.optString("id").orEmpty()
                val ask = proposal?.optDouble("ask_price", Double.NaN) ?: Double.NaN
                if (proposalId.isBlank() || !ask.isFinite() || ask <= 0.0) {
                    synchronized(this) { pendingSymbols.remove(symbol) }
                    onStatus("$symbol invalid proposal; no buy sent."); return@proposal
                }
                api.buy(proposalId, ask) { buyResult ->
                    buyResult.onSuccess bought@ { responseBuy ->
                        val buy = responseBuy.optJSONObject("buy")
                        val id = buy?.optLong("contract_id", 0L) ?: 0L
                        if (id <= 0L) {
                            synchronized(this) { pendingSymbols.remove(symbol) }
                            onStatus("$symbol buy response missing contract ID."); return@bought
                        }
                        synchronized(this) {
                            pendingSymbols.remove(symbol)
                            positions[id] = OpenPosition(id, symbol, signal.direction.name, ask)
                            lastEntryMillis = System.currentTimeMillis()
                            tradesToday++
                            marketTradeCounts[symbol] = (marketTradeCounts[symbol] ?: 0) + 1
                        }
                        onTrade(TradeRecord(id, symbol, signal.direction.name, ask, 0.0, 0.0, "OPEN", signal.reason))
                        onStatus("${config.accountMode.uppercase()} contract $id opened on $symbol. Active: ${positions.size}.")
                        api.monitor(id) { monitorResult ->
                            monitorResult.onSuccess { monitorResponse ->
                                val sub = monitorResponse.optJSONObject("subscription")?.optString("id")
                                synchronized(this) { positions[id]?.subscriptionId = sub }
                            }
                            monitorResult.onFailure {
                                onStatus("Monitor failed for open contract $id: ${it.message}. Scanner paused; reconnect and inspect Deriv portfolio.")
                                stop()
                            }
                        }
                    }.onFailure {
                        synchronized(this) { pendingSymbols.remove(symbol) }
                        onStatus("$symbol buy rejected: ${it.message}")
                    }
                }
            }.onFailure {
                synchronized(this) { pendingSymbols.remove(symbol) }
                onStatus("$symbol proposal rejected: ${it.message}")
            }
        }
    }

    fun onContractUpdate(contract: JSONObject) {
        val id = contract.optLong("contract_id", 0L)
        synchronized(this) {
            val position = positions[id] ?: return
            if (position.settled) return
            val profit = contract.optDouble("profit", 0.0)
            val sold = contract.optBoolean("is_sold", false)
            val expired = contract.optBoolean("is_expired", false)
            val exit = risk.exitReason(profit)
            if (!sold && !expired && exit != null && !position.closeRequested) {
                position.closeRequested = true
                onStatus("$exit. Closing contract $id…")
                api.sell(id) { result ->
                    result.onSuccess { onStatus("Close request accepted for $id.") }
                    .onFailure {
                        synchronized(this) { positions[id]?.closeRequested = false }
                        onStatus("Close request failed for $id: ${it.message}. Still monitoring.")
                    }
                }
            }
            if (sold || expired) {
                position.settled = true
                realizedPnl += profit
                if (profit < 0.0) {
                    consecutiveLosses++
                    progressionIndex = (progressionIndex - 1).coerceAtLeast(0)
                } else if (profit > 0.0) {
                    consecutiveLosses = 0
                    if (config.stakeProgressionEnabled)
                        progressionIndex = (progressionIndex + 1).coerceAtMost(config.stakeProgression.lastIndex)
                } else consecutiveLosses = 0
                positions.remove(id)
                position.subscriptionId?.let { sub -> api.unsubscribe(sub) { } }
                val direction = when (contract.optString("contract_type")) {
                    "MULTUP" -> "UP"; "MULTDOWN" -> "DOWN"; else -> position.direction
                }
                onTrade(TradeRecord(id, position.symbol, direction,
                    contract.optDouble("buy_price", position.buyPrice),
                    contract.optDouble("sell_price", contract.optDouble("bid_price", 0.0)),
                    profit, "CLOSED", contract.optString("status"), progressionIndex))
                val nextStake = if (config.stakeProgressionEnabled)
                    config.stakeProgression[progressionIndex.coerceIn(0, config.stakeProgression.lastIndex)]
                else config.stake
                val progressionNote = if (config.stakeProgressionEnabled)
                    " | next stake $nextStake (${if (profit > 0.0) "step up" else if (profit < 0.0) "step down" else "unchanged"})"
                else ""
                onStatus("Contract $id settled. P/L $profit | Session $realizedPnl | Open ${positions.size}$progressionNote.")
                if (realizedPnl <= -config.dailyLossLimit ||
                    consecutiveLosses >= config.maxConsecutiveLosses ||
                    tradesToday >= config.maxTradesPerDay) {
                    onStatus("Risk lock activated. New entries stopped.")
                    stop()
                }
            }
        }
    }

    @Synchronized fun stop() {
        running.set(false)
        emergencyStop = true
        watchdog?.shutdownNow(); watchdog = null
        subscriptions.values.toList().forEach { api.unsubscribe(it) { } }
        subscriptions.clear()
        // Keep contract subscriptions alive: existing exposure must remain monitored until settled.
        onStatus("Scanner stopped. No new entries; monitoring ${positions.size} existing contract(s).")
    }

    /** Re-adopts existing portfolio contracts after app restart; never opens a replacement trade. */
    @Synchronized fun adoptOpenPositions(contracts: JSONArray) {
        for (index in 0 until contracts.length()) {
            val item = contracts.optJSONObject(index) ?: continue
            val id = item.optLong("contract_id", 0L)
            if (id <= 0L || positions.containsKey(id)) continue
            val rawSymbol = item.optString("underlying", item.optString("symbol", ""))
            val symbol = rawSymbol.ifBlank { "RECOVERED-$id" }
            val type = item.optString("contract_type")
            val direction = if (type == "MULTDOWN") "DOWN" else "UP"
            positions[id] = OpenPosition(id, symbol, direction, item.optDouble("buy_price", 0.0))
            api.monitor(id) { result ->
                result.onSuccess { response ->
                    val sub = response.optJSONObject("subscription")?.optString("id")
                    synchronized(this) { positions[id]?.subscriptionId = sub }
                }.onFailure {
                    onStatus("Could not restore monitoring for contract $id: ${it.message}. Keep this position open in Deriv and reconnect.")
                }
            }
        }
        if (positions.isNotEmpty()) onStatus("Recovered ${positions.size} existing Deriv contract(s); monitoring each independently.")
    }

    @Synchronized fun hasOpenPosition(): Boolean = positions.isNotEmpty()
    @Synchronized fun openPositionCount(): Int = positions.size
    fun isRunning(): Boolean = running.get()
}
