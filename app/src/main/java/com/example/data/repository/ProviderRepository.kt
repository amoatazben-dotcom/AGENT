package com.example.data.repository

import android.content.Context
import com.example.data.ai.BaseUrlNormalizer
import com.example.data.ai.ProviderProbe
import com.example.data.ai.TestResult
import com.example.data.local.AIProviderEntity
import com.example.data.local.AgentDatabase
import com.example.data.security.SecureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

val PROVIDER_PRESETS: Map<String, String> = mapOf(
    "openai" to "https://api.openai.com/v1",
    "anthropic" to "https://api.anthropic.com",
    "gemini" to "https://generativelanguage.googleapis.com",
    "openrouter" to "https://openrouter.ai/api/v1",
    "groq" to "https://api.groq.com/openai/v1",
    "deepseek" to "https://api.deepseek.com/v1",
    "xai" to "https://api.x.ai/v1",
    "mistral" to "https://api.mistral.ai/v1",
    "ollama" to "http://localhost:11434/v1",
    "lmstudio" to "http://localhost:1234/v1"
)

@Singleton
class ProviderRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val database: AgentDatabase = AgentDatabase.getDatabase(context),
    private val secureStore: SecureStore = SecureStore(context)
) {
    private val dao = database.providerDao()
    private val modelDao = database.providerModelDao()

    val providers: Flow<List<AIProviderEntity>> = dao.getAllProviders()

    fun modelsFor(providerId: String) = modelDao.getModelsForProvider(providerId)

    suspend fun getProvider(id: String): AIProviderEntity? = dao.getProviderById(id)
    suspend fun enabledProviders(): List<AIProviderEntity> = dao.getEnabledProviders()
    suspend fun defaultProvider(): AIProviderEntity? = dao.getDefaultProvider()
    fun apiKeyFor(providerId: String): String? = secureStore.getApiKey(providerId)
    fun maskedKey(providerId: String): String = secureStore.maskedApiKey(providerId)

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

    /** Create provider: metadata → Room, secret → Keystore. Throws on invalid URL. */
    suspend fun createProvider(
        name: String,
        type: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        customHeadersJson: String = "{}",
        timeoutMs: Int = 60000,
        isDefault: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val normalized = BaseUrlNormalizer.normalize(baseUrl).baseUrl
        validateHeaders(customHeadersJson)
        val id = UUID.randomUUID().toString()
        val ts = now()
        if (isDefault) dao.clearDefaults()
        dao.upsert(
            AIProviderEntity(
                id = id, name = name.trim(), type = type, baseUrl = normalized,
                defaultModel = defaultModel.trim(), enabled = true, isDefault = isDefault,
                customHeadersJson = customHeadersJson, timeoutMs = timeoutMs,
                status = "unknown", hasApiKey = apiKey.isNotBlank(),
                createdAt = ts, updatedAt = ts
            )
        )
        if (apiKey.isNotBlank()) secureStore.putApiKey(id, apiKey)
        id
    }

    suspend fun updateProvider(
        id: String,
        name: String,
        baseUrl: String,
        defaultModel: String,
        customHeadersJson: String,
        timeoutMs: Int,
        enabled: Boolean,
        newApiKey: String? = null // null = keep, "" = delete, else replace
    ) = withContext(Dispatchers.IO) {
        val existing = dao.getProviderById(id) ?: throw IllegalArgumentException("Provider not found")
        val normalized = BaseUrlNormalizer.normalize(baseUrl).baseUrl
        validateHeaders(customHeadersJson)
        when (newApiKey) {
            null -> Unit
            "" -> secureStore.deleteApiKey(id)
            else -> secureStore.putApiKey(id, newApiKey)
        }
        dao.upsert(
            existing.copy(
                name = name.trim(), baseUrl = normalized, defaultModel = defaultModel.trim(),
                customHeadersJson = customHeadersJson, timeoutMs = timeoutMs, enabled = enabled,
                hasApiKey = secureStore.hasApiKey(id), updatedAt = now()
            )
        )
    }

    suspend fun deleteProvider(id: String) = withContext(Dispatchers.IO) {
        dao.deleteProvider(id)
        secureStore.deleteApiKey(id)
    }

    suspend fun setDefault(id: String) = withContext(Dispatchers.IO) {
        dao.clearDefaults()
        val p = dao.getProviderById(id) ?: return@withContext
        dao.upsert(p.copy(isDefault = true, updatedAt = now()))
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val p = dao.getProviderById(id) ?: return@withContext
        dao.upsert(p.copy(enabled = enabled, updatedAt = now()))
    }

    /** Real connection test: device → provider. Persists status + discovered models. */
    suspend fun testConnection(id: String): TestResult = withContext(Dispatchers.IO) {
        val p = dao.getProviderById(id) ?: throw IllegalArgumentException("Provider not found")
        val key = secureStore.getApiKey(id) ?: ""
        val headers = parseHeaders(p.customHeadersJson)
        val result = ProviderProbe.testConnection(p, key, headers)
        dao.upsert(
            p.copy(
                status = result.status,
                lastTestedAt = now(),
                lastError = if (result.status == "connected" || result.status == "no_models") null else result.message,
                latencyMs = result.latencyMs,
                updatedAt = now()
            )
        )
        if (result.models.isNotEmpty()) {
            modelDao.clearForProvider(id)
            modelDao.upsertAll(ProviderProbe.toModelEntities(id, result.models, now()))
        }
        result
    }

    suspend fun refreshModels(id: String): List<String> = withContext(Dispatchers.IO) {
        val p = dao.getProviderById(id) ?: throw IllegalArgumentException("Provider not found")
        val key = secureStore.getApiKey(id) ?: ""
        val ids = ProviderProbe.refreshModels(p, key, parseHeaders(p.customHeadersJson))
        modelDao.clearForProvider(id)
        modelDao.upsertAll(ProviderProbe.toModelEntities(id, ids, now()))
        ids
    }

    suspend fun cachedModels(id: String) = modelDao.getModelsOnce(id).map { it.modelId }

    fun parseHeaders(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        val obj = JSONObject(json)
        val out = mutableMapOf<String, String>()
        for (k in obj.keys()) out[k] = obj.optString(k)
        return out
    }

    private fun validateHeaders(json: String) {
        if (json.isBlank()) return
        try {
            JSONObject(json)
        } catch (_: Exception) {
            throw IllegalArgumentException("Custom headers must be valid JSON.")
        }
    }
}
