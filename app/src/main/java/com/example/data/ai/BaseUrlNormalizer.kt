package com.example.data.ai

/** Central Base-URL normalizer. Never produces /v1/v1 or //chat/completions. */
object BaseUrlNormalizer {

    data class Normalized(
        val baseUrl: String,
        val chatCompletionsUrl: String,
        val modelsUrl: String,
        val isLocal: Boolean,
        val usesHttp: Boolean
    )

    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" || h == "127.0.0.1" || h == "[::1]" ||
            h.startsWith("10.") || h.startsWith("192.168.") ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(h)
    }

    fun normalize(raw: String): Normalized {
        val trimmed0 = raw.trim()
        require(trimmed0.startsWith("http://", true) || trimmed0.startsWith("https://", true)) {
            "Invalid Base URL: must start with http:// or https://"
        }
        var noTrail = trimmed0.trimEnd('/')
        val url = try {
            java.net.URL(noTrail)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid Base URL: unparseable.")
        }
        require(url.host.isNotBlank()) { "Invalid Base URL: missing host." }
        var path = url.path.replace(Regex("/{2,}"), "/").trimEnd('/')
        if (path == "/") path = ""
        if (!path.endsWith("/v1", ignoreCase = true)) path += "/v1"
        val base = "${url.protocol}://${url.host}${if (url.port != -1) ":${url.port}" else ""}$path"
        val baseUrl = base.trimEnd('/')
        return Normalized(
            baseUrl = baseUrl,
            chatCompletionsUrl = "$baseUrl/chat/completions",
            modelsUrl = "$baseUrl/models",
            isLocal = isLocalHost(url.host),
            usesHttp = url.protocol == "http"
        )
    }

    /** Emulator fix: device localhost != host localhost. Explicit opt-in per profile. */
    fun emulatorHostUrl(raw: String): String {
        return raw.replace("://localhost", "://10.0.2.2").replace("://127.0.0.1", "://10.0.2.2")
    }

    fun defaultBaseUrlFor(type: String): String = when (type.lowercase()) {
        "openai" -> "https://api.openai.com/v1"
        "anthropic" -> "https://api.anthropic.com"
        "gemini" -> "https://generativelanguage.googleapis.com"
        "openrouter" -> "https://openrouter.ai/api/v1"
        "groq" -> "https://api.groq.com/openai/v1"
        "deepseek" -> "https://api.deepseek.com/v1"
        "xai" -> "https://api.x.ai/v1"
        "mistral" -> "https://api.mistral.ai/v1"
        "ollama" -> "http://localhost:11434/v1"
        "lmstudio" -> "http://localhost:1234/v1"
        else -> ""
    }
}
