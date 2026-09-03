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
import java.util.UUID

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

/**
 * Gateway WebSocket with token auth, exponential-backoff reconnect,
 * re-subscribe on reconnect, and duplicate-event protection.
 */
class AgentWebSocketManager(private val okHttpClient: OkHttpClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val envelopeAdapter = moshi.adapter(WsEventEnvelope::class.java)

    private var webSocket: WebSocket? = null
    private var currentUrl: String = ""
    private var authToken: String? = null
    private var subscribedConversationId: String? = null
    private var reconnectAttempt = 0
    private var manualDisconnect = false
    private val seenEventIds = LinkedHashSet<String>()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingEvents = MutableSharedFlow<WsEventEnvelope>(extraBufferCapacity = 128)
    val incomingEvents: SharedFlow<WsEventEnvelope> = _incomingEvents.asSharedFlow()

    fun connect(wsUrl: String, conversationId: String? = null, token: String? = null) {
        currentUrl = wsUrl
        if (conversationId != null) subscribedConversationId = conversationId
        if (token != null) authToken = token
        manualDisconnect = false
        _connectionState.value = ConnectionState.CONNECTING

        val url = if (!authToken.isNullOrBlank() && !wsUrl.contains("token=")) {
            if (wsUrl.contains("?")) "$wsUrl&token=$authToken" else "$wsUrl?token=$authToken"
        } else wsUrl
        val request = Request.Builder().url(url).build()
        try {
            webSocket?.cancel()
        } catch (_: Exception) {}
        webSocket = okHttpClient.newWebSocket(request, createListener())
    }

    private fun createListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("AgentWS", "WebSocket connected")
                reconnectAttempt = 0
                _connectionState.value = ConnectionState.CONNECTED
                subscribedConversationId?.let { subscribeToConversation(it) }
                webSocket.send("""{"action":"ping","eventId":"${UUID.randomUUID()}"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    if (text.contains("\"type\":\"pong\"") || text.contains("\"type\": \"pong\"")) return
                    val envelope = envelopeAdapter.fromJson(text) ?: return
                    // Duplicate protection
                    if (envelope.eventId.isNotBlank()) {
                        synchronized(seenEventIds) {
                            if (!seenEventIds.add(envelope.eventId)) return
                            if (seenEventIds.size > 500) seenEventIds.clear()
                        }
                    }
                    scope.launch { _incomingEvents.emit(envelope) }
                } catch (e: Exception) {
                    Log.e("AgentWS", "Error parsing incoming event", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("AgentWS", "WebSocket failure: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("AgentWS", "WebSocket closed: $reason")
                if (!manualDisconnect) scheduleReconnect() else {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (manualDisconnect || currentUrl.isEmpty()) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        _connectionState.value = ConnectionState.RECONNECTING
        scope.launch {
            val wait = minOf(1000L * (1 shl minOf(reconnectAttempt, 5)), 30000L)
            reconnectAttempt++
            delay(wait)
            if (_connectionState.value == ConnectionState.RECONNECTING && !manualDisconnect) {
                connect(currentUrl, subscribedConversationId, authToken)
            }
        }
    }

    fun subscribeToConversation(conversationId: String) {
        subscribedConversationId = conversationId
        webSocket?.send("""{"action":"subscribe","conversationId":"$conversationId","eventId":"${UUID.randomUUID()}"}""")
    }

    fun resolveApproval(approvalId: String, decision: String) {
        webSocket?.send("""{"action":"resolve_approval","approvalId":"$approvalId","decision":"$decision","eventId":"${UUID.randomUUID()}"}""")
    }

    fun stopRun(runId: String) {
        webSocket?.send("""{"action":"stop_run","runId":"$runId","eventId":"${UUID.randomUUID()}"}""")
    }

    fun disconnect() {
        manualDisconnect = true
        _connectionState.value = ConnectionState.DISCONNECTED
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (_: Exception) {}
        webSocket = null
    }
}
