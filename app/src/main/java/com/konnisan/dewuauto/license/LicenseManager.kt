package com.konnisan.dewuauto.license

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.konnisan.dewuauto.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class LicenseManager(private val context: Context) {
    companion object {
        private const val TAG = "DewuAuto-License"
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
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "LICENSE_API_BASE_URL is empty; Debug build uses local development verification.")
                callback(Result.success(Unit))
            } else {
                callback(Result.failure(IllegalStateException("Release 构建必须配置卡密服务地址")))
            }
            return
        }

        if (cardKey.isBlank()) {
            callback(Result.failure(IllegalArgumentException("请输入卡密")))
            return
        }

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
            }
            mainHandler.post { callback(result) }
        }
    }

    fun startHeartbeat(onFailure: (String) -> Unit) {
        stopHeartbeat()
        val baseUrl = BuildConfig.LICENSE_API_BASE_URL.trim().trimEnd('/')
        if (baseUrl.isBlank() || BuildConfig.DEBUG && sessionToken == null) return

        heartbeatRunnable = object : Runnable {
            override fun run() {
                val token = sessionToken
                if (token.isNullOrBlank()) {
                    onFailure("卡密会话不存在")
                    return
                }
                executor.execute {
                    val result = runCatching {
                        val response = postJson(
                            "$baseUrl/heartbeat",
                            JSONObject()
                                .put("sessionToken", token)
                                .put("deviceId", deviceId),
                        )
                        if (!response.optBoolean("ok", false)) {
                            error(response.optString("message", "心跳失败"))
                        }
                    }
                    mainHandler.post {
                        result.exceptionOrNull()?.let { onFailure(it.message ?: "心跳失败") }
                        if (result.isSuccess) mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
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
        executor.shutdownNow()
    }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: $text")
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
