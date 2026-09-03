package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.ChatMessage
import com.example.data.ai.LocalChatExecutor
import com.example.data.ai.StreamEvent
import com.example.data.local.AgentDatabase
import com.example.data.local.RunEntity
import com.example.data.model.*
import com.example.data.network.ApiClient
import com.example.data.network.ConnectionState
import com.example.data.repository.AgentRepository
import com.example.data.repository.ProviderRepository
import com.example.data.security.SecureStore
import com.example.ui.localization.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class AgentViewModel(
    application: Application,
    val database: AgentDatabase,
    val repository: AgentRepository
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        database = AgentDatabase.getDatabase(application),
        repository = AgentRepository(application, AgentDatabase.getDatabase(application))
    )

    private val providerRepository = ProviderRepository(application, database, SecureStore(application))
    private val secureStore = SecureStore(application)
    private val wsManager = ApiClient.webSocketManager

    val providers = providerRepository.providers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Language State (Defaults to Arabic as requested)
    private val _currentLanguage = MutableStateFlow(AppLanguage.ARABIC)
    val currentLanguage: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()

    fun toggleLanguage() {
        _currentLanguage.value = if (_currentLanguage.value == AppLanguage.ARABIC) AppLanguage.ENGLISH else AppLanguage.ARABIC
    }

    fun setLanguage(language: AppLanguage) {
        _currentLanguage.value = language
    }

    val agents: StateFlow<List<AgentModel>> = repository.agents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: StateFlow<List<ConversationModel>> = repository.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val approvals: StateFlow<List<ApprovalModel>> = repository.approvals
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val executionLogs: StateFlow<List<com.example.data.local.ExecutionLogEntity>> = repository.executionLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectionState: StateFlow<ConnectionState> = wsManager.connectionState

    // Workspace status (real health, refreshed on demand)
    private val _workspaceStatus = MutableStateFlow(WorkspaceStatus())
    val workspaceStatus: StateFlow<WorkspaceStatus> = _workspaceStatus.asStateFlow()

    // Active conversation state
    private val _selectedConversationId = MutableStateFlow<String?>(null)
    val selectedConversationId: StateFlow<String?> = _selectedConversationId.asStateFlow()

    private val _selectedAgentId = MutableStateFlow("agent-default-1")
    val selectedAgentId: StateFlow<String> = _selectedAgentId.asStateFlow()

    // Chat UI state
    private val _chatItems = MutableStateFlow<List<ChatItem>>(emptyList())
    val chatItems: StateFlow<List<ChatItem>> = _chatItems.asStateFlow()

    private val _isAgentWorking = MutableStateFlow(false)
    val isAgentWorking: StateFlow<Boolean> = _isAgentWorking.asStateFlow()

    private val _currentWorkStatus = MutableStateFlow<String?>(null)
    val currentWorkStatus: StateFlow<String?> = _currentWorkStatus.asStateFlow()

    // Set when chat cannot run (no provider). Screens show Configure CTA.
    private val _needsProviderSetup = MutableStateFlow(false)
    val needsProviderSetup: StateFlow<Boolean> = _needsProviderSetup.asStateFlow()

    // Computer Viewer state (Room-backed; empty until real session)
    private val _computerSession = MutableStateFlow(
        ComputerSessionModel(id = "local", status = "stopped", activeUrl = "about:blank", lastAction = "Computer worker unavailable")
    )
    val computerSession: StateFlow<ComputerSessionModel> = _computerSession.asStateFlow()

    // Automations / Files from Room (no mocks)
    val automations: StateFlow<List<AutomationModel>> = database.automationDao().getAll()
        .map { list ->
            list.map {
                AutomationModel(
                    id = it.id, name = it.name, description = it.description, type = it.type,
                    cronExpression = it.cronExpression ?: "", agentId = it.agentId, prompt = it.prompt,
                    enabled = it.enabled, lastRunAt = it.lastRunAt, nextRunAt = it.nextRunAt, lastStatus = it.lastStatus
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val files: StateFlow<List<FileItemModel>> = database.fileDao().getAll()
        .map { list ->
            list.map {
                FileItemModel(
                    id = it.id, name = it.name, path = it.path, mimeType = it.mimeType,
                    size = it.sizeBytes, conversationId = it.conversationId, runId = it.runId
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Settings state (persisted via DataStore/Room by Settings screen; gateway URL here)
    private val _gatewayUrl = MutableStateFlow(ApiClient.getBaseUrl())
    val gatewayUrl: StateFlow<String> = _gatewayUrl.asStateFlow()

    init {
        viewModelScope.launch {
            repository.seedDefaultDataIfEmpty()
        }

        viewModelScope.launch {
            try {
                wsManager.connect(ApiClient.getWebSocketUrl(), token = secureStore.getAuthToken())
            } catch (_: Exception) {}
        }

        viewModelScope.launch {
            wsManager.incomingEvents.collect { envelope ->
                handleIncomingWsEvent(envelope)
            }
        }

        // Hydrate computer sessions from Room
        viewModelScope.launch {
            database.computerSessionDao().getAll().collect { list ->
                list.firstOrNull()?.let {
                    _computerSession.value = ComputerSessionModel(
                        id = it.id, status = it.status, activeUrl = it.activeUrl,
                        cursorX = it.cursorX, cursorY = it.cursorY, lastAction = it.lastAction
                    )
                }
            }
        }

        viewModelScope.launch { refreshWorkspaceStatus() }
    }

    // ---------- Workspace health ----------

    fun refreshWorkspaceStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val dbState = "Ready"
            var providerState = "Not configured"
            try {
                val enabled = providerRepository.enabledProviders()
                val def = enabled.firstOrNull { it.isDefault } ?: enabled.firstOrNull()
                providerState = if (def != null) "${def.name} (${def.status})" else "Not configured"
            } catch (_: Exception) {}
            var gatewayState = "Offline"
            var workerState = "Offline"
            try {
                val h = ApiClient.api.getHealth()
                if (h.isSuccessful) gatewayState = "Local"
                try {
                    val ws = ApiClient.api.getWorkspaceStatus()
                    if (ws.isSuccessful) {
                        val body = ws.body()
                        workerState = (body?.get("computerWorker") as? String) ?: workerState
                    }
                } catch (_: Exception) {}
            } catch (_: Exception) {
                gatewayState = "Offline"
            }
            _workspaceStatus.value = WorkspaceStatus(dbState, providerState, gatewayState, workerState)
        }
    }

    // ---------- Providers (delegated, for screens) ----------

    fun providerRepository(): ProviderRepository = providerRepository
    fun secureStore(): SecureStore = secureStore

    suspend fun modelsForProvider(providerId: String) = providerRepository.cachedModels(providerId)

    // ---------- Chat: REAL execution ----------

    private fun handleIncomingWsEvent(envelope: WsEventEnvelope) {
        when (envelope.type) {
            "assistant.delta" -> {
                val delta = envelope.payload["delta"] as? String ?: ""
                appendStreamingAssistantDelta(delta)
            }
            "assistant.message" -> {
                val content = envelope.payload["content"] as? String ?: return
                finalizeStreamingMessage(content)
            }
            "tool.started" -> {
                val tool = envelope.payload["tool"] as? String ?: "tool"
                _isAgentWorking.value = true
                _currentWorkStatus.value = "Executing tool: $tool"
                addChatItem(ChatItem(role = "tool", text = "Tool started: $tool", toolName = tool, toolStatus = "running"))
            }
            "tool.completed" -> {
                val tool = envelope.payload["tool"] as? String ?: "tool"
                val result = envelope.payload["result"]?.toString() ?: "Completed"
                _currentWorkStatus.value = "Completed: $tool"
                addChatItem(ChatItem(role = "tool", text = "Tool finished: $tool\nResult: $result", toolName = tool, toolStatus = "success"))
            }
            "tool.failed" -> {
                val tool = envelope.payload["tool"] as? String ?: "tool"
                val err = envelope.payload["error"]?.toString() ?: "failed"
                addChatItem(ChatItem(role = "tool", text = "Tool failed: $tool\n$err", toolName = tool, toolStatus = "failed"))
            }
            "computer.frame" -> {
                val base64 = envelope.payload["base64"] as? String
                val url = envelope.payload["url"] as? String ?: _computerSession.value.activeUrl
                _computerSession.value = _computerSession.value.copy(activeUrl = url, status = "running", latestScreenshotBase64 = base64)
            }
            "approval.created" -> {
                val approval = ApprovalModel(
                    id = envelope.payload["id"] as? String ?: UUID.randomUUID().toString(),
                    runId = envelope.payload["runId"] as? String ?: "",
                    conversationId = envelope.payload["conversationId"] as? String ?: "",
                    toolName = envelope.payload["toolName"] as? String ?: "Unknown Tool",
                    reason = envelope.payload["reason"] as? String ?: "Action requires explicit authorization",
                    riskLevel = envelope.payload["riskLevel"] as? String ?: "high",
                    status = "pending"
                )
                viewModelScope.launch { repository.saveApproval(approval) }
                addChatItem(ChatItem(role = "approval", approval = approval))
            }
            "run.completed", "run.failed" -> {
                _isAgentWorking.value = false
                _currentWorkStatus.value = null
                if (envelope.type == "run.failed") {
                    val err = envelope.payload["error"]?.toString() ?: "Run failed"
                    addChatItem(ChatItem(role = "event", text = "Run failed: $err"))
                }
            }
        }
    }

    fun selectConversation(convId: String) {
        _selectedConversationId.value = convId
        try {
            wsManager.subscribeToConversation(convId)
        } catch (_: Exception) {}
        viewModelScope.launch {
            repository.getMessages(convId).collect { msgs ->
                // Preserve streaming item if active
                val streaming = _chatItems.value.lastOrNull()?.takeIf { it.isStreaming }
                val mapped = msgs.map { ChatItem(id = it.id, role = it.role, text = it.content) }
                _chatItems.value = if (streaming != null && mapped.isNotEmpty()) mapped + streaming else mapped
            }
        }
    }

    fun createNewConversation(title: String, agentId: String = _selectedAgentId.value) {
        viewModelScope.launch {
            val id = repository.createConversation(title, agentId)
            selectConversation(id)
        }
    }

    fun deleteConversation(convId: String) {
        viewModelScope.launch {
            repository.deleteConversation(convId)
            if (_selectedConversationId.value == convId) {
                _selectedConversationId.value = null
                _chatItems.value = emptyList()
            }
        }
    }

    fun selectAgent(agentId: String) {
        _selectedAgentId.value = agentId
    }

    fun sendMessage(userText: String) {
        if (userText.isBlank() || _isAgentWorking.value) return
        viewModelScope.launch {
            val existingId = _selectedConversationId.value
            val convId = if (existingId != null) {
                existingId
            } else {
                val id = repository.createConversation("Task: ${userText.take(24)}...", _selectedAgentId.value)
                _selectedConversationId.value = id
                try {
                    wsManager.subscribeToConversation(id)
                } catch (_: Exception) {}
                id
            }

            val userItem = ChatItem(role = "user", text = userText)
            addChatItem(userItem)
            viewModelScope.launch {
                repository.saveMessage(
                    MessageModel(id = userItem.id, conversationId = convId, role = "user", content = userText)
                )
            }

            _isAgentWorking.value = true
            _currentWorkStatus.value = "Sending..."
            _needsProviderSetup.value = false

            // Resolve agent + provider from Room (single source of truth)
            val agent = withContext(Dispatchers.IO) {
                database.agentDao().getAgentById(_selectedAgentId.value)
            }
            if (agent == null) {
                finishWithError(convId, "Agent not found.")
                return@launch
            }
            val providerId = agent.primaryProviderId
            val provider = withContext(Dispatchers.IO) {
                when {
                    providerId != null -> providerRepository.getProvider(providerId)
                    else -> providerRepository.defaultProvider() ?: providerRepository.enabledProviders().firstOrNull()
                }
            }
            if (provider == null) {
                _needsProviderSetup.value = true
                finishWithError(convId, "No AI provider configured. Open Settings → AI Providers → Add Provider.")
                return@launch
            }
            val model = agent.primaryModel.ifBlank { provider.defaultModel }
            if (model.isBlank()) {
                _needsProviderSetup.value = true
                finishWithError(convId, "No model selected. Edit the provider/agent and choose a Model.")
                return@launch
            }

            // Path 1: Gateway (remote tools, approvals, persistence server-side)
            if (tryGatewaySend(convId, agent.id, userText)) return@launch

            // Path 2: Local Workspace — direct device → provider streaming
            executeLocalChat(convId, agent.id, agent.systemPrompt, provider.id, model, agent.temperature)
        }
    }

    /** Returns true when the gateway accepted the message (streaming arrives via WS). */
    private suspend fun tryGatewaySend(convId: String, agentId: String, userText: String): Boolean {
        return try {
            val res = ApiClient.api.sendMessage(mapOf("conversationId" to convId, "content" to userText, "agentId" to agentId))
            if (res.isSuccessful) {
                _currentWorkStatus.value = "Agent working..."
                val runId = res.body()?.get("runId") as? String
                if (runId != null) {
                    // Safety net: if WS deltas never arrive (offline WS), poll until done.
                    viewModelScope.launch(Dispatchers.IO) { pollGatewayRun(convId, runId) }
                }
                true
            } else {
                val code = res.code()
                if (code == 401) {
                    // Auth required but no token — fall through to local mode
                    false
                } else {
                    val errBody = try {
                        res.errorBody()?.string() ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    // Provider/config errors surfaced by gateway are real — show them, don't fake.
                    if (code == 400 && (errBody.contains("provider", true) || errBody.contains("model", true))) {
                        finishWithError(convId, parseGatewayError(errBody))
                        true // handled (error shown)
                    } else {
                        false // connectivity/other → try local
                    }
                }
            }
        } catch (_: Exception) {
            false // gateway unreachable → local mode
        }
    }

    private fun parseGatewayError(body: String): String {
        return try {
            JSONObject(body).optString("error", "Request failed.")
        } catch (_: Exception) {
            "Request failed."
        }
    }

    /** Fallback finalizer when WS events are lost: polls run + messages, then settles UI. */
    private suspend fun pollGatewayRun(convId: String, runId: String) {
        val deadline = System.currentTimeMillis() + 5 * 60 * 1000
        var settled = false
        while (System.currentTimeMillis() < deadline && !settled) {
            try {
                kotlinx.coroutines.delay(2000)
                // If WS already settled the run, stop polling.
                if (!_isAgentWorking.value) return
                val run = ApiClient.api.getRun(runId)
                val status = (run.body()?.get("status") as? String) ?: continue
                if (status == "completed" || status == "failed") {
                    if (status == "failed") {
                        val err = (run.body()?.get("error") as? String) ?: "Run failed"
                        withContext(Dispatchers.Main) { finishWithError(convId, err) }
                    } else {
                        // Pull the persisted assistant message if WS never streamed it.
                        val msgs = ApiClient.api.getMessages(convId)
                        val lastAssistant = msgs.body()?.lastOrNull { it.role == "assistant" }
                        withContext(Dispatchers.Main) {
                            if (lastAssistant != null) {
                                finalizeStreamingMessage(lastAssistant.content)
                                _isAgentWorking.value = false
                                _currentWorkStatus.value = null
                            } else {
                                _isAgentWorking.value = false
                                _currentWorkStatus.value = null
                            }
                        }
                    }
                    settled = true
                }
            } catch (_: Exception) {
                // transient: keep polling until deadline
            }
        }
        // Deadline reached while still working: release UI honestly.
        if (!settled && _isAgentWorking.value) {
            withContext(Dispatchers.Main) {
                _isAgentWorking.value = false
                _currentWorkStatus.value = null
            }
        }
    }

    private suspend fun executeLocalChat(
        convId: String,
        agentId: String,
        systemPrompt: String,
        providerId: String,
        model: String,
        temperature: Float
    ) {
        val provider = providerRepository.getProvider(providerId) ?: run {
            finishWithError(convId, "Provider not found.")
            return
        }
        val apiKey = secureStore.getApiKey(providerId) ?: ""
        val headers = try {
            providerRepository.parseHeaders(provider.customHeadersJson)
        } catch (_: Exception) {
            emptyMap()
        }
        val history: List<ChatMessage> = withContext(Dispatchers.IO) {
            database.messageDao().getMessagesOnce(convId)
                .filter { it.role == "user" || it.role == "assistant" }
                .takeLast(30)
                .map { ChatMessage(it.role, it.content) }
        }
        // Recent memory (bounded, not full history)
        val memorySnippet = withContext(Dispatchers.IO) {
            try {
                database.memoryDao().recentForAgent(agentId, 3).joinToString("\n---\n") { it.content }.take(2000)
            } catch (_: Exception) {
                ""
            }
        }
        val fullHistory = if (memorySnippet.isNotBlank()) {
            listOf(ChatMessage("system", "Relevant memory:\n$memorySnippet")) + history
        } else history

        val runId = UUID.randomUUID().toString()
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        withContext(Dispatchers.IO) {
            try {
                database.runDao().upsert(
                    RunEntity(id = runId, conversationId = convId, agentId = agentId, status = "running", userPrompt = history.lastOrNull()?.content ?: "", createdAt = ts)
                )
            } catch (_: Exception) {}
            repository.logExecutionEvent(runId, convId, agentId, "info", "run.started", "Local run started ($model)")
        }

        _currentWorkStatus.value = "Streaming..."
        beginStreamingAssistant()

        val acc = StringBuilder()
        var pTok = 0
        var cTok = 0
        LocalChatExecutor.streamChat(provider, apiKey, model, systemPrompt, fullHistory, temperature, headers) { event ->
            when (event) {
                is StreamEvent.Delta -> {
                    acc.append(event.text)
                    appendStreamingAssistantDelta(event.text)
                }
                is StreamEvent.Usage -> {
                    pTok = event.promptTokens
                    cTok = event.completionTokens
                }
                is StreamEvent.Failed -> {
                    finishStreamingWithError(convId, runId, agentId, event.error)
                }
                StreamEvent.Done -> {
                    finalizeLocalAssistant(convId, runId, agentId, acc.toString(), pTok, cTok)
                }
            }
        }
    }

    private fun finalizeLocalAssistant(convId: String, runId: String, agentId: String, content: String, pTok: Int, cTok: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val final = content.ifBlank { "(empty response)" }
            finalizeStreamingMessage(final)
            repository.saveMessage(MessageModel(conversationId = convId, role = "assistant", content = final))
            try {
                database.runDao().upsert(
                    RunEntity(id = runId, conversationId = convId, agentId = agentId, status = "completed", promptTokens = pTok, completionTokens = cTok, totalTokens = pTok + cTok, createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
                )
            } catch (_: Exception) {}
            repository.logExecutionEvent(runId, convId, agentId, "info", "run.completed", "Local run completed.")
            withContext(Dispatchers.Main) {
                _isAgentWorking.value = false
                _currentWorkStatus.value = null
            }
        }
    }

    private fun finishStreamingWithError(convId: String, runId: String, agentId: String, error: String) {
        viewModelScope.launch(Dispatchers.IO) {
            finalizeStreamingMessage("Error: $error")
            repository.saveMessage(MessageModel(conversationId = convId, role = "assistant", content = "Error: $error"))
            try {
                database.runDao().upsert(
                    RunEntity(id = runId, conversationId = convId, agentId = agentId, status = "failed", error = error, createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
                )
            } catch (_: Exception) {}
            repository.logExecutionEvent(runId, convId, agentId, "error", "run.failed", error)
            withContext(Dispatchers.Main) {
                _isAgentWorking.value = false
                _currentWorkStatus.value = null
            }
        }
    }

    private fun finishWithError(convId: String, error: String) {
        addChatItem(ChatItem(role = "event", text = error))
        _isAgentWorking.value = false
        _currentWorkStatus.value = null
    }

    fun cancelCurrentRun() {
        // Best effort: stop WS run + reset state. Local streaming cannot be aborted mid-call
        // beyond ignoring further deltas; mark not-working so UI recovers.
        _isAgentWorking.value = false
        _currentWorkStatus.value = null
        val items = _chatItems.value
        if (items.isNotEmpty() && items.last().isStreaming) {
            _chatItems.value = items.dropLast(1) + items.last().copy(isStreaming = false)
        }
    }

    // ---------- Approvals (real) ----------

    fun approveAction(approval: ApprovalModel) {
        viewModelScope.launch {
            try {
                repository.resolveApproval(approval.id, "approved")
            } catch (_: Exception) {}
            updateApprovalInChat(approval.id, "approved")
            addChatItem(ChatItem(role = "event", text = "Approved: ${approval.toolName}. Resuming run..."))
        }
    }

    fun rejectAction(approval: ApprovalModel) {
        viewModelScope.launch {
            try {
                repository.resolveApproval(approval.id, "rejected")
            } catch (_: Exception) {}
            updateApprovalInChat(approval.id, "rejected")
            addChatItem(ChatItem(role = "event", text = "Rejected: ${approval.toolName}. Run notified."))
            _isAgentWorking.value = false
            _currentWorkStatus.value = null
        }
    }

    private fun updateApprovalInChat(id: String, status: String) {
        _chatItems.value = _chatItems.value.map { item ->
            if (item.approval?.id == id) {
                item.copy(approval = item.approval.copy(status = status))
            } else item
        }
    }

    private fun addChatItem(item: ChatItem) {
        // No duplicates: skip if identical last item
        val cur = _chatItems.value
        if (cur.isNotEmpty() && cur.last().id == item.id) return
        _chatItems.value = cur + item
    }

    private fun beginStreamingAssistant() {
        _chatItems.value = _chatItems.value + ChatItem(role = "assistant", text = "", isStreaming = true)
    }

    private fun appendStreamingAssistantDelta(delta: String) {
        val current = _chatItems.value
        if (current.isNotEmpty() && current.last().isStreaming) {
            val last = current.last()
            _chatItems.value = current.dropLast(1) + last.copy(text = last.text + delta)
        } else {
            _chatItems.value = current + ChatItem(role = "assistant", text = delta, isStreaming = true)
        }
    }

    private fun finalizeStreamingMessage(fullContent: String) {
        val current = _chatItems.value
        if (current.isNotEmpty() && current.last().isStreaming) {
            val last = current.last()
            _chatItems.value = current.dropLast(1) + last.copy(text = fullContent, isStreaming = false)
        } else if (fullContent.isNotBlank()) {
            _chatItems.value = current + ChatItem(role = "assistant", text = fullContent)
        }
    }

    // ---------- Settings / database ----------

    fun updateGatewayUrl(url: String) {
        _gatewayUrl.value = url
        ApiClient.setBaseUrl(url)
        try {
            wsManager.connect(ApiClient.getWebSocketUrl(), token = secureStore.getAuthToken())
        } catch (_: Exception) {}
        viewModelScope.launch {
            try {
                database.appSettingDao().put(com.example.data.local.AppSettingEntity("gateway_url", url))
            } catch (_: Exception) {}
        }
        refreshWorkspaceStatus()
    }

    fun resetDatabase(scope: String = "conversations") {
        viewModelScope.launch {
            repository.clearScope(scope)
            if (scope == "conversations" || scope == "factory") {
                _chatItems.value = emptyList()
                _selectedConversationId.value = null
            }
        }
    }

    @Deprecated("Use resetDatabase(scope)")
    fun resetDatabaseLegacy() = resetDatabase("factory")

    fun createAgent(agent: AgentModel) {
        viewModelScope.launch {
            repository.saveAgent(agent)
        }
    }

    fun deleteAgent(id: String) {
        viewModelScope.launch {
            repository.deleteAgent(id)
        }
    }

    // ---------- Automations (real: persisted + run = real agent run) ----------

    fun createAutomation(auto: AutomationModel) {
        viewModelScope.launch(Dispatchers.IO) {
            val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
            database.automationDao().upsert(
                com.example.data.local.AutomationEntity(
                    id = auto.id, name = auto.name, description = auto.description, type = auto.type,
                    cronExpression = auto.cronExpression, agentId = auto.agentId, prompt = auto.prompt,
                    enabled = auto.enabled, lastRunAt = auto.lastRunAt, nextRunAt = auto.nextRunAt,
                    lastStatus = auto.lastStatus, createdAt = ts, updatedAt = ts
                )
            )
            try {
                ApiClient.api.createAutomation(auto)
            } catch (_: Exception) {}
        }
    }

    fun toggleAutomation(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = database.automationDao().getAll().first()
            val found = all.firstOrNull { it.id == id } ?: return@launch
            database.automationDao().upsert(found.copy(enabled = !found.enabled, updatedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())))
        }
    }

    fun runAutomationNow(auto: AutomationModel) {
        viewModelScope.launch {
            // 1) Try gateway run (server executes a real agent run)
            try {
                val res = ApiClient.api.runAutomation(auto.id)
                if (res.isSuccessful) {
                    refreshAutomationsFromGateway()
                    return@launch
                }
            } catch (_: Exception) {}
            // 2) Local fallback: background WorkManager execution (no Activity timers)
            com.example.data.work.AutomationWorker.enqueue(getApplication(), auto.id)
            _currentWorkStatus.value = "Automation enqueued in background..."
        }
    }

    private suspend fun refreshAutomationsFromGateway() {
        try {
            val res = ApiClient.api.getAutomations()
            if (res.isSuccessful) {
                val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                res.body()?.forEach { m ->
                    database.automationDao().upsert(
                        com.example.data.local.AutomationEntity(
                            id = m.id, name = m.name, description = m.description, type = m.type,
                            cronExpression = m.cronExpression, agentId = m.agentId, prompt = m.prompt,
                            enabled = m.enabled, lastRunAt = m.lastRunAt, nextRunAt = m.nextRunAt,
                            lastStatus = m.lastStatus, createdAt = ts, updatedAt = ts
                        )
                    )
                }
            }
        } catch (_: Exception) {}
    }

    // ---------- Computer (real worker or honest offline) ----------

    fun computerAction(action: String, url: String? = null, text: String? = null, x: Int? = null, y: Int? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = _computerSession.value.id.takeIf { it != "local" } ?: "session-main"
            try {
                val payload = mutableMapOf<String, Any?>("action" to action)
                url?.let { payload["url"] = it }
                text?.let { payload["text"] = it }
                x?.let { payload["x"] = it }
                y?.let { payload["y"] = it }
                val res = ApiClient.api.sendComputerAction(id, payload)
                if (res.isSuccessful) {
                    val session = res.body()
                    withContext(Dispatchers.Main) {
                        _computerSession.value = _computerSession.value.copy(
                            status = "running",
                            activeUrl = url ?: _computerSession.value.activeUrl,
                            lastAction = "$action dispatched"
                        )
                    }
                    session?.let {
                        try {
                            database.computerSessionDao().upsert(
                                com.example.data.local.ComputerSessionEntity(
                                    id = id, status = "running", activeUrl = url ?: _computerSession.value.activeUrl,
                                    cursorX = x ?: 0, cursorY = y ?: 0, lastAction = action,
                                    updatedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
                                    createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                                )
                            )
                        } catch (_: Exception) {}
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _computerSession.value = _computerSession.value.copy(status = "stopped", lastAction = "Computer worker unavailable")
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    _computerSession.value = _computerSession.value.copy(status = "stopped", lastAction = "Computer worker unavailable")
                }
            }
        }
    }

    fun updateComputerUrl(url: String) = computerAction("navigate", url = url)

    fun stopComputer() {
        viewModelScope.launch {
            try {
                ApiClient.api.sendComputerAction(_computerSession.value.id, mapOf("action" to "stop"))
            } catch (_: Exception) {}
            _computerSession.value = _computerSession.value.copy(status = "stopped", lastAction = "Stopped")
        }
    }

    fun restartComputer() {
        viewModelScope.launch {
            try {
                ApiClient.api.sendComputerAction(_computerSession.value.id, mapOf("action" to "restart"))
                _computerSession.value = _computerSession.value.copy(status = "running", lastAction = "Restart requested")
            } catch (_: Exception) {
                _computerSession.value = _computerSession.value.copy(status = "stopped", lastAction = "Computer worker unavailable")
            }
        }
    }
}
