package com.diablo.derivtrader

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val api = DerivApi()
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var pat: EditText
    private lateinit var appId: EditText
    private lateinit var account: EditText
    private lateinit var accountSpinner: Spinner
    private val demoAccounts = mutableListOf<JSONObject>()
    private lateinit var marketSpinner: Spinner
    private lateinit var stake: EditText
    private lateinit var multiplier: EditText
    private lateinit var takeProfit: EditText
    private lateinit var stopLoss: EditText
    private lateinit var dailyLoss: EditText
    private lateinit var maxLosses: EditText
    private lateinit var maxTrades: EditText
    private lateinit var cooldown: EditText
    private lateinit var log: TextView
    private lateinit var balanceView: TextView
    private lateinit var historyView: TextView
    private var controller: TradingController? = null
    private val markets = mutableListOf<Pair<String, String>>()
    private val selectedMarkets = linkedSetOf<String>()
    private var accountCurrency = "USD"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private var reconnectEnabled = false
    private val reconnectRunnable = Runnable { attemptReconnect() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = try {
            val key = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                this, "diablo_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Secure storage unavailable. Credentials were not saved.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        render()
        pat.setText(prefs.getString("pat", ""))
        appId.setText(prefs.getString("appId", ""))
        stake.setText(prefs.getString("stake", "1.00"))
        multiplier.setText(prefs.getString("multiplier", "10"))
        takeProfit.setText(prefs.getString("takeProfit", "2.00"))
        stopLoss.setText(prefs.getString("stopLoss", "1.00"))
        dailyLoss.setText(prefs.getString("dailyLoss", "5.00"))
        maxLosses.setText(prefs.getString("maxLosses", "3"))
        maxTrades.setText(prefs.getString("maxTrades", "20"))
        cooldown.setText(prefs.getString("cooldown", "30"))
        showHistory()
        api.onUpdate = { message ->
            runOnUiThread {
                controller?.onDerivMessage(message)
                message.optJSONObject("balance")?.let {
                    val amount = it.optDouble("balance", Double.NaN)
                    accountCurrency = it.optString("currency", accountCurrency)
                    if (amount.isFinite()) balanceView.text = "DEMO BALANCE: $amount $accountCurrency"
                }
                message.optJSONObject("proposal_open_contract")?.let {
                    show("Contract ${it.optLong("contract_id")} | ${it.optString("status")} | P/L ${it.optDouble("profit")}")
                }
                if (message.has("connection_error")) {
                    controller?.stop()
                    show("Connection lost: ${message.optString("connection_error")}. Scanner stopped; attempting safe reconnection. You must restart scanning manually.")
                    scheduleReconnect()
                }
            }
        }
    }

    private fun render() {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = 1
            setPadding(22, 18, 22, 24)
            setBackgroundColor(0xFF02040A.toInt())
        }
        fun label(text: String) {
            box.addView(TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(0xFF9EB0CA.toInt())
                setPadding(0, 12, 0, 2)
            })
        }
        fun field(hintText: String, default: String = "", secret: Boolean = false) =
            EditText(this).apply {
                hint = hintText
                setText(default)
                setTextColor(-1)
                setHintTextColor(0xFF8090AA.toInt())
                setSingleLine(true)
                if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                else inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
        fun button(title: String, action: () -> Unit) {
            box.addView(Button(this).apply { text = title; setOnClickListener { action() } })
        }

        box.addView(TextView(this).apply {
            text = "DIABLO DERIV AUTO TRADER"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(0xFFFF0A1F.toInt())
            setPadding(0, 8, 0, 16)
        })
        label("DERIV PERSONAL ACCESS TOKEN")
        pat = field("PAT with trade permission", secret = true); box.addView(pat)
        label("DERIV APP ID")
        appId = field("App ID"); box.addView(appId)
        label("DERIV DEMO OPTIONS ACCOUNT")
        account = field("Demo account ID").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }; box.addView(account)
        button("LOAD MY DERIV ACCOUNTS") { loadAccounts() }
        accountSpinner = Spinner(this)
        accountSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, mutableListOf("Load accounts first"))
        accountSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { }
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val item = demoAccounts.getOrNull(position) ?: return
                account.setText(item.optString("account_id"))
                accountCurrency = item.optString("currency", "USD")
                balanceView.text = "SELECTED DEMO: ${item.optString("account_id")} | ${item.optString("balance")} ${accountCurrency}"
            }
        }
        box.addView(accountSpinner)
        balanceView = TextView(this).apply { text = "DEMO BALANCE: not connected"; setTextColor(0xFF00FF7A.toInt()) }
        box.addView(balanceView)
        button("CONNECT DEMO ACCOUNT") { connect() }
        button("LOAD MARKETS") { loadMarkets() }
        label("SELECT MARKET")
        marketSpinner = Spinner(this)
        marketSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, mutableListOf("Load markets first"))
        box.addView(marketSpinner)
        button("SELECT UP TO 20 MARKETS FOR SCANNING") { showMarketPicker() }

        label("STAKE (ACCOUNT CURRENCY)")
        stake = field("1.00", "1.00"); box.addView(stake)
        label("MULTIPLIER")
        multiplier = field("10", "10"); box.addView(multiplier)
        label("TAKE PROFIT (ACCOUNT CURRENCY)")
        takeProfit = field("2.00", "2.00"); box.addView(takeProfit)
        label("STOP LOSS (ACCOUNT CURRENCY)")
        stopLoss = field("1.00", "1.00"); box.addView(stopLoss)
        label("MAX DAILY LOSS (ACCOUNT CURRENCY)")
        dailyLoss = field("5.00", "5.00"); box.addView(dailyLoss)
        label("MAX CONSECUTIVE LOSSES")
        maxLosses = field("3", "3"); box.addView(maxLosses)
        label("MAX TRADES PER DAY")
        maxTrades = field("20", "20"); box.addView(maxTrades)
        label("COOLDOWN SECONDS")
        cooldown = field("30", "30"); box.addView(cooldown)

        button("CHECK SELECTED MARKET MULTIPLIERS") { checkMarket() }
        button("START AUTOMATIC DEMO SCALPING") { startTrading() }
        button("EMERGENCY STOP / DISCONNECT") {
            reconnectEnabled = false
            mainHandler.removeCallbacks(reconnectRunnable)
            controller?.stop()
            if (controller?.hasOpenPosition() == true) {
                show("New entries stopped. Existing Demo position remains connected and monitored for its exit rules. Keep the app open until it closes.")
            } else {
                api.disconnect()
                show("Stopped and disconnected. No open position was being monitored.")
            }
        }
        log = TextView(this).apply {
            text = "DEMO ONLY — connect, load markets and select a symbol."
            textSize = 14f
            setTextColor(0xFF00FF7A.toInt())
            setPadding(8, 18, 8, 18)
        }
        box.addView(log)
        box.addView(TextView(this).apply {
            text = "TRADE HISTORY"
            textSize = 18f
            setTextColor(0xFFFF0A1F.toInt())
        })
        historyView = TextView(this).apply { setTextColor(-1); textSize = 12f }
        box.addView(historyView)
        box.addView(TextView(this).apply {
            text = "Real trading is locked. Multiplier contracts can lose the full stake. Run and verify Demo tests before considering real funds."
            setTextColor(0xFFFF9AA5.toInt())
            setPadding(0, 16, 0, 10)
        })
        scroll.addView(box)
        setContentView(scroll)
    }


    private fun loadAccounts() {
        val token = pat.text.toString().trim()
        val id = appId.text.toString().trim()
        if (token.isBlank() || id.isBlank()) {
            show("Enter PAT and App ID first.")
            return
        }
        show("Loading your Deriv accounts…")
        api.getAccounts(token, id) { result ->
            runOnUiThread {
                result.onSuccess { response ->
                    demoAccounts.clear()
                    val data = response.optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val item = data.optJSONObject(i) ?: continue
                            if (item.optString("account_type").equals("demo", true) ||
                                item.optString("group").equals("demo", true)) demoAccounts.add(item)
                        }
                    } else {
                        val single = response.optJSONObject("data")
                        if (single != null && single.optString("account_type").equals("demo", true)) demoAccounts.add(single)
                    }
                    accountSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                        demoAccounts.map { "${it.optString("account_id")} | ${it.optString("currency")} | balance ${it.optString("balance")}" })
                    show("Found ${demoAccounts.size} Demo account(s). Select one, then connect.")
                }.onFailure { show("Account discovery failed: ${it.message}") }
            }
        }
    }

    private fun connect() {
        val token = pat.text.toString().trim()
        val id = appId.text.toString().trim()
        val accountId = account.text.toString().trim()
        if (token.isBlank() || id.isBlank() || accountId.isBlank()) {
            show("Enter PAT, App ID and Demo account ID.")
            return
        }
        reconnectEnabled = true
        reconnectAttempts = 0
        prefs.edit().putString("pat", token).putString("appId", id).putString("accountId", accountId).apply()
        show("Requesting Deriv Demo connection…")
        api.connect(token, id, accountId, "demo") { result ->
            runOnUiThread {
                result.onSuccess {
                    reconnectAttempts = 0
                    mainHandler.removeCallbacks(reconnectRunnable)
                    show(it)
                    balanceView.text = "CONNECTED TO DEMO ACCOUNT: $accountId"
                    api.balance { balanceResult ->
                        runOnUiThread {
                            balanceResult.onSuccess { response ->
                                val b = response.optJSONObject("balance")
                                if (b != null) {
                                    accountCurrency = b.optString("currency", accountCurrency)
                                    balanceView.text = "DEMO BALANCE: ${b.optDouble("balance")} $accountCurrency"
                                }
                            }.onFailure { show("Balance request failed: ${it.message}") }
                        }
                    }
                    show("Authenticated Demo WebSocket connected. Load markets.")
                }.onFailure { show("Connection failed: ${it.message}") }
            }
        }
    }


    private fun scheduleReconnect() {
        if (!reconnectEnabled || reconnectAttempts >= 5) {
            if (reconnectAttempts >= 5) show("Automatic reconnect stopped after 5 attempts. Tap CONNECT DEMO ACCOUNT to retry.")
            return
        }
        val delay = minOf(30_000L, 1_000L shl reconnectAttempts)
        reconnectAttempts++
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun attemptReconnect() {
        if (!reconnectEnabled || isFinishing) return
        val token = prefs.getString("pat", "").orEmpty()
        val app = prefs.getString("appId", "").orEmpty()
        val accountId = prefs.getString("accountId", "").orEmpty()
        if (token.isBlank() || app.isBlank() || accountId.isBlank()) {
            show("Reconnect needs saved Demo credentials. Connect manually.")
            return
        }
        show("Reconnecting to Deriv Demo (attempt ${reconnectAttempts}/5)…")
        api.connect(token, app, accountId, "demo") { result ->
            runOnUiThread {
                result.onSuccess {
                    reconnectAttempts = 0
                    show("Deriv Demo reconnected. Checking account balance and open positions before restarting.")
                    api.balance { }
                    api.portfolio { portfolio ->
                        runOnUiThread {
                            portfolio.onSuccess { response ->
                                val count = response.optJSONObject("portfolio")?.optJSONArray("contracts")?.length() ?: 0
                                show("Reconnected. Existing open positions: $count. Scanner remains stopped; review positions, then start manually.")
                            }.onFailure { show("Reconnected, but portfolio check failed. Scanner remains stopped.") }
                        }
                    }
                }.onFailure {
                    show("Reconnect failed: ${it.message}")
                    scheduleReconnect()
                }
            }
        }
    }

    private fun loadMarkets() {
        api.markets { result ->
            runOnUiThread {
                result.onSuccess { response ->
                    val array = response.optJSONArray("active_symbols")
                    markets.clear()
                    if (array != null) for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val symbolCode = item.optString("symbol")
                        val display = item.optString("display_name", symbolCode)
                        if (symbolCode.isNotBlank()) markets.add(display to symbolCode)
                    }
                    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                        markets.map { "${it.first} (${it.second})" })
                    marketSpinner.adapter = adapter
                    show("Loaded ${markets.size} active markets. Multiplier support is checked separately per symbol.")
                }.onFailure { show("Market load failed: ${it.message}") }
            }
        }
    }

    private fun selectedSymbol(): String? {
        val index = marketSpinner.selectedItemPosition
        return markets.getOrNull(index)?.second
    }


    private fun showMarketPicker() {
        if (markets.isEmpty()) {
            show("Load markets first.")
            return
        }
        val labels = markets.map { "${it.first} (${it.second})" }.toTypedArray()
        val checked = BooleanArray(markets.size) { markets[it].second in selectedMarkets }
        android.app.AlertDialog.Builder(this)
            .setTitle("Choose up to 20 markets")
            .setMultiChoiceItems(labels, checked) { dialog, which, isChecked ->
                val code = markets[which].second
                if (isChecked) {
                    if (selectedMarkets.size >= 20) {
                        (dialog as? android.app.AlertDialog)?.listView?.setItemChecked(which, false)
                        show("Maximum 20 markets.")
                    } else selectedMarkets.add(code)
                } else selectedMarkets.remove(code)
            }
            .setPositiveButton("SAVE") { _, _ ->
                if (selectedMarkets.isEmpty()) show("No markets selected. Choose at least one.")
                else show("Selected ${selectedMarkets.size} market(s): ${selectedMarkets.joinToString()}")
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun checkMarket() {
        val selected = selectedSymbol() ?: run { show("Load and select a market first."); return }
        api.contractsFor(selected) { result ->
            runOnUiThread {
                result.onSuccess { response ->
                    val available = response.optJSONObject("contracts_for")?.optJSONArray("available")
                    val supported = (0 until (available?.length() ?: 0)).mapNotNull {
                        available?.optJSONObject(it)?.optString("contract_type")
                    }.filter { it == "MULTUP" || it == "MULTDOWN" }.distinct()
                    show("$selected multiplier support: ${supported.joinToString().ifBlank { "not available" }}")
                }.onFailure { show("Contract check failed: ${it.message}") }
            }
        }
    }

    private fun startTrading() {
        val selected = (selectedMarkets.toList().ifEmpty { listOfNotNull(selectedSymbol()) }).distinct()
        if (selected.isEmpty()) { show("Load and select at least one market first."); return }
        val stakeValue = stake.text.toString().toDoubleOrNull() ?: 0.0
        val multiplierValue = multiplier.text.toString().toIntOrNull() ?: 0
        val tp = takeProfit.text.toString().toDoubleOrNull() ?: 0.0
        val sl = stopLoss.text.toString().toDoubleOrNull() ?: 0.0
        val dailyLossValue = dailyLoss.text.toString().toDoubleOrNull() ?: 0.0
        val lossLimit = maxLosses.text.toString().toIntOrNull() ?: 0
        val tradesLimit = maxTrades.text.toString().toIntOrNull() ?: 0
        val cooldownSeconds = cooldown.text.toString().toLongOrNull() ?: 0L
        if (stakeValue <= 0 || multiplierValue <= 0 || tp <= 0 || sl <= 0 || dailyLossValue <= 0 || lossLimit <= 0 || tradesLimit <= 0 || cooldownSeconds < 0) {
            show("Stake, multiplier, take profit and stop loss must all be greater than zero.")
            return
        }
        if (!api.connected) { show("Connect Deriv Demo first."); return }
        prefs.edit()
            .putString("stake", stake.text.toString())
            .putString("multiplier", multiplier.text.toString())
            .putString("takeProfit", takeProfit.text.toString())
            .putString("stopLoss", stopLoss.text.toString())
            .putString("dailyLoss", dailyLoss.text.toString())
            .putString("maxLosses", maxLosses.text.toString())
            .putString("maxTrades", maxTrades.text.toString())
            .putString("cooldown", cooldown.text.toString())
            .apply()
        val dailyStats = calculateTodayRiskStats()
        val config = TraderConfig(
            candleSeconds = 5L,
            stake = stakeValue,
            multiplier = multiplierValue,
            currency = accountCurrency,
            takeProfit = tp,
            stopLoss = sl,
            dailyLossLimit = dailyLossValue,
            maxConsecutiveLosses = lossLimit,
            maxTradesPerDay = tradesLimit,
            cooldownMillis = cooldownSeconds * 1000L,
            initialRealizedPnl = dailyStats.first,
            initialConsecutiveLosses = dailyStats.second,
            initialTradesToday = dailyStats.third
        )
        show("Checking Deriv for existing open positions before starting…")
        api.portfolio { portfolioResult ->
            runOnUiThread {
                portfolioResult.onSuccess { response ->
                    val contracts = response.optJSONObject("portfolio")?.optJSONArray("contracts")
                    val openCount = contracts?.length() ?: 0
                    if (openCount > 0) {
                        show("Safety lock: Deriv reports $openCount open contract(s). Manage or close them in Deriv first; scanner will not add exposure.")
                        return@onSuccess
                    }
                    controller = TradingController(api, config, { status ->
                        runOnUiThread { show(status) }
                    }, { record ->
                        runOnUiThread { saveTrade(record) }
                    })
                    controller?.start(selected, accountCurrency)
                }.onFailure { show("Could not verify open positions. Scanner blocked for safety: ${it.message}") }
            }
        }
    }


    private fun calculateTodayRiskStats(): Triple<Double, Int, Int> {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis
        val all = JSONArray(prefs.getString("trade_history", "[]") ?: "[]")
        val closed = mutableListOf<Pair<Long, Double>>()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            if (item.optString("status") != "CLOSED") continue
            val time = item.optLong("time", 0L)
            if (time >= startOfDay) closed.add(time to item.optDouble("profit", 0.0))
        }
        closed.sortBy { it.first }
        val pnl = closed.sumOf { it.second }
        var losses = 0
        for (item in closed.asReversed()) {
            if (item.second < 0.0) losses++ else break
        }
        return Triple(pnl, losses, closed.size)
    }

    private fun saveTrade(record: TradeRecord) {
        val all = JSONArray(prefs.getString("trade_history", "[]") ?: "[]")
        all.put(JSONObject()
            .put("id", record.contractId)
            .put("symbol", record.symbol)
            .put("direction", record.direction)
            .put("buyPrice", record.buyPrice)
            .put("exitPrice", record.exitPrice)
            .put("profit", record.profit)
            .put("status", record.status)
            .put("detail", record.detail)
            .put("time", System.currentTimeMillis()))
        while (all.length() > 200) all.remove(0)
        prefs.edit().putString("trade_history", all.toString()).apply()
        showHistory()
    }

    private fun showHistory() {
        if (!::historyView.isInitialized) return
        val all = JSONArray(prefs.getString("trade_history", "[]") ?: "[]")
        val lines = (maxOf(0, all.length() - 15) until all.length()).map { i ->
            val item = all.optJSONObject(i) ?: JSONObject()
            "#${item.optLong("id")} ${item.optString("symbol")} ${item.optString("direction")} | ${item.optString("status")} | Entry ${item.optDouble("buyPrice")} | Exit ${item.optDouble("exitPrice")} | P/L ${item.optDouble("profit")}"
        }
        historyView.text = lines.reversed().joinToString("\n").ifBlank { "No trades recorded yet." }
    }

    private fun show(message: String) {
        if (::log.isInitialized) log.text = message
    }

    override fun onStop() {
        reconnectEnabled = false
        mainHandler.removeCallbacks(reconnectRunnable)
        super.onStop()
        controller?.stop()
        api.disconnect()
    }
}
