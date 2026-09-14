package com.konnisan.dewuauto.license

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.konnisan.dewuauto.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class PurchaseManager(private val context: Context) {
    companion object {
        private const val POLL_INTERVAL_MS = 3_000L
    }

    data class PurchaseOrder(
        val orderNo: String,
        val clientToken: String,
        val payUrl: String,
        val status: String,
        val planDays: Int,
        val amountFen: Int,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    private val deviceId: String
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    fun createOrder(planDays: Int = 30, callback: (Result<PurchaseOrder>) -> Unit) {
        val baseUrl = baseUrl()
        if (baseUrl.isBlank()) {
            callback(Result.failure(IllegalStateException("未配置卡密服务地址")))
            return
        }

        executor.execute {
            val result = runCatching {
                val response = requestJson(
                    method = "POST",
                    url = "$baseUrl/orders",
                    body = JSONObject()
                        .put("deviceId", deviceId)
                        .put("planDays", planDays),
                )
                if (!response.optBoolean("ok", false)) {
                    error(response.optString("message", "创建订单失败"))
                }
                val orderNo = response.optString("orderNo").takeIf { it.isNotBlank() }
                    ?: error("服务端未返回订单号")
                val clientToken = response.optString("clientToken").takeIf { it.isNotBlank() }
                    ?: error("服务端未返回订单凭证")
                val payPath = response.optString("payPath").takeIf { it.isNotBlank() }
                    ?: error("服务端未返回支付页面")
                PurchaseOrder(
                    orderNo = orderNo,
                    clientToken = clientToken,
                    payUrl = "$baseUrl/${payPath.trimStart('/')}",
                    status = response.optString("status", "CREATED"),
                    planDays = response.optInt("planDays", planDays),
                    amountFen = response.optInt("amountFen", 0),
                )
            }
            mainHandler.post { callback(result) }
        }
    }

    fun startPolling(
        orderNo: String,
        clientToken: String,
        onPaid: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        stopPolling()
        val baseUrl = baseUrl()
        if (baseUrl.isBlank()) {
            onFailure("未配置卡密服务地址")
            return
        }

        pollRunnable = object : Runnable {
            override fun run() {
                executor.execute {
                    val result = runCatching {
                        val encodedToken = URLEncoder.encode(clientToken, StandardCharsets.UTF_8.toString())
                        requestJson(
                            method = "GET",
                            url = "$baseUrl/orders/${urlPart(orderNo)}?token=$encodedToken",
                            body = null,
                        )
                    }
                    mainHandler.post {
                        result.fold(
                            onSuccess = { response ->
                                when (response.optString("status")) {
                                    "PAID" -> {
                                        val cardKey = response.optString("cardKey")
                                        if (cardKey.isBlank()) {
                                            stopPolling()
                                            onFailure("订单已支付但服务端未返回卡密")
                                        } else {
                                            stopPolling()
                                            onPaid(cardKey)
                                        }
                                    }
                                    "CREATED" -> mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                                    "EXPIRED" -> {
                                        stopPolling()
                                        onFailure("订单已过期，请重新创建")
                                    }
                                    "CANCELLED" -> {
                                        stopPolling()
                                        onFailure("订单已取消")
                                    }
                                    else -> mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                                }
                            },
                            onFailure = {
                                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                            },
                        )
                    }
                }
            }
        }.also { mainHandler.post(it) }
    }

    fun stopPolling() {
        pollRunnable?.let(mainHandler::removeCallbacks)
        pollRunnable = null
    }

    fun shutdown() {
        stopPolling()
        executor.shutdownNow()
    }

    private fun baseUrl(): String = BuildConfig.LICENSE_API_BASE_URL.trim().trimEnd('/')

    private fun urlPart(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private fun requestJson(method: String, url: String, body: JSONObject?): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: $text")
            if (text.isBlank()) error("订单服务返回空响应")
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
