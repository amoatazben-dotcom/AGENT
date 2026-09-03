package com.example.data.ai

import com.example.data.local.AIProviderEntity
import com.example.data.local.ProviderModelEntity
import com.example.data.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TestResult(
    val status: String, // connected, auth_failed, invalid_url, timeout, unreachable, no_models, rate_limited, invalid_response
    val latencyMs: Long,
    val httpStatus: Int?,
    val message: String,
    val models: List<String>
)

/** Direct OpenAI-compatible test + models discovery from the device (local-first). */
object ProviderProbe {

    private fun client(timeoutMs: Int): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(minOf(timeoutMs, 20000).toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(minOf(timeoutMs, 25000).toLong(), TimeUnit.MILLISECONDS)
        .build()

    suspend fun testConnection(
        provider: AIProviderEntity,
        apiKey: String,
        customHeaders: Map<String, String> = emptyMap()
    ): TestResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val norm = try {
            BaseUrlNormalizer.normalize(provider.baseUrl)
        } catch (e: Exception) {
            return@withContext TestResult("invalid_url", 0, null, e.message ?: "Invalid URL", emptyList())
        }
        val type = provider.type.lowercase()
        if (type == "anthropic" || type == "gemini") {
            // No /models endpoint: validate presence of key honestly.
            return@withContext if (apiKey.isBlank()) {
                TestResult("auth_failed", 0, null, "Missing API key.", emptyList())
            } else {
                TestResult("connected", 0, null, "Key present. Full verification happens on first chat.", emptyList())
            }
        }
        try {
            val builder = Request.Builder().url(norm.modelsUrl).get()
            if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
            for ((k, v) in customHeaders) builder.header(k, v)
            val res = client(provider.timeoutMs).newCall(builder.build()).execute()
            val latency = System.currentTimeMillis() - started
            res.use {
                val body = it.body?.string() ?: ""
                when (it.code) {
                    200 -> {
                        return@withContext try {
                            val ids = parseModelIds(body)
                            if (ids.isEmpty()) {
                                TestResult("no_models", latency, 200, "Connected, but no models listed. Enter a Model ID manually.", emptyList())
                            } else {
                                TestResult("connected", latency, 200, "Connected • $latency ms • ${ids.size} models", ids)
                            }
                        } catch (_: Exception) {
                            TestResult("invalid_response", latency, 200, "Invalid JSON from /models.", emptyList())
                        }
                    }
                    401, 403 -> TestResult("auth_failed", latency, it.code, "Authentication failed. Check API key.", emptyList())
                    404 -> TestResult("no_models", latency, 404, "No /models endpoint. Enter Model ID manually.", emptyList())
                    429 -> TestResult("rate_limited", latency, 429, "Rate limited (429). Try again shortly.", emptyList())
                    else -> TestResult("invalid_response", latency, it.code, "Unexpected status ${it.code}.", emptyList())
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            return@withContext TestResult("timeout", System.currentTimeMillis() - started, null, "Connection timed out.", emptyList())
        } catch (e: Exception) {
            return@withContext TestResult("unreachable", System.currentTimeMillis() - started, null, "Server unreachable: ${e.message}", emptyList())
        }
    }

    suspend fun refreshModels(
        provider: AIProviderEntity,
        apiKey: String,
        customHeaders: Map<String, String> = emptyMap()
    ): List<String> = withContext(Dispatchers.IO) {
        val norm = BaseUrlNormalizer.normalize(provider.baseUrl)
        val builder = Request.Builder().url(norm.modelsUrl).get()
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        for ((k, v) in customHeaders) builder.header(k, v)
        client(provider.timeoutMs).newCall(builder.build()).execute().use {
            if (!it.isSuccessful) throw IllegalStateException("HTTP ${it.code}")
            return@withContext parseModelIds(it.body?.string() ?: "")
        }
    }

    fun parseModelIds(body: String): List<String> {
        val json = JSONObject(body)
        val arr: JSONArray = when {
            json.has("data") -> json.getJSONArray("data")
            json.has("models") -> json.getJSONArray("models")
            else -> JSONArray()
        }
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val el = arr.get(i)
            val id = if (el is String) el else (el as? JSONObject)?.optString("id") ?: ""
            if (id.isNotBlank()) out.add(id)
        }
        return out
    }

    fun toModelEntities(providerId: String, ids: List<String>, now: String): List<ProviderModelEntity> =
        ids.take(500).map { ProviderModelEntity("$providerId:$it", providerId, it, it, now) }
}
