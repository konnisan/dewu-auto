package com.konnisan.dewuauto.license

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.konnisan.dewuauto.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class LicenseManager(private val context: Context) {
    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var sessionToken: String? = null

    private val deviceId: String
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    fun verify(cardKey: String, callback: (Result<Unit>) -> Unit) {
        val baseUrl = BuildConfig.LICENSE_API_BASE_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            callback(Result.failure(IllegalStateException("未配置卡密服务地址")))
            return
        }

        if (cardKey.isBlank()) {
            callback(Result.failure(IllegalArgumentException("请输入卡密")))
            return
        }

        sessionToken = null
        executor.execute {
            val result = runCatching {
                val response = postJson(
                    "$baseUrl/verify",
                    JSONObject()
                        .put("cardKey", cardKey)
                        .put("deviceId", deviceId),
                )
                if (!response.optBoolean("ok", false)) {
                    error(response.optString("message", "卡密验证失败"))
                }
                sessionToken = response.optString("sessionToken").takeIf { it.isNotBlank() }
                    ?: error("服务端未返回卡密会话")
            }
            mainHandler.post { callback(result) }
        }
    }

    fun startHeartbeat(onFailure: (String) -> Unit) {
        stopHeartbeat()
        val baseUrl = BuildConfig.LICENSE_API_BASE_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            onFailure("未配置卡密服务地址")
            return
        }

        val token = sessionToken
        if (token.isNullOrBlank()) {
            onFailure("卡密会话不存在")
            return
        }

        heartbeatRunnable = object : Runnable {
            override fun run() {
                val currentToken = sessionToken
                if (currentToken.isNullOrBlank()) {
                    onFailure("卡密会话不存在")
                    return
                }
                executor.execute {
                    val result = runCatching {
                        val response = postJson(
                            "$baseUrl/heartbeat",
                            JSONObject()
                                .put("sessionToken", currentToken)
                                .put("deviceId", deviceId),
                        )
                        if (!response.optBoolean("ok", false)) {
                            error(response.optString("message", "心跳失败"))
                        }
                    }
                    mainHandler.post {
                        val failure = result.exceptionOrNull()
                        if (failure != null) {
                            sessionToken = null
                            onFailure(failure.message ?: "心跳失败")
                        } else {
                            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
                        }
                    }
                }
            }
        }.also { mainHandler.postDelayed(it, HEARTBEAT_INTERVAL_MS) }
    }

    fun stopHeartbeat() {
        heartbeatRunnable?.let(mainHandler::removeCallbacks)
        heartbeatRunnable = null
    }

    fun shutdown() {
        stopHeartbeat()
        sessionToken = null
        executor.shutdownNow()
    }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: $text")
            if (text.isBlank()) error("卡密服务返回空响应")
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
