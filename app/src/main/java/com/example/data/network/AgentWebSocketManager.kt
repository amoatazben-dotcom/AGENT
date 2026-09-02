package com.example.data.network

import android.util.Log
import com.example.data.model.WsEventEnvelope
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

class AgentWebSocketManager(private val okHttpClient: OkHttpClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val envelopeAdapter = moshi.adapter(WsEventEnvelope::class.java)

    private var webSocket: WebSocket? = null
    private var currentUrl: String = ""
    private var subscribedConversationId: String? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingEvents = MutableSharedFlow<WsEventEnvelope>(extraBufferCapacity = 64)
    val incomingEvents: SharedFlow<WsEventEnvelope> = _incomingEvents.asSharedFlow()

    fun connect(wsUrl: String, conversationId: String? = null) {
        currentUrl = wsUrl
        subscribedConversationId = conversationId
        _connectionState.value = ConnectionState.CONNECTING

        val request = Request.Builder().url(wsUrl).build()
        webSocket?.cancel()
        webSocket = okHttpClient.newWebSocket(request, createListener())
    }

    private fun createListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("AgentWS", "WebSocket connected to $currentUrl")
                _connectionState.value = ConnectionState.CONNECTED

                // Automatically subscribe to active conversation
                subscribedConversationId?.let { convId ->
                    subscribeToConversation(convId)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val envelope = envelopeAdapter.fromJson(text)
                    if (envelope != null) {
                        scope.launch { _incomingEvents.emit(envelope) }
                    }
                } catch (e: Exception) {
                    Log.e("AgentWS", "Error parsing incoming event: $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("AgentWS", "WebSocket failure: ${t.message}. Attempting reconnect...")
                _connectionState.value = ConnectionState.RECONNECTING
                scope.launch {
                    delay(3000)
                    if (_connectionState.value == ConnectionState.RECONNECTING && currentUrl.isNotEmpty()) {
                        connect(currentUrl, subscribedConversationId)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("AgentWS", "WebSocket closed: $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    fun subscribeToConversation(conversationId: String) {
        subscribedConversationId = conversationId
        val msg = """{"action":"subscribe","conversationId":"$conversationId"}"""
        webSocket?.send(msg)
    }

    fun resolveApproval(approvalId: String, decision: String) {
        val msg = """{"action":"resolve_approval","approvalId":"$approvalId","decision":"$decision"}"""
        webSocket?.send(msg)
    }

    fun stopRun(runId: String) {
        val msg = """{"action":"stop_run","runId":"$runId"}"""
        webSocket?.send(msg)
    }

    fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }
}
