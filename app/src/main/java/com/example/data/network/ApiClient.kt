package com.example.data.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private var baseUrl: String = "http://10.0.2.2:3000"

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
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
