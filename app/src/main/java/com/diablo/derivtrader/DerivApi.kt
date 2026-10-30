package com.diablo.derivtrader

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class DerivApi {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var reqId = 1
    private val pending = mutableMapOf<Int, (Result<JSONObject>) -> Unit>()
    @Volatile var connected = false
        private set
    var onUpdate: ((JSONObject) -> Unit)? = null

    @Synchronized
    fun connect(token: String, appId: String, accountId: String, mode: String, done: (Result<String>) -> Unit) {
        if (mode != "demo") {
            done(Result.failure(Exception("Real mode locked until demo validation")))
            return
        }
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
                        val parsed = url.toHttpUrlOrNull() ?: throw Exception("Invalid Deriv WebSocket URL")
                        if (parsed.scheme != "wss" || parsed.host != "api.derivws.com" ||
                            !parsed.encodedPath.endsWith("/ws/demo")) {
                            throw Exception("Unexpected Deriv demo endpoint")
                        }
                        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                connected = true
                                done(Result.success("Connected to Deriv Demo"))
                            }
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                try {
                                    val message = JSONObject(text)
                                    val id = message.optInt("req_id", -1)
                                    synchronized(pending) {
                                        pending.remove(id)?.invoke(
                                            if (message.has("error")) Result.failure(
                                                Exception(message.optJSONObject("error")?.optString("message") ?: "Deriv error")
                                            ) else Result.success(message)
                                        )
                                    }
                                    onUpdate?.invoke(message)
                                } catch (_: Exception) { }
                            }
                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                connected = false
                                if (code != 1000) onUpdate?.invoke(JSONObject().put("connection_error", "WebSocket closed: $code $reason"))
                            }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                connected = false
                                onUpdate?.invoke(JSONObject().put("connection_error", t.message ?: "WebSocket failure"))
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

    fun markets(done: (Result<JSONObject>) -> Unit) =
        request(JSONObject().put("active_symbols", "brief"), done)

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
        ws?.close(1000, "App stopped")
        ws = null
        synchronized(pending) { pending.clear() }
    }
}
