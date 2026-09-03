package com.example.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for API keys and auth tokens, backed by Android Keystore
 * via EncryptedSharedPreferences. Metadata lives in Room; secrets here,
 * keyed by providerId / well-known keys. Never logged.
 */
@Singleton
class SecureStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "agentforge_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun putApiKey(providerId: String, apiKey: String) {
        prefs.edit().putString(keyFor(providerId), apiKey).apply()
    }

    fun getApiKey(providerId: String): String? =
        prefs.getString(keyFor(providerId), null)

    fun deleteApiKey(providerId: String) {
        prefs.edit().remove(keyFor(providerId)).apply()
    }

    fun hasApiKey(providerId: String): Boolean =
        !prefs.getString(keyFor(providerId), null).isNullOrEmpty()

    fun putAuthToken(token: String) {
        prefs.edit().putString("auth.token", token).apply()
    }

    fun getAuthToken(): String? = prefs.getString("auth.token", null)

    fun putRefreshToken(token: String) {
        prefs.edit().putString("auth.refresh", token).apply()
    }

    fun getRefreshToken(): String? = prefs.getString("auth.refresh", null)

    fun clearAuth() {
        prefs.edit().remove("auth.token").remove("auth.refresh").apply()
    }

    /** Masked display form, e.g. sk-••••••••••93xA. Never the full key. */
    fun maskedApiKey(providerId: String): String = mask(getApiKey(providerId) ?: "")

    private fun keyFor(providerId: String) = "provider.$providerId.apikey"

    companion object {
        fun mask(apiKey: String): String {
            if (apiKey.isBlank()) return ""
            val t = apiKey.trim()
            if (t.length <= 8) return "••••••••"
            val prefix = if (t.startsWith("sk-")) "sk-" else t.take(3) + "-"
            return "$prefix••••••••••${t.takeLast(4)}"
        }
    }
}
