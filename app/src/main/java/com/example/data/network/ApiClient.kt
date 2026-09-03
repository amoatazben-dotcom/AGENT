package com.example.data.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/** Redacting logger: never prints Authorization / api keys / tokens. */
private fun redactedLogger(): HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger { message ->
    var m = message
    m = m.replace(Regex("(?i)(Bearer\\s+)[A-Za-z0-9_\\-.]{8,}"), "$1[REDACTED]")
    m = m.replace(Regex("sk-[A-Za-z0-9_\\-]{4,}"), "sk-[REDACTED]")
    m = m.replace(Regex("sk-proj-[A-Za-z0-9_\\-]{4,}"), "sk-proj-[REDACTED]")
    m = m.replace(Regex("sk-ant-[A-Za-z0-9_\\-]{4,}"), "sk-ant-[REDACTED]")
    m = m.replace(Regex("AIza[0-9A-Za-z\\-_]{10,}"), "AIza[REDACTED]")
    android.util.Log.d("ApiClient", m)
}

object ApiClient {
    private var baseUrl: String = "http://10.0.2.2:3000"

    @Volatile
    var authToken: String? = null

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val authInterceptor = Interceptor { chain ->
        val token = authToken
        val req = if (!token.isNullOrBlank()) {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            chain.request()
        }
        chain.proceed(req)
    }

    val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor(redactedLogger()).apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()
    }

    private var retrofitInstance: Retrofit? = null

    val api: AgentForgeApi
        get() {
            if (retrofitInstance == null) {
                retrofitInstance = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(okHttpClient)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
            }
            return retrofitInstance!!.create(AgentForgeApi::class.java)
        }

    val webSocketManager: AgentWebSocketManager by lazy {
        AgentWebSocketManager(okHttpClient)
    }

    fun setBaseUrl(url: String) {
        val formatted = if (!url.endsWith("/")) "$url/" else url
        baseUrl = formatted
        retrofitInstance = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    fun getBaseUrl(): String = baseUrl

    fun getWebSocketUrl(): String {
        return baseUrl.replace("http://", "ws://").replace("https://", "wss://") + "ws"
    }
}
