package com.diablo.derivtrader

import okhttp3.*
import java.net.URI
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class DerivApi {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val publicHttp = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var ws: WebSocket? = null
    private var reqId = 1
    private val pending = mutableMapOf<Int, (Result<JSONObject>) -> Unit>()
    @Volatile var connected = false
        private set
    @Volatile var connectedMode: String? = null
        private set
    var onUpdate: ((JSONObject) -> Unit)? = null

    @Synchronized
    fun connect(token: String, appId: String, accountId: String, mode: String, done: (Result<String>) -> Unit) {
        if (mode != "demo" && mode != "real") { done(Result.failure(IllegalArgumentException("Invalid account mode"))); return }
        disconnect()
        val req = Request.Builder()
            .url("https://api.derivws.com/trading/v1/options/accounts/$accountId/otp")
            .post(ByteArray(0).toRequestBody())
            .header("Authorization", "Bearer $token")
            .header("Deriv-App-ID", appId)
            .build()

        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = done(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        done(Result.failure(Exception("Deriv OTP HTTP ${it.code}: $raw")))
                        return
                    }
                    try {
                        val url = JSONObject(raw).optJSONObject("data")?.optString("url").orEmpty()
                        // java.net.URI is required here: OkHttp HttpUrl accepts only http/https,
                        // so toHttpUrlOrNull() incorrectly rejects valid wss:// URLs.
                        val parsed = try { URI(url) } catch (_: Exception) {
                            throw Exception("Deriv returned a malformed WebSocket URL")
                        }
                        val expectedPath = if (mode == "real")
                            "/trading/v1/options/ws/real"
                        else
                            "/trading/v1/options/ws/demo"
                        val hasOtp = parsed.rawQuery.orEmpty().split("&").any {
                            it.startsWith("otp=") && it.substringAfter("otp=").isNotBlank()
                        }
                        if (!parsed.scheme.equals("wss", ignoreCase = true) ||
                            !parsed.host.equals("api.derivws.com", ignoreCase = true) ||
                            parsed.path != expectedPath || !hasOtp) {
                            throw Exception("Deriv returned an unexpected $mode WebSocket URL")
                        }
                        val callbackDelivered = java.util.concurrent.atomic.AtomicBoolean(false)
                        fun finishConnection(result: Result<String>) {
                            if (callbackDelivered.compareAndSet(false, true)) done(result)
                        }
                        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                if (ws !== webSocket) { webSocket.close(1000, "Superseded connection"); return }
                                connected = true
                                connectedMode = mode
                                finishConnection(Result.success("Connected to Deriv ${mode.uppercase()}"))
                            }
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (ws !== webSocket) return
                                try {
                                    val message = JSONObject(text)
                                    val id = message.optInt("req_id", -1)
                                    synchronized(pending) {
                                        pending.remove(id)?.invoke(
                                            when {
                                                message.has("error") -> Result.failure(
                                                    Exception(message.optJSONObject("error")?.optString("message") ?: "Deriv error")
                                                )
                                                message.optJSONArray("errors")?.length()?.let { it > 0 } == true -> {
                                                    val errors = message.optJSONArray("errors")!!
                                                    val details = (0 until errors.length()).mapNotNull { index ->
                                                        errors.optJSONObject(index)?.optString("message")?.takeIf { it.isNotBlank() }
                                                    }.joinToString("; ")
                                                    Result.failure(Exception(details.ifBlank { "Deriv rejected the request" }))
                                                }
                                                else -> Result.success(message)
                                            }
                                        )
                                    }
                                    onUpdate?.invoke(message)
                                } catch (_: Exception) { }
                            }
                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                if (ws !== webSocket) return
                                connected = false
                                connectedMode = null
                                val message = "Deriv WebSocket closed: $code ${reason.ifBlank { "(no reason supplied)" }}"
                                finishConnection(Result.failure(Exception(message)))
                                if (code != 1000) onUpdate?.invoke(JSONObject().put("connection_error", message))
                            }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                if (ws !== webSocket) return
                                connected = false
                                connectedMode = null
                                val httpDetail = response?.let { " (HTTP ${it.code})" }.orEmpty()
                                val message = "Deriv WebSocket failed$httpDetail: ${t.message ?: "network or handshake error"}"
                                finishConnection(Result.failure(Exception(message)))
                                onUpdate?.invoke(JSONObject().put("connection_error", message))
                            }
                        })
                    } catch (e: Exception) {
                        done(Result.failure(e))
                    }
                }
            }
        })
    }

    fun request(data: JSONObject, done: (Result<JSONObject>) -> Unit) {
        val socket = ws
        if (!connected || socket == null) {
            done(Result.failure(Exception("Connect Deriv first")))
            return
        }
        val id: Int
        synchronized(pending) {
            id = reqId++
            data.put("req_id", id)
            pending[id] = done
        }
        if (!socket.send(data.toString())) {
            synchronized(pending) { pending.remove(id) }
            done(Result.failure(Exception("WebSocket send failed")))
        }
    }


    fun getAccounts(token: String, appId: String, done: (Result<JSONObject>) -> Unit) {
        val request = Request.Builder()
            .url("https://api.derivws.com/trading/v1/options/accounts")
            .get()
            .header("Authorization", "Bearer $token")
            .header("Deriv-App-ID", appId)
            .build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = done(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        done(Result.failure(Exception("Deriv accounts HTTP ${it.code}: $raw")))
                    } else {
                        try { done(Result.success(JSONObject(raw))) }
                        catch (e: Exception) { done(Result.failure(e)) }
                    }
                }
            }
        })
    }

    fun portfolio(done: (Result<JSONObject>) -> Unit) =
        request(JSONObject().put("portfolio", 1), done)

    fun balance(done: (Result<JSONObject>) -> Unit) =
        request(JSONObject().put("balance", 1).put("subscribe", 1), done)

    /**
     * Active symbols are public market data. Deriv documents this request on its
     * unauthenticated public Options WebSocket, not as an account-scoped lookup.
     * Fetch the catalogue there, then use the authenticated account socket for
     * contracts, proposals, buys and open-contract monitoring.
     */
    fun markets(done: (Result<JSONObject>) -> Unit) {
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(result: Result<JSONObject>) {
            if (finished.compareAndSet(false, true)) done(result)
        }
        val publicSocket = publicHttp.newWebSocket(
            Request.Builder()
                .url("wss://api.derivws.com/trading/v1/options/ws/public")
                .build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!webSocket.send(JSONObject()
                            .put("active_symbols", "brief")
                            .put("req_id", 71001)
                            .toString())) {
                        finish(Result.failure(Exception("Could not request Deriv public market list.")))
                        webSocket.close(1000, "Request failed")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val message = JSONObject(text)
                        if (message.optInt("req_id", -1) != 71001 &&
                            !message.has("active_symbols") && !message.has("error") && !message.has("errors")) return
                        when {
                            message.has("error") -> finish(Result.failure(Exception(
                                message.optJSONObject("error")?.optString("message") ?: "Deriv public market request failed"
                            )))
                            message.optJSONArray("errors")?.length()?.let { it > 0 } == true -> {
                                val errors = message.optJSONArray("errors")!!
                                val details = (0 until errors.length()).mapNotNull { i ->
                                    errors.optJSONObject(i)?.optString("message")?.takeIf { it.isNotBlank() }
                                }.joinToString("; ")
                                finish(Result.failure(Exception(details.ifBlank { "Deriv rejected the public market request" })))
                            }
                            message.optJSONArray("active_symbols") != null ->
                                finish(Result.success(message))
                            else -> finish(Result.failure(Exception("Deriv response did not contain an active_symbols list: $text")))
                        }
                    } catch (e: Exception) {
                        finish(Result.failure(e))
                    } finally {
                        webSocket.close(1000, "Market list received")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.let { " (HTTP ${it.code})" }.orEmpty()
                    finish(Result.failure(Exception("Public market feed failed$code: ${t.message ?: "network error"}")))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!finished.get()) finish(Result.failure(Exception(
                        "Public market feed closed before returning symbols: $code ${reason}"
                    )))
                }
            }
        )
    }

    fun contractsFor(symbol: String, done: (Result<JSONObject>) -> Unit) =
        request(JSONObject().put("contracts_for", symbol), done)

    /** Starts a live tick subscription. Subsequent ticks arrive through onUpdate. */
    fun subscribeTicks(symbol: String, done: (Result<JSONObject>) -> Unit) =
        request(JSONObject().put("ticks", symbol).put("subscribe", 1), done)

    fun unsubscribe(subscriptionId: String, done: (Result<JSONObject>) -> Unit) =
        request(JSONObject().put("forget", subscriptionId), done)

    fun multiplierProposal(
        symbol: String,
        direction: String,
        stake: Double,
        multiplier: Int,
        currency: String,
        takeProfit: Double,
        stopLoss: Double,
        done: (Result<JSONObject>) -> Unit
    ) {
        require(direction == "UP" || direction == "DOWN")
        request(
            JSONObject()
                .put("proposal", 1)
                .put("amount", stake)
                .put("basis", "stake")
                .put("contract_type", if (direction == "UP") "MULTUP" else "MULTDOWN")
                .put("currency", currency)
                .put("multiplier", multiplier)
                .put("limit_order", JSONObject()
                    .put("take_profit", takeProfit)
                    .put("stop_loss", stopLoss))
                .put("underlying_symbol", symbol),
            done
        )
    }

    fun buy(proposalId: String, price: Double, done: (Result<JSONObject>) -> Unit) =
        request(JSONObject().put("buy", proposalId).put("price", price), done)

    fun monitor(contractId: Long, done: (Result<JSONObject>) -> Unit) =
        request(JSONObject().put("proposal_open_contract", 1).put("contract_id", contractId).put("subscribe", 1), done)

    fun sell(contractId: Long, done: (Result<JSONObject>) -> Unit) =
        request(JSONObject().put("sell", contractId).put("price", 0), done)

    @Synchronized
    fun disconnect() {
        connected = false
        connectedMode = null
        ws?.close(1000, "App stopped")
        ws = null
        synchronized(pending) { pending.clear() }
    }
}
