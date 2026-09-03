package com.example.data.ai

import com.example.data.local.AIProviderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ChatMessage(val role: String, val content: String)

sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent
    data class Usage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int) : StreamEvent
    data class Failed(val error: String, val kind: String) : StreamEvent
    data object Done : StreamEvent
}

/**
 * Local-first chat executor: streams directly from the device to the user's
 * OpenAI-compatible provider. Used when Gateway is unreachable (Local Workspace).
 * Secrets come from SecureStore; nothing is logged.
 */
object LocalChatExecutor {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun streamChat(
        provider: AIProviderEntity,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        temperature: Float,
        customHeaders: Map<String, String> = emptyMap(),
        onEvent: (StreamEvent) -> Unit
    ) = withContext(Dispatchers.IO) {
        val type = provider.type.lowercase()
        try {
            when (type) {
                "gemini" -> streamGemini(provider, apiKey, model, systemPrompt, history, temperature, onEvent)
                "anthropic" -> streamAnthropic(provider, apiKey, model, systemPrompt, history, temperature, onEvent)
                else -> streamOpenAICompatible(provider, apiKey, model, systemPrompt, history, temperature, customHeaders, onEvent)
            }
        } catch (e: Exception) {
            onEvent(StreamEvent.Failed(mapError(e), "network"))
        }
    }

    private fun streamOpenAICompatible(
        provider: AIProviderEntity,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        temperature: Float,
        customHeaders: Map<String, String>,
        onEvent: (StreamEvent) -> Unit
    ) {
        val norm = try {
            BaseUrlNormalizer.normalize(provider.baseUrl)
        } catch (e: Exception) {
            onEvent(StreamEvent.Failed(e.message ?: "Invalid Base URL", "invalid_url")); return
        }
        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (m in history.takeLast(30)) messages.put(JSONObject().put("role", m.role).put("content", m.content))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", temperature.toDouble())
            .put("stream", true)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val reqBuilder = Request.Builder().url(norm.chatCompletionsUrl).post(body)
        if (apiKey.isNotBlank()) reqBuilder.header("Authorization", "Bearer $apiKey")
        for ((k, v) in customHeaders) reqBuilder.header(k, v)

        client.newCall(reqBuilder.build()).execute().use { res ->
            if (!res.isSuccessful) {
                onEvent(StreamEvent.Failed(parseHttpError(res.code, res.body?.string()), "http_${res.code}"))
                return
            }
            val reader = BufferedReader(InputStreamReader(res.body!!.byteStream()))
            var line: String?
            val accArgs = StringBuilder()
            var accName = ""
            var accId = ""
            while (reader.readLine().also { line = it } != null) {
                val t = line!!.trim()
                if (t.isEmpty() || !t.startsWith("data:")) continue
                val data = t.removePrefix("data:").trim()
                if (data == "[DONE]") break
                try {
                    val json = JSONObject(data)
                    json.optJSONObject("usage")?.let {
                        onEvent(StreamEvent.Usage(it.optInt("prompt_tokens"), it.optInt("completion_tokens"), it.optInt("total_tokens")))
                    }
                    val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    delta.optString("content", "").takeIf { it.isNotEmpty() }?.let { onEvent(StreamEvent.Delta(it)) }
                    val tc = delta.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")
                    if (tc != null) {
                        accName += tc.optString("name", "")
                        accArgs.append(tc.optString("arguments", ""))
                        accId = delta.optJSONArray("tool_calls")?.optJSONObject(0)?.optString("id", accId) ?: accId
                    }
                } catch (_: Exception) { /* fragmented chunk */ }
            }
            // Tool calls from a local executor are surfaced as text (no local tool runner on device yet).
            if (accName.isNotBlank()) onEvent(StreamEvent.Delta("\n\n[Tool request: $accName ${accArgs.take(500)}]"))
            onEvent(StreamEvent.Done)
        }
    }

    private fun streamGemini(
        provider: AIProviderEntity,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        temperature: Float,
        onEvent: (StreamEvent) -> Unit
    ) {
        if (apiKey.isBlank()) { onEvent(StreamEvent.Failed("Missing API key.", "auth")); return }
        val m = if (model.contains("gemini", true)) model else "gemini-1.5-flash"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$m:streamGenerateContent?key=$apiKey&alt=sse"
        val contents = JSONArray()
        for (h in history.takeLast(30)) {
            if (h.role == "system") continue
            contents.put(JSONObject().put("role", if (h.role == "assistant") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", h.content))))
        }
        val bodyJson = JSONObject().put("contents", contents)
        if (systemPrompt.isNotBlank()) bodyJson.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(url).post(body).build()).execute().use { res ->
            if (!res.isSuccessful) { onEvent(StreamEvent.Failed(parseHttpError(res.code, res.body?.string()), "http_${res.code}")); return }
            val reader = BufferedReader(InputStreamReader(res.body!!.byteStream()))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val t = line!!.trim()
                if (!t.startsWith("data:")) continue
                try {
                    val parts = JSONObject(t.removePrefix("data:").trim())
                        .optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        parts.optJSONObject(i)?.optString("text", "")?.takeIf { it.isNotEmpty() }?.let { onEvent(StreamEvent.Delta(it)) }
                    }
                } catch (_: Exception) {}
            }
            onEvent(StreamEvent.Done)
        }
    }

    private fun streamAnthropic(
        provider: AIProviderEntity,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        temperature: Float,
        onEvent: (StreamEvent) -> Unit
    ) {
        if (apiKey.isBlank()) { onEvent(StreamEvent.Failed("Missing API key.", "auth")); return }
        val m = if (model.contains("claude", true)) model else "claude-3-5-sonnet-20241022"
        val msgs = JSONArray()
        for (h in history.takeLast(30)) {
            if (h.role == "system") continue
            msgs.put(JSONObject().put("role", if (h.role == "assistant") "assistant" else "user").put("content", h.content))
        }
        val bodyJson = JSONObject().put("model", m).put("max_tokens", 4096).put("messages", msgs).put("stream", true)
        if (systemPrompt.isNotBlank()) bodyJson.put("system", systemPrompt)
        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://api.anthropic.com/v1/messages").post(body)
            .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01").build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) { onEvent(StreamEvent.Failed(parseHttpError(res.code, res.body?.string()), "http_${res.code}")); return }
            val reader = BufferedReader(InputStreamReader(res.body!!.byteStream()))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val t = line!!.trim()
                if (!t.startsWith("data:")) continue
                try {
                    val json = JSONObject(t.removePrefix("data:").trim())
                    if (json.optString("type") == "content_block_delta") {
                        json.optJSONObject("delta")?.optString("text", "")?.takeIf { it.isNotEmpty() }?.let { onEvent(StreamEvent.Delta(it)) }
                    }
                } catch (_: Exception) {}
            }
            onEvent(StreamEvent.Done)
        }
    }

    private fun parseHttpError(code: Int, body: String?): String = when (code) {
        401, 403 -> "Authentication failed. Check your API key."
        404 -> "Not found. Check Base URL / Model ID."
        408 -> "Request timed out."
        429 -> "Rate limited (429). Try again shortly."
        in 500..599 -> "Provider unavailable (HTTP $code)."
        else -> (body?.take(300) ?: "HTTP $code")
    }

    private fun mapError(e: Exception): String =
        if (e is java.net.SocketTimeoutException) "Request timed out."
        else "Network error: ${e.message}"
}
