package com.diablo.derivtrader

import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class MainActivity : AppCompatActivity() {
    private val api = DerivApi()
    private lateinit var log: TextView
    private lateinit var pat: EditText
    private lateinit var appId: EditText
    private lateinit var account: EditText
    private lateinit var symbol: EditText
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = try {
            val key = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                this,
                "diablo_secure",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Secure credential storage unavailable. Credentials were not saved.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        render()
        pat.setText(prefs.getString("pat", ""))
        appId.setText(prefs.getString("appId", ""))
        api.onUpdate = { message ->
            runOnUiThread {
                val contract = message.optJSONObject("proposal_open_contract")
                if (contract != null) {
                    show("Contract ${contract.optLong("contract_id")} | ${contract.optString("status")} | P/L ${contract.optDouble("profit")}")
                } else if (message.has("connection_error")) {
                    show("Connection error: ${message.optString("connection_error")}")
                }
            }
        }
    }

    private fun render() {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = 1
            setPadding(24, 22, 24, 22)
            setBackgroundColor(0xFF02040A.toInt())
        }
        fun field(hintText: String, secret: Boolean = false) = EditText(this).apply {
            hint = hintText
            setTextColor(-1)
            setHintTextColor(0xFF8090AA.toInt())
            setSingleLine(true)
            if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

        box.addView(TextView(this).apply {
            text = "DIABLO DERIV TRADER"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(0xFFFF0A1F.toInt())
        })
        pat = field("Deriv PAT with trade permission", true)
        appId = field("Deriv App ID")
        account = field("Deriv Options account ID")
        symbol = field("Market symbol e.g. 1HZ100V")
        listOf("PERSONAL ACCESS TOKEN", "APP ID", "DEMO ACCOUNT ID", "MARKET SYMBOL")
            .forEachIndexed { index, label ->
                box.addView(TextView(this).apply {
                    text = label
                    setTextColor(0xFF9EB0CA.toInt())
                })
                box.addView(listOf(pat, appId, account, symbol)[index])
            }

        box.addView(button("CONNECT DERIV DEMO") { connect() })
        box.addView(button("LOAD MARKETS") {
            api.markets { result ->
                runOnUiThread {
                    result.onSuccess { show("Markets response received: ${it.optJSONArray("active_symbols")?.length() ?: 0} symbols") }
                        .onFailure { show(it.message ?: "Market error") }
                }
            }
        })
        box.addView(button("CHECK MULTIPLIER AVAILABILITY") {
            val selectedSymbol = symbol.text.toString().trim()
            if (selectedSymbol.isBlank()) {
                show("Enter a market symbol first.")
            } else {
                api.contractsFor(selectedSymbol) { result ->
                    runOnUiThread {
                        result.onSuccess { response ->
                            val available = response.optJSONObject("contracts_for")
                                ?.optJSONArray("available")
                            val types = mutableListOf<String>()
                            if (available != null) {
                                for (i in 0 until available.length()) {
                                    val item = available.optJSONObject(i) ?: continue
                                    val type = item.optString("contract_type")
                                    if (type == "MULTUP" || type == "MULTDOWN") types.add(type)
                                }
                            }
                            show("Multiplier contracts for $selectedSymbol: ${types.distinct().joinToString().ifBlank { "none confirmed" }}")
                        }.onFailure { show(it.message ?: "Contract check error") }
                    }
                }
            }
        })
        box.addView(button("STOP / DISCONNECT") {
            api.disconnect()
            show("Disconnected. No new trades will be started. Existing contracts remain on Deriv.")
        })
        log = TextView(this).apply {
            text = "DEMO ONLY — automated trading is not enabled yet."
            textSize = 14f
            setTextColor(0xFF00FF7A.toInt())
            setPadding(12, 20, 12, 20)
        }
        box.addView(log)
        box.addView(TextView(this).apply {
            text = "Multipliers can lose money. Real mode remains locked until Demo strategy, risk controls and trade lifecycle are tested."
            setTextColor(0xFFFF9AA5.toInt())
        })
        scroll.addView(box)
        setContentView(scroll)
    }

    private fun connect() {
        val token = pat.text.toString().trim()
        val applicationId = appId.text.toString().trim()
        val accountId = account.text.toString().trim()
        if (token.isBlank() || applicationId.isBlank() || accountId.isBlank()) {
            show("Enter PAT, App ID and account ID.")
            return
        }
        prefs.edit().putString("pat", token).putString("appId", applicationId).apply()
        show("Connecting to Deriv Demo…")
        api.connect(token, applicationId, accountId, "demo") { result ->
            runOnUiThread {
                result.onSuccess { show(it) }
                    .onFailure { show("Connection failed: ${it.message}") }
            }
        }
    }

    private fun show(message: String) {
        if (::log.isInitialized) log.text = message
    }

    override fun onStop() {
        super.onStop()
        api.disconnect()
    }
}
