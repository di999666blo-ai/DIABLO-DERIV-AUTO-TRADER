package com.diablo.derivtrader

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import com.google.android.material.bottomnavigation.BottomNavigationView
import android.view.Menu
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val api = DerivApi()
    private lateinit var splash: DiabloSplashView
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var pat: EditText
    private lateinit var appId: EditText
    private lateinit var account: EditText
    private lateinit var accountSpinner: Spinner
    private val demoAccounts = mutableListOf<JSONObject>()
    private lateinit var modeSpinner: Spinner
    private var selectedMode = "demo"
    private var realUnlocked = false
    private lateinit var marketSpinner: Spinner
    private lateinit var stake: EditText
    private lateinit var multiplier: EditText
    private lateinit var takeProfit: EditText
    private lateinit var stopLoss: EditText
    private lateinit var dailyLoss: EditText
    private lateinit var maxLosses: EditText
    private lateinit var maxTrades: EditText
    private lateinit var cooldown: EditText
    private lateinit var maxPositions: EditText
    private lateinit var perMarketLimit: EditText
    private lateinit var progressionSwitch: Switch
    private lateinit var log: TextView
    private lateinit var balanceView: TextView
    private lateinit var historyView: TextView
    private lateinit var statsView: TextView
    private lateinit var liveActivityView: TextView
    private lateinit var portfolioView: TextView
    private lateinit var chartView: DerivCandleChartView
    private val liveActivityLines = mutableListOf<String>()
    private val lastContractActivityAt = mutableMapOf<Long, Long>()
    private var connectionStatusView: TextView? = null
    private var marketStatusView: TextView? = null
    private var controller: TradingController? = null
    private val markets = mutableListOf<Pair<String, String>>()
    private val selectedMarkets = linkedSetOf<String>()
    private val marketQuantities = linkedMapOf<String, Int>()
    private var accountCurrency = "USD"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private var reconnectEnabled = false
    private val reconnectRunnable = Runnable { attemptReconnect() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        splash = DiabloSplashView(this)
        setContentView(splash)
        splash.setProgress(12, "INITIALISING SECURE STORAGE")
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
        splash.setProgress(48, "CHECKING SAFETY SYSTEMS")
        realUnlocked = prefs.getBoolean("real_unlocked", false) &&
            prefs.getBoolean("demo_closed_trade_verified", false) && RiskManager.selfTest()
        if (!realUnlocked) prefs.edit().putBoolean("real_unlocked", false).apply()
        selectedMode = "demo"
        splash.setProgress(72, "BUILDING TRADING TERMINAL")
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
        maxPositions.setText(prefs.getString("maxPositions", "3"))
        perMarketLimit.setText(prefs.getString("perMarketLimit", "5"))
        progressionSwitch.isChecked = prefs.getBoolean("stakeProgressionEnabled", false)
        val savedQuantities = try { JSONObject(prefs.getString("market_quantities", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        savedQuantities.keys().forEach { key -> marketQuantities[key] = savedQuantities.optInt(key, 5).coerceIn(1, 100) }
        showHistory()
        api.onUpdate = { message ->
            runOnUiThread {
                controller?.onDerivMessage(message)
                message.optJSONObject("balance")?.let {
                    val amount = it.optDouble("balance", Double.NaN)
                    accountCurrency = it.optString("currency", accountCurrency)
                    if (amount.isFinite()) balanceView.text = "${api.connectedMode?.uppercase() ?: "ACCOUNT"} BALANCE: $amount $accountCurrency"
                }
                message.optJSONObject("proposal_open_contract")?.let { contract ->
                    val contractId = contract.optLong("contract_id")
                    val now = System.currentTimeMillis()
                    val previous = lastContractActivityAt[contractId] ?: 0L
                    if (contractId > 0L && (now - previous >= 1200L ||
                            contract.optBoolean("is_sold") || contract.optBoolean("is_expired"))) {
                        lastContractActivityAt[contractId] = now
                        appendLiveActivity(
                            "LIVE #$contractId  ${contract.optString("status", "OPEN")}  P/L ${String.format(java.util.Locale.US, "%+.2f", contract.optDouble("profit", 0.0))}"
                        )
                    }
                    show("Contract $contractId | ${contract.optString("status")} | P/L ${contract.optDouble("profit")}")
                }
                if (message.has("connection_error")) {
                    controller?.stop()
                    show("Connection lost: ${message.optString("connection_error")}. Scanner stopped; attempting safe reconnection. You must restart scanning manually.")
                    scheduleReconnect()
                }
            }
        }
    }


    private fun darkSpinnerAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(0xFFF4F7FF.toInt())
                    textSize = 14f
                    setPadding(12, 0, 12, 0)
                }
            }
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(0xFFF4F7FF.toInt())
                    setBackgroundColor(0xFF0E172A.toInt())
                    textSize = 14f
                    setPadding(16, 12, 16, 12)
                }
            }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun render() {
        val pageBackground = 0xFF02040A.toInt()
        val cardBackground = 0xFF0E172A.toInt()
        val red = 0xFFFF0A1F.toInt()
        val green = 0xFF00FF7A.toInt()
        val muted = 0xFF8EA1C0.toInt()
        val white = 0xFFF4F7FF.toInt()
        fun rounded(color: Int, stroke: Int = 0xFF1E345A.toInt(), radius: Float = 18f) =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = radius * resources.displayMetrics.density
                setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), stroke)
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackground)
            setPadding(16, 12, 16, 8)
        }
        val screenHost = FrameLayout(this)
        root.addView(screenHost, LinearLayout.LayoutParams(-1, 0, 1f))

        val pageTitles = linkedMapOf(
            "trade" to "TRADE NOW",
            "history" to "TRADE HISTORY",
            "portfolio" to "PORTFOLIO",
            "settings" to "SETTINGS / DERIV CONNECTION",
            "charts" to "CHARTS GALLERY",
            "risk" to "RISK MANAGER",
            "alerts" to "ALERTS / LIVE ACTIVITY",
            "guide" to "GUIDE MODE",
            "analytics" to "ANALYTICS"
        )
        val pageScreens = linkedMapOf<String, LinearLayout>()
        val pageContents = linkedMapOf<String, LinearLayout>()
        pageTitles.forEach { (id, title) ->
            val page = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(pageBackground)
                visibility = android.view.View.GONE
            }
            val pageHeader = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 10, 12, 8)
            }
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(Button(this).apply {
                text = "← BACK"
                isAllCaps = false
                textSize = 10f
                setTextColor(red)
                background = rounded(0xFF111111.toInt(), red, 8f)
                setOnClickListener { showDashboard("home") }
            }, LinearLayout.LayoutParams(-2, 42))
            headerRow.addView(TextView(this).apply {
                text = title
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(red)
                gravity = Gravity.CENTER
                setPadding(8, 0, 4, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            pageHeader.addView(headerRow)
            page.addView(pageHeader)
            val scroll = ScrollView(this).apply { isFillViewport = false }
            val pageContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 4, 12, 22)
            }
            scroll.addView(pageContent)
            page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            pageScreens[id] = page
            pageContents[id] = pageContent
        }
        var activePage = "trade"

        var homeScreenRef: LinearLayout? = null
        // Declare the navigation callback before wiring BACK and launcher click listeners.
        // A local function declared later is not visible to these earlier Kotlin lambdas.
        var showDashboard: (String) -> Unit = {}
        showDashboard = { id ->
            screenHost.removeAllViews()
            val selected = if (id == "home") homeScreenRef else pageScreens[id]
            if (selected != null) {
                selected.visibility = android.view.View.VISIBLE
                screenHost.addView(selected, FrameLayout.LayoutParams(-1, -1))
            }
        }

        val homeScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackground)
            setPadding(12, 14, 12, 12)
        }
        homeScreen.addView(TextView(this).apply {
            text = "😈 DIABLO"
            textSize = 27f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(red)
            setShadowLayer(12f, 0f, 0f, red)
            setPadding(0, 14, 0, 4)
        })
        homeScreen.addView(TextView(this).apply {
            text = "DERIV AUTO TRADER  •  HOME LAUNCHER"
            textSize = 9f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(red)
            setPadding(0, 0, 0, 14)
        })
        balanceView = TextView(this).apply {
            text = "● BOT STANDBY     DEMO BALANCE: NOT CONNECTED"
            textSize = 10f
            setTextColor(green)
            background = rounded(0xFF0A0A0A.toInt(), 0x66FF0F0F, 8f)
            setPadding(10, 12, 10, 12)
        }
        homeScreen.addView(balanceView)
        homeScreen.addView(TextView(this).apply {
            text = "SELECT A DASHBOARD"
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 8)
        })
        val launcherGrid = GridLayout(this).apply {
            columnCount = 3
            rowCount = 3
            useDefaultMargins = false
        }
        val launcherItems = listOf(
            Triple("⚡", "Trade Now", "trade"),
            Triple("⏱", "Trade History", "history"),
            Triple("💼", "Portfolio", "portfolio"),
            Triple("⚙", "Settings", "settings"),
            Triple("📊", "Charts Gallery", "charts"),
            Triple("🛡", "Risk Manager", "risk"),
            Triple("🔔", "Alerts", "alerts"),
            Triple("📖", "Guide Mode", "guide"),
            Triple("📈", "Analytics", "analytics")
        )
        launcherItems.forEach { (icon, labelText, pageId) ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(0xFF0F0F0F.toInt(), red, 10f)
                setPadding(4, 15, 4, 15)
                isClickable = true
                isFocusable = true
                setOnClickListener { showDashboard(pageId) }
            }
            tile.addView(TextView(this).apply {
                text = icon
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(red)
                setShadowLayer(10f, 0f, 0f, red)
            })
            tile.addView(TextView(this).apply {
                text = labelText
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(white)
                setPadding(0, 6, 0, 0)
            })
            launcherGrid.addView(tile, GridLayout.LayoutParams().apply {
                width = 0
                height = -2
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(4, 4, 4, 4)
            })
        }
        homeScreen.addView(launcherGrid, LinearLayout.LayoutParams(-1, -2))
        homeScreen.addView(TextView(this).apply {
            text = "Each icon opens its own dashboard. Use BACK to return to the launcher."
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(4, 12, 4, 4)
        })
        homeScreenRef = homeScreen
        screenHost.addView(homeScreen, FrameLayout.LayoutParams(-1, -1))

        fun section(title: String, subtitle: String = ""): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(cardBackground)
                setPadding(16, 15, 16, 16)
            }
            card.addView(TextView(this).apply {
                text = title
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(red)
            })
            if (subtitle.isNotBlank()) card.addView(TextView(this).apply {
                text = subtitle
                textSize = 10f
                setTextColor(muted)
                setPadding(0, 3, 0, 10)
            })
            pageContents[activePage]?.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
            return card
        }
        fun label(parent: LinearLayout, text: String) {
            parent.addView(TextView(this).apply {
                this.text = text.uppercase()
                textSize = 10f
                letterSpacing = 0.07f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(muted)
                setPadding(0, 11, 0, 4)
            })
        }
        fun field(parent: LinearLayout, hintText: String, default: String = "", secret: Boolean = false): EditText {
            val edit = EditText(this).apply {
                hint = hintText
                setText(default)
                setTextColor(white)
                setHintTextColor(0xFF647795.toInt())
                textSize = 14f
                setSingleLine(true)
                background = rounded(0xFF08111F.toInt(), 0xFF263B5B.toInt(), 10f)
                setPadding(12, 2, 12, 2)
                // Deriv credentials and IDs may contain letters and digits.
                // Keep every connection field on a text keyboard; mask only the PAT.
                inputType = if (secret)
                    android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                else
                    android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            parent.addView(edit, LinearLayout.LayoutParams(-1, 48).apply { bottomMargin = 3 })
            return edit
        }
        fun action(parent: LinearLayout, title: String, primary: Boolean = false, onClick: () -> Unit) {
            parent.addView(Button(this).apply {
                text = title
                isAllCaps = false
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(white)
                background = rounded(if (primary) 0xFF8F1028.toInt() else 0xFF14243B.toInt(),
                    if (primary) red else 0xFF29466C.toInt(), 12f)
                setPadding(12, 4, 12, 4)
                minHeight = 48
                minimumHeight = 48
                isClickable = true
                isFocusable = true
                stateListAnimator = null
                setOnClickListener {
                    show("Button pressed: $title")
                    Toast.makeText(this@MainActivity, "$title", Toast.LENGTH_SHORT).show()
                    onClick()
                }
            }, LinearLayout.LayoutParams(-1, 48).apply { topMargin = 8 })
        }

        activePage = "trade"
        val overview = section("LIVE OVERVIEW", "Live account state and trading engine status")
        val overviewGrid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun stat(title: String, value: String, valueColor: Int): LinearLayout {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(0xFF08111F.toInt(), 0xFF1E345A.toInt(), 12f)
                setPadding(11, 10, 8, 10)
            }
            tile.addView(TextView(this).apply { text = title; textSize = 9f; setTextColor(muted) })
            tile.addView(TextView(this).apply {
                text = value
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(valueColor)
                setPadding(0, 5, 0, 0)
            })
            return tile
        }
        overviewGrid.addView(stat("ENGINE", "STANDBY", green), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 6 })
        overviewGrid.addView(stat("CONTRACT", "MULTUP / MULTDOWN", white), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 6 })
        overview.addView(overviewGrid)
        overview.addView(TextView(this).apply {
            text = "No trade is placed until you connect, select markets, pass the risk checks and start the engine."
            textSize = 11f
            setTextColor(muted)
            setPadding(2, 10, 2, 0)
        })

        activePage = "settings"
        val connection = section("01  /  DERIV CONNECTION", "Deriv PAT is stored encrypted on this phone. Never paste a GitHub PAT into the app or chat.")
        label(connection, "Account mode")
        modeSpinner = Spinner(this)
        modeSpinner.adapter = darkSpinnerAdapter(
            if (realUnlocked) listOf("DEMO ACCOUNT", "REAL — UNLOCKED")
            else listOf("DEMO ACCOUNT", "REAL — LOCKED")
        )
        modeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { }
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position == 1 && !realUnlocked) {
                    Toast.makeText(this@MainActivity, "Real mode locked until Demo verification and explicit approval.", Toast.LENGTH_LONG).show()
                    modeSpinner.setSelection(0)
                    selectedMode = "demo"
                } else selectedMode = if (position == 1) "real" else "demo"
            }
        }
        connection.addView(modeSpinner, LinearLayout.LayoutParams(-1, 48))
        action(connection, if (realUnlocked) "REAL MODE UNLOCKED" else "VERIFY / UNLOCK REAL MODE") {
            requestRealUnlock()
        }
        label(connection, "Deriv Personal Access Token (PAT)")
        pat = field(connection, "Paste your Deriv PAT with trade permission", secret = true)
        label(connection, "Deriv app ID")
        appId = field(connection, "App ID")
        label(connection, "Selected account ID")
        account = field(connection, "Deriv Options account ID").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }
        action(connection, "LOAD MY DERIV ACCOUNTS") { loadAccounts() }
        connectionStatusView = TextView(this).apply {
            text = "Ready. Tap LOAD MY DERIV ACCOUNTS to retrieve accounts."
            textSize = 11f
            setTextColor(green)
            setPadding(4, 9, 4, 4)
        }
        connection.addView(connectionStatusView)
        accountSpinner = Spinner(this)
        accountSpinner.adapter = darkSpinnerAdapter(listOf("Load accounts first"))
        accountSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { }
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val item = demoAccounts.getOrNull(position) ?: return
                account.setText(item.optString("account_id"))
                accountCurrency = item.optString("currency", "USD")
                balanceView.text = "SELECTED ${accountModeOf(item).uppercase()}: ${item.optString("account_id")}  •  ${item.optString("balance")} $accountCurrency"
            }
        }
        connection.addView(accountSpinner, LinearLayout.LayoutParams(-1, 48).apply { topMargin = 7 })
        action(connection, "CONNECT SELECTED ACCOUNT", true) { connect() }
        connectionStatusView?.text = "Ready. Load accounts, select the correct account, then connect."

        activePage = "trade"
        val marketsCard = section("02  /  MARKET SCANNER", "Choose active Deriv symbols. Multiplier availability is checked per market.")
        marketSpinner = Spinner(this)
        marketSpinner.adapter = darkSpinnerAdapter(listOf("Load markets first"))
        marketsCard.addView(marketSpinner, LinearLayout.LayoutParams(-1, 48).apply { topMargin = 6 })
        action(marketsCard, "LOAD ACTIVE MARKETS") { loadMarkets() }
        action(marketsCard, "SELECT MARKETS (UP TO 20)") { showMarketPicker() }
        action(marketsCard, "SET INDIVIDUAL MARKET QUANTITIES") { showMarketQuantityDialog() }
        action(marketsCard, "CHECK MULTUP / MULTDOWN SUPPORT") { checkMarket() }
        marketStatusView = TextView(this).apply {
            text = "Markets not loaded yet."
            textSize = 11f
            setTextColor(muted)
            setPadding(4, 9, 4, 4)
        }
        marketsCard.addView(marketStatusView)

        activePage = "risk"
        val risk = section("03  /  RISK ENGINE", "Set limits before starting. Values are in the account currency unless stated.")
        label(risk, "Stake per trade")
        stake = field(risk, "Stake", "1.00")
        label(risk, "Multiplier")
        multiplier = field(risk, "Multiplier", "10")
        label(risk, "Take profit")
        takeProfit = field(risk, "Take profit", "2.00")
        label(risk, "Stop loss")
        stopLoss = field(risk, "Stop loss", "1.00")
        label(risk, "Maximum daily loss")
        dailyLoss = field(risk, "Max daily loss", "5.00")
        label(risk, "Maximum consecutive losses")
        maxLosses = field(risk, "Maximum consecutive losses", "3")
        label(risk, "Maximum trades per day")
        maxTrades = field(risk, "Maximum trades per day", "20")
        label(risk, "Cooldown between entries (seconds)")
        cooldown = field(risk, "Cooldown seconds", "30")
        label(risk, "Maximum simultaneous open contracts (1–20)")
        maxPositions = field(risk, "Max open contracts", "3")
        label(risk, "Maximum trades per selected market (1–100)")
        perMarketLimit = field(risk, "Per-market trade quantity", "5")
        progressionSwitch = Switch(this).apply {
            text = "WIN-BASED STAKE PROGRESSION  1 → 2 → 3 → 4 → 5"
            setTextColor(white)
            textSize = 11f
            isChecked = false
            setPadding(2, 12, 2, 8)
        }
        risk.addView(progressionSwitch)

        activePage = "trade"
        val execution = section("04  /  EXECUTION", "Multiple independent contracts. Emergency stop prevents new entries.")
        action(execution, "START AUTOMATIC SCALPING", true) { startTrading() }
        action(execution, "EMERGENCY STOP / DISCONNECT") {
            reconnectEnabled = false
            mainHandler.removeCallbacks(reconnectRunnable)
            controller?.stop()
            if (controller?.hasOpenPosition() == true) {
                show("New entries stopped. Existing ${selectedMode.uppercase()} position remains connected and monitored for its exit rules. Keep the app open until it closes.")
            } else {
                api.disconnect()
                show("Stopped and disconnected. No open position was being monitored.")
            }
        }
        log = TextView(this).apply {
            text = "SYSTEM STANDBY  •  Connect your Deriv account to begin."
            textSize = 12f
            setTextColor(green)
            background = rounded(0xFF071A17.toInt(), 0xFF126044.toInt(), 12f)
            setPadding(12, 12, 12, 12)
        }
        execution.addView(log, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })
        execution.addView(TextView(this).apply {
            text = "Real mode remains locked until a genuine closed Demo trade is recorded, local risk self-tests pass and you explicitly approve. Multiplier contracts can lose the full stake."
            textSize = 10f
            setTextColor(0xFFFFA0AA.toInt())
            setPadding(2, 12, 2, 0)
        })

        activePage = "alerts"
        val live = section("05  /  LIVE BOT ACTIVITY", "Watch real Deriv contract events: entry, live P/L, close, win or loss.")
        liveActivityView = TextView(this).apply {
            text = "WAITING FOR BOT EVENTS. Start the scanner in Demo to see live activity here."
            setTextColor(white)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(12, 12, 12, 12)
            background = rounded(0xFF08111F.toInt(), 0xFF1E345A.toInt(), 12f)
            minHeight = 120
        }
        live.addView(liveActivityView)

        activePage = "analytics"
        val stats = section("06  /  PERFORMANCE", "Calculated from locally recorded settled contracts")
        statsView = TextView(this).apply {
            setTextColor(white)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(12, 12, 12, 12)
            background = rounded(0xFF08111F.toInt(), 0xFF1E345A.toInt(), 12f)
        }
        stats.addView(statsView)

        activePage = "history"
        val history = section("07  /  TRADE HISTORY", "Most recent recorded contracts")
        historyView = TextView(this).apply {
            setTextColor(white)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(12, 12, 12, 12)
            background = rounded(0xFF08111F.toInt(), 0xFF1E345A.toInt(), 12f)
        }
        history.addView(historyView)

        activePage = "portfolio"
        val portfolio = section("PORTFOLIO / OPEN POSITIONS", "Actual open contracts returned by your connected Deriv account.")
        portfolioView = TextView(this).apply {
            text = "Connect your Deriv account, then refresh to load live open positions."
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(white)
            setPadding(12, 12, 12, 12)
            background = rounded(0xFF08111F.toInt(), 0xFF1E345A.toInt(), 12f)
            minHeight = 100
        }
        portfolio.addView(portfolioView)
        action(portfolio, "REFRESH OPEN POSITIONS") { refreshPortfolio() }

        activePage = "charts"
        val chartsInfo = section("CHARTS GALLERY", "Real Deriv candles - no simulated prices")
        chartsInfo.addView(TextView(this).apply {
            text = "Connect your Deriv account, load active markets in Trade Now, then select a market and timeframe below."
            textSize = 11f
            setTextColor(muted)
            setPadding(2, 4, 2, 8)
        })
        val chartTimeframe = Spinner(this)
        val chartPeriods = listOf("1 MINUTE" to 60, "2 MINUTES" to 120, "3 MINUTES" to 180, "5 MINUTES" to 300, "15 MINUTES" to 900, "1 HOUR" to 3600)
        chartTimeframe.adapter = darkSpinnerAdapter(chartPeriods.map { it.first })
        chartsInfo.addView(chartTimeframe, LinearLayout.LayoutParams(-1, 48))
        chartView = DerivCandleChartView(this)
        chartsInfo.addView(chartView, LinearLayout.LayoutParams(-1, 260).apply { topMargin = 10 })
        action(chartsInfo, "LOAD / REFRESH DERIV CANDLES", true) {
            val symbol = selectedSymbol()
            if (symbol == null) {
                chartView.setLoading("Load active markets in Trade Now first.")
            } else if (!api.connected) {
                chartView.setLoading("Connect your Deriv account to load authenticated candles.")
            } else {
                val period = chartPeriods.getOrNull(chartTimeframe.selectedItemPosition) ?: chartPeriods.first()
                chartView.setLoading("Loading " + symbol + " " + period.first + " candles from Deriv...")
                api.candleHistory(symbol, period.second) { result ->
                    runOnUiThread {
                        result.onSuccess { response ->
                            val rows = response.optJSONArray("candles")
                            if (rows == null) chartView.setLoading("Deriv returned no candle array.")
                            else chartView.setCandles(symbol, period.first, rows)
                        }.onFailure { chartView.setLoading("Chart request failed: " + it.message) }
                    }
                }
            }
        }
        activePage = "guide"
        val guide = section("HOW TO START SAFELY", "Follow these steps in order")
        listOf(
            "1. Open Settings and enter your Deriv Personal Access Token (PAT) and App ID.",
            "2. Load accounts, select a DEMO account, then connect.",
            "3. Open Trade Now, load active markets and select the symbols to scan.",
            "4. Open Risk Manager and set stake, take profit, stop loss and exposure limits.",
            "5. Return to Trade Now and start automatic scalping only after the feed is live.",
            "6. Monitor Alerts, Portfolio, Trade History and Analytics. Emergency Stop blocks new entries."
        ).forEach { instruction ->
            guide.addView(TextView(this).apply {
                text = instruction
                textSize = 11f
                setTextColor(white)
                background = rounded(0xFF0C0C0C.toInt(), 0x66FF0F0F, 8f)
                setPadding(12, 12, 12, 12)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
        }

        showDashboard("home")

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
        splash.setProgress(92, "STARTUP COMPLETE")
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                splash.setProgress(100, "SYSTEM READY")
                setContentView(root)
            }
        }, 650L)
    }

    private fun requestRealUnlock() {
        if (realUnlocked) { show("Real mode is already unlocked on this installation."); return }
        val demoTradeVerified = prefs.getBoolean("demo_closed_trade_verified", false) &&
            prefs.getString("demo_verified_account_id", "") == prefs.getString("accountId", "") &&
            prefs.getString("accountMode", "demo") == "demo"
        if (!demoTradeVerified) {
            show("Real remains locked: first complete at least one genuine Demo MULTUP/MULTDOWN trade and let Deriv report it CLOSED in the app history.")
            return
        }
        if (!RiskManager.selfTest()) {
            show("Real remains locked: one or more local risk-control self-tests failed.")
            return
        }
        val checks = arrayOf("I checked that the closed Demo trade also appears in my Deriv account history.", "I understand multiplier trades can lose the full stake and Real mode uses real money.")
        val selected = BooleanArray(checks.size)
        android.app.AlertDialog.Builder(this)
            .setTitle("Final Real-mode approval")
            .setMultiChoiceItems(checks, selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton("UNLOCK REAL MODE") { _, _ ->
                if (!selected.all { it }) {
                    show("Real remains locked: confirm both requirements after checking your Demo results.")
                } else {
                    realUnlocked = true
                    prefs.edit().putBoolean("real_unlocked", true).apply()
                    modeSpinner.adapter = darkSpinnerAdapter(listOf("DEMO", "REAL — UNLOCKED"))
                    modeSpinner.setSelection(0)
                    selectedMode = "demo"
                    show("Real mode unlocked after a recorded Demo close and passing risk self-tests. Select your Real account and connect manually; trading will not start automatically.")
                }
            }
            .setNegativeButton("KEEP DEMO ONLY", null)
            .show()
    }

    private fun accountModeOf(item: JSONObject): String {
        val declared = item.optString("account_type", item.optString("account_category", "")).lowercase()
        if (declared.contains("demo") || declared.contains("virtual")) return "demo"
        if (declared.contains("real") || declared.contains("financial")) return "real"
        val accountId = item.optString("account_id").uppercase()
        return when {
            accountId.startsWith("VRTC") -> "demo"
            accountId.startsWith("CR") -> "real"
            else -> "unknown"
        }
    }

    private fun accountModeOfId(accountId: String): String = when {
        accountId.startsWith("VRTC", ignoreCase = true) -> "demo"
        accountId.startsWith("CR", ignoreCase = true) -> "real"
        else -> "unknown"
    }

    private fun loadAccounts() {
        val token = pat.text.toString().trim()
        val id = appId.text.toString().trim()
        if (token.isBlank() || id.isBlank()) {
            showConnectionStatus("Enter your Deriv Personal Access Token and App ID first.")
            return
        }
        showConnectionStatus("⏳ Contacting Deriv and loading your accounts…")
        api.getAccounts(token, id) { result ->
            runOnUiThread {
                result.onSuccess { response ->
                    demoAccounts.clear()
                    val data = response.optJSONArray("data") ?: response.optJSONArray("accounts")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val item = data.optJSONObject(i) ?: continue
                            if (item.optString("account_id").isNotBlank()) demoAccounts.add(item)
                        }
                    } else {
                        val single = response.optJSONObject("data") ?: response.optJSONObject("account")
                        if (single != null && single.optString("account_id").isNotBlank()) demoAccounts.add(single)
                    }
                    accountSpinner.adapter = darkSpinnerAdapter(
                        demoAccounts.map { "${accountModeOf(it)} | ${it.optString("account_id")} | ${it.optString("currency")} | balance ${it.optString("balance")}" })
                    showConnectionStatus(if (demoAccounts.isEmpty()) "Deriv responded, but no account records were found. Check that this PAT belongs to the expected Deriv account." else "✅ Found ${demoAccounts.size} account(s). Select the account matching DEMO mode.")
                }.onFailure { showConnectionStatus("❌ Account discovery failed: ${it.message}") }
            }
        }
    }

    private fun connect() {
        val token = pat.text.toString().trim()
        val id = appId.text.toString().trim()
        val accountId = account.text.toString().trim()
        if (token.isBlank() || id.isBlank() || accountId.isBlank()) {
            show("Enter PAT, App ID and the account ID.")
            return
        }
        if (selectedMode == "real" && !realUnlocked) { show("Real mode is locked until verified Demo testing and explicit approval."); return }
        val accountRecord = demoAccounts.firstOrNull { it.optString("account_id") == accountId }
        val detectedMode = accountRecord?.let { accountModeOf(it) } ?: accountModeOfId(accountId)
        if (detectedMode == "unknown") { show("Account type could not be verified. Tap LOAD MY DERIV ACCOUNTS and select the account from the list."); return }
        if (detectedMode != selectedMode) { show("Account/mode mismatch: selected account is $detectedMode, but ${selectedMode.uppercase()} is selected. Choose the matching account."); return }
        reconnectEnabled = selectedMode == "demo"
        reconnectAttempts = 0
        prefs.edit().putString("pat", token).putString("appId", id).putString("accountId", accountId).putString("accountMode", selectedMode).apply()
        showConnectionStatus("⏳ Requesting authenticated Deriv ${selectedMode.uppercase()} connection…")
        api.connect(token, id, accountId, selectedMode) { result ->
            runOnUiThread {
                result.onSuccess {
                    reconnectAttempts = 0
                    mainHandler.removeCallbacks(reconnectRunnable)
                    showConnectionStatus("✅ $it")
                    balanceView.text = "CONNECTED TO ${selectedMode.uppercase()} ACCOUNT: $accountId"
                    api.balance { balanceResult ->
                        runOnUiThread {
                            balanceResult.onSuccess { response ->
                                val b = response.optJSONObject("balance")
                                if (b != null) {
                                    accountCurrency = b.optString("currency", accountCurrency)
                                    balanceView.text = "${selectedMode.uppercase()} BALANCE: ${b.optDouble("balance")} $accountCurrency"
                                }
                            }.onFailure { showConnectionStatus("Connected, but balance request failed: ${it.message}") }
                        }
                    }
                    showConnectionStatus("✅ Authenticated Deriv ${selectedMode.uppercase()} WebSocket connected. Load markets next.")
                    refreshPortfolio()
                }.onFailure { showConnectionStatus("❌ Connection failed: ${it.message}") }
            }
        }
    }


    private fun refreshPortfolio() {
        if (!::portfolioView.isInitialized) return
        if (!api.connected) {
            portfolioView.text = "NOT CONNECTED. Connect your Deriv account to retrieve its portfolio."
            return
        }
        portfolioView.text = "Loading open positions from Deriv…"
        api.portfolio { result ->
            runOnUiThread {
                result.onSuccess { response ->
                    val portfolio = response.optJSONObject("portfolio")
                    val contracts = portfolio?.optJSONArray("contracts")
                    val lines = mutableListOf<String>()
                    lines.add("ACCOUNT: ${api.connectedMode?.uppercase() ?: "CONNECTED"}")
                    lines.add("OPEN CONTRACTS: ${contracts?.length() ?: 0}")
                    if (contracts != null) {
                        for (i in 0 until contracts.length()) {
                            val item = contracts.optJSONObject(i) ?: continue
                            val id = item.optLong("contract_id")
                            val symbol = item.optString("underlying", item.optString("symbol", "Unknown"))
                            val type = item.optString("contract_type", "CONTRACT")
                            val buy = item.optDouble("buy_price", 0.0)
                            val profit = item.optDouble("profit", 0.0)
                            lines.add("#$id  $symbol  $type")
                            lines.add(String.format(java.util.Locale.US, "   BUY %.2f   LIVE P/L %+.2f", buy, profit))
                        }
                    }
                    portfolioView.text = if (contracts == null || contracts.length() == 0)
                        lines.joinToString("\n") + "\nNo currently open positions on this account."
                    else lines.joinToString("\n")
                }.onFailure {
                    portfolioView.text = "Portfolio request failed: ${it.message}"
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (!reconnectEnabled || reconnectAttempts >= 5) {
            if (reconnectAttempts >= 5) show("Automatic reconnect stopped after 5 attempts. Tap CONNECT SELECTED ACCOUNT MODE to retry.")
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
            show("Reconnect needs saved Deriv credentials. Connect manually.")
            return
        }
        val mode = prefs.getString("accountMode", "demo").orEmpty()
        if (mode != "demo") {
            show("Automatic reconnect blocked for Real mode. Reconnect manually after checking your Deriv account.")
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
        // The active-symbol catalogue is public market data, not account-scoped.
        // It must remain loadable even before an account is connected.
        showMarketStatus("⏳ Loading Deriv's public market catalogue…")
        api.markets { result ->
            runOnUiThread {
                result.onSuccess { response ->
                    val array = response.optJSONArray("active_symbols")
                    markets.clear()
                    if (array != null) for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        // The current Deriv Options API renamed the legacy fields:
                        // symbol -> underlying_symbol; display_name -> underlying_symbol_name.
                        // Keep legacy fallbacks so older compatible responses still work.
                        val symbolCode = item.optString("underlying_symbol")
                            .ifBlank { item.optString("symbol") }
                        val display = item.optString("underlying_symbol_name")
                            .ifBlank { item.optString("display_name") }
                            .ifBlank { symbolCode }
                        if (symbolCode.isNotBlank()) markets.add(display to symbolCode)
                    }
                    val adapter = darkSpinnerAdapter(markets.map { "${it.first} (${it.second})" })
                    marketSpinner.adapter = adapter
                    showMarketStatus(if (markets.isEmpty()) "Deriv public market catalogue returned zero symbols. Check the network and retry." else "✅ Loaded ${markets.size} public markets. Choose up to 20; account-specific MULTUP/MULTDOWN support is checked separately.")
                }.onFailure { showMarketStatus("❌ Market load failed: ${it.message}") }
            }
        }
    }

    private fun selectedSymbol(): String? {
        val index = marketSpinner.selectedItemPosition
        return markets.getOrNull(index)?.second
    }


    private fun showMarketPicker() {
        if (markets.isEmpty()) {
            showMarketStatus("Market list is empty. Loading Deriv public market catalogue now; tap SELECT MARKETS again when loading finishes.")
            loadMarkets()
            return
        }
        val labels = markets.map { "${it.first} (${it.second})" }.toTypedArray()
        val checked = BooleanArray(markets.size) { markets[it].second in selectedMarkets }
        android.app.AlertDialog.Builder(this)
            .setTitle("Choose up to 20 markets  •  ${markets.size} available")
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


    private fun showMarketQuantityDialog() {
        val selected = selectedMarkets.toList().ifEmpty { listOfNotNull(selectedSymbol()) }
        if (selected.isEmpty()) { showMarketStatus("Select at least one market first. Load the active market list, then choose markets."); return }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 8, 20, 8)
        }
        val inputs = linkedMapOf<String, EditText>()
        selected.forEach { symbol ->
            panel.addView(TextView(this).apply {
                text = symbol
                setTextColor(0xFFF4F7FF.toInt())
                textSize = 13f
                setPadding(0, 8, 0, 2)
            })
            inputs[symbol] = EditText(this).apply {
                setText((marketQuantities[symbol] ?: 5).toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setTextColor(0xFFF4F7FF.toInt())
                hint = "1–100 trades"
            }
            panel.addView(inputs[symbol], LinearLayout.LayoutParams(-1, 48))
        }
        val scroll = ScrollView(this).apply { addView(panel) }
        android.app.AlertDialog.Builder(this)
            .setTitle("PER-MARKET QUANTITY")
            .setMessage("Set an independent maximum number of entries per market for this run.")
            .setView(scroll)
            .setPositiveButton("SAVE") { _, _ ->
                val parsed = inputs.mapValues { it.value.text.toString().toIntOrNull() ?: 0 }
                if (parsed.values.any { it !in 1..100 }) {
                    show("Every market quantity must be between 1 and 100. Nothing saved.")
                } else {
                    marketQuantities.putAll(parsed)
                    val saved = JSONObject()
                    marketQuantities.forEach { (key, value) -> saved.put(key, value) }
                    prefs.edit().putString("market_quantities", saved.toString()).apply()
                    show("Individual market quantities saved.")
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun checkMarket() {
        val symbols = selectedMarkets.toList().ifEmpty { listOfNotNull(selectedSymbol()) }
        if (symbols.isEmpty()) {
            showMarketStatus("Load active markets and select at least one pair before checking multiplier support.")
            return
        }
        showMarketStatus("Checking MULTUP / MULTDOWN support for ${symbols.size} selected market(s)…")
        val results = mutableListOf<String>()
        fun checkNext(index: Int) {
            if (index >= symbols.size) {
                showMarketStatus(results.joinToString("\n"))
                return
            }
            val symbol = symbols[index]
            api.contractsFor(symbol) { result ->
                runOnUiThread {
                    result.onSuccess { response ->
                        val available = response.optJSONObject("contracts_for")?.optJSONArray("available")
                        val supported = (0 until (available?.length() ?: 0)).mapNotNull {
                            available?.optJSONObject(it)?.optString("contract_type")
                        }.filter { it == "MULTUP" || it == "MULTDOWN" }.distinct()
                        results.add("$symbol: ${supported.joinToString().ifBlank { "NOT AVAILABLE" }}")
                    }.onFailure { results.add("$symbol: ERROR — ${it.message}") }
                    checkNext(index + 1)
                }
            }
        }
        checkNext(0)
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
        val positionLimit = maxPositions.text.toString().toIntOrNull() ?: 0
        val marketLimit = perMarketLimit.text.toString().toIntOrNull() ?: 0
        if (stakeValue <= 0 || multiplierValue <= 0 || tp <= 0 || sl <= 0 || dailyLossValue <= 0 || lossLimit <= 0 || tradesLimit <= 0 || cooldownSeconds < 0 || positionLimit !in 1..20 || marketLimit !in 1..100) {
            show("Stake, multiplier, take profit and stop loss must all be greater than zero.")
            return
        }
        if (!api.connected) { show("Connect the selected Deriv account first."); return }
        if (selectedMode == "real" && !realUnlocked) { show("Real trading is locked until verified Demo tests and explicit approval."); return }
        if (prefs.getString("accountMode", "demo") != selectedMode || api.connectedMode != selectedMode) { show("Safety lock: the authenticated Deriv account does not match the selected account mode."); return }
        prefs.edit()
            .putString("stake", stake.text.toString())
            .putString("multiplier", multiplier.text.toString())
            .putString("takeProfit", takeProfit.text.toString())
            .putString("stopLoss", stopLoss.text.toString())
            .putString("dailyLoss", dailyLoss.text.toString())
            .putString("maxLosses", maxLosses.text.toString())
            .putString("maxTrades", maxTrades.text.toString())
            .putString("cooldown", cooldown.text.toString())
            .putString("maxPositions", maxPositions.text.toString())
            .putString("perMarketLimit", perMarketLimit.text.toString())
            .putBoolean("stakeProgressionEnabled", progressionSwitch.isChecked)
            .apply()
        val dailyStats = calculateTodayRiskStats()
        val config = TraderConfig(
            accountMode = selectedMode,
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
            initialTradesToday = dailyStats.third,
            maxOpenPositions = positionLimit,
            maxTradesPerMarket = marketLimit,
            stakeProgressionEnabled = progressionSwitch.isChecked,
            initialProgressionIndex = prefs.getInt("stake_progression_index", 0).coerceIn(0, 4),
            marketTradeLimits = selected.associateWith { marketQuantities[it] ?: marketLimit }
        )
        show("Checking Deriv for existing open positions before starting…")
        api.portfolio { portfolioResult ->
            runOnUiThread {
                portfolioResult.onSuccess { response ->
                    val contracts = response.optJSONObject("portfolio")?.optJSONArray("contracts")
                    val openCount = contracts?.length() ?: 0
                    controller = TradingController(api, config, { status ->
                        runOnUiThread {
                            show(status)
                            val lower = status.lowercase()
                            if (listOf("opened", "settled", "closing", "close request", "feed stale",
                                    "entry blocked", "risk lock", "scanner stopped", "live tick feed",
                                    "contract checks complete", "buy rejected", "proposal rejected").any { lower.contains(it) }) {
                                appendLiveActivity(status)
                            }
                        }
                    }, { record ->
                        runOnUiThread {
                            val outcome = when {
                                record.status == "OPEN" -> "TRADE OPENED"
                                record.profit > 0.0 -> "TRADE CLOSED — WIN"
                                record.profit < 0.0 -> "TRADE CLOSED — LOSS"
                                else -> "TRADE CLOSED — BREAKEVEN"
                            }
                            appendLiveActivity("$outcome | #${record.contractId} ${record.symbol} ${record.direction} | P/L ${String.format(java.util.Locale.US, "%+.2f", record.profit)}")
                            saveTrade(record)
                            refreshPortfolio()
                        }
                    })
                    if (openCount > 0) {
                        show("Restoring $openCount existing Deriv contract(s) before scanning. No duplicate positions will be opened.")
                        controller?.adoptOpenPositions(contracts ?: JSONArray())
                    }
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
        if (record.status == "CLOSED") {
            prefs.edit().putInt("stake_progression_index", record.progressionIndex.coerceIn(0, 4)).apply()
        }
        if (record.status == "CLOSED" && api.connectedMode == "demo" &&
            (record.direction == "UP" || record.direction == "DOWN")) {
            prefs.edit()
                .putBoolean("demo_closed_trade_verified", true)
                .putString("demo_verified_account_id", prefs.getString("accountId", "").orEmpty())
                .apply()
        }
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
        if (::statsView.isInitialized) renderPerformanceStats(all)
    }

    private fun renderPerformanceStats(all: JSONArray) {
        val closed = (0 until all.length()).mapNotNull { i ->
            all.optJSONObject(i)?.takeIf { it.optString("status") == "CLOSED" }
        }
        val now = System.currentTimeMillis()
        val dayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val weekStart = now - 7L * 24L * 60L * 60L * 1000L
        val wins = closed.filter { it.optDouble("profit", 0.0) > 0.0 }
        val losses = closed.filter { it.optDouble("profit", 0.0) < 0.0 }
        val net = closed.sumOf { it.optDouble("profit", 0.0) }
        val today = closed.filter { it.optLong("time") >= dayStart }.sumOf { it.optDouble("profit", 0.0) }
        val week = closed.filter { it.optLong("time") >= weekStart }.sumOf { it.optDouble("profit", 0.0) }
        val avgWin = if (wins.isEmpty()) 0.0 else wins.sumOf { it.optDouble("profit", 0.0) } / wins.size
        val avgLoss = if (losses.isEmpty()) 0.0 else losses.sumOf { it.optDouble("profit", 0.0) } / losses.size
        val rate = if (closed.isEmpty()) 0.0 else wins.size * 100.0 / closed.size
        val markets = closed.groupBy { it.optString("symbol", "Unknown") }
            .mapValues { entry -> entry.value.sumOf { it.optDouble("profit", 0.0) } }
            .entries.sortedByDescending { it.value }.take(5)
        val marketText = if (markets.isEmpty()) "  No settled market data" else markets.joinToString("\n") {
            String.format(java.util.Locale.US, "  %s  %+.2f", it.key, it.value)
        }
        statsView.text = listOf(
            "SETTLED: ${closed.size}   WIN RATE: ${String.format(java.util.Locale.US, "%.1f", rate)}%",
            String.format(java.util.Locale.US, "NET P/L: %+.2f", net),
            String.format(java.util.Locale.US, "TODAY: %+.2f   7 DAYS: %+.2f", today, week),
            String.format(java.util.Locale.US, "AVG WIN: %.2f   AVG LOSS: %.2f", avgWin, avgLoss),
            "TOP MARKETS (P/L)", marketText,
            "Based on the latest 200 locally stored records. Balance is displayed separately."
        ).joinToString("\n")
    }
    private fun appendLiveActivity(message: String) {
        if (!::liveActivityView.isInitialized) return
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        liveActivityLines.add(0, "[$time] $message")
        while (liveActivityLines.size > 35) liveActivityLines.removeAt(liveActivityLines.lastIndex)
        liveActivityView.text = liveActivityLines.joinToString("\n")
    }

    private fun showConnectionStatus(message: String) {
        if (::log.isInitialized) log.text = message
        connectionStatusView?.text = message
    }

    private fun showMarketStatus(message: String) {
        if (::log.isInitialized) log.text = message
        marketStatusView?.text = message
    }

    private fun show(message: String) {
        if (::log.isInitialized) log.text = message
    }

    override fun onStop() {
        reconnectEnabled = false
        mainHandler.removeCallbacks(reconnectRunnable)
        super.onStop()
        controller?.stop()
        // Do not sever the Deriv socket while contracts are open; their monitor streams must remain alive.
        if (controller?.hasOpenPosition() != true) api.disconnect()
    }
}
