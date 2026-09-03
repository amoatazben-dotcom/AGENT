package com.example.data.repository

import android.content.Context
import com.example.data.local.AgentDatabase
import com.example.data.local.AgentEntity
import com.example.data.local.ApprovalEntity
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.data.model.*
import com.example.data.network.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRepository @Inject constructor(
    @ApplicationContext context: Context,
    val database: AgentDatabase = AgentDatabase.getDatabase(context)
) {
    private val agentDao = database.agentDao()
    private val convDao = database.conversationDao()
    private val msgDao = database.messageDao()
    private val approvalDao = database.approvalDao()
    private val logDao = database.executionLogDao()
    private val stateDao = database.agentStateDao()
    private val api = ApiClient.api

    val agents: Flow<List<AgentModel>> = agentDao.getAllAgents().map { list ->
        list.map { it.toModel() }
    }

    val conversations: Flow<List<ConversationModel>> = convDao.getAllConversations().map { list ->
        list.map { ConversationModel(it.id, it.title, it.agentId, it.createdAt) }
    }

    val approvals: Flow<List<ApprovalModel>> = approvalDao.getAllApprovals().map { list ->
        list.map {
            ApprovalModel(
                id = it.id,
                runId = it.runId,
                conversationId = it.conversationId,
                toolName = it.toolName,
                reason = it.reason,
                riskLevel = it.riskLevel,
                status = it.status,
                createdAt = it.createdAt
            )
        }
    }

    fun getMessages(convId: String): Flow<List<MessageModel>> =
        msgDao.getMessagesForConversation(convId).map { list ->
            list.map { MessageModel(it.id, it.conversationId, it.role, it.content, it.createdAt) }
        }

    suspend fun seedDefaultDataIfEmpty() = withContext(Dispatchers.IO) {
        // Seed ONLY when the database is completely empty. Never fabricate history.
        if (agentDao.count() > 0) return@withContext
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val defaultAgent = AgentEntity(
            id = "agent-default-1",
            name = "General Assistant",
            description = "General-purpose agent. Configure an AI provider to start chatting.",
            icon = "smart_toy",
            systemPrompt = "You are a helpful AI assistant.",
            primaryProvider = "openai_compatible",
            primaryModel = "",
            primaryProviderId = null,
            fallbackProviderId = null,
            fallbackModel = null,
            temperature = 0.7f,
            maxSteps = 25,
            approvalPolicy = "require_approval",
            computerPermission = false,
            shellPermission = false,
            filesystemPermission = true,
            networkPermission = true,
            automationPermission = false,
            subagentPermission = false
        )
        agentDao.insertAgents(listOf(defaultAgent))
    }

    suspend fun clearScope(scope: String) = withContext(Dispatchers.IO) {
        when (scope) {
            "conversations" -> {
                convDao.deleteAllConversations()
                msgDao.deleteAllMessages()
            }
            "execution" -> {
                msgDao.deleteAllMessages()
                approvalDao.deleteAllApprovals()
                logDao.deleteAllLogs()
                database.runDao().deleteAll()
                database.runEventDao().deleteAll()
                database.toolCallDao().deleteAll()
                database.automationRunDao().deleteAll()
                database.computerSessionDao().deleteAll()
                database.fileDao().deleteAll()
            }
            "cache" -> {
                database.providerModelDao().deleteAll()
            }
            "factory" -> {
                convDao.deleteAllConversations()
                msgDao.deleteAllMessages()
                approvalDao.deleteAllApprovals()
                logDao.deleteAllLogs()
                agentDao.deleteAllAgents()
                database.automationDao().deleteAll()
                database.automationRunDao().deleteAll()
                database.memoryDao().deleteAll()
                database.providerDao().deleteAllProviders()
                database.providerModelDao().deleteAll()
                database.fileDao().deleteAll()
                database.computerSessionDao().deleteAll()
                seedDefaultDataIfEmpty()
            }
        }
    }

    suspend fun clearAndReseedDatabase() = withContext(Dispatchers.IO) {
        agentDao.deleteAllAgents()
        convDao.deleteAllConversations()
        msgDao.deleteAllMessages()
        approvalDao.deleteAllApprovals()
        logDao.deleteAllLogs()
        seedDefaultDataIfEmpty()
    }

    suspend fun saveAgent(agent: AgentModel) = withContext(Dispatchers.IO) {
        agentDao.insertAgent(agent.toEntity())
        try {
            api.createAgent(agent)
        } catch (_: Exception) {}
    }

    suspend fun deleteAgent(id: String) = withContext(Dispatchers.IO) {
        // Keep conversations: reassign to the default agent instead of violating the FK.
        try {
            val fallback = agentDao.getAgentById("agent-default-1")?.id
                ?: agentDao.getAllOnce().firstOrNull { it.id != id }?.id
            if (fallback != null) {
                convDao.reassignAgent(id, fallback)
            } else {
                // No agent left: conversations cannot exist without an agent link target;
                // delete them explicitly (FK has no SET NULL on purpose).
                convDao.deleteAllConversations()
                msgDao.deleteAllMessages()
            }
        } catch (_: Exception) {}
        agentDao.deleteAgent(id)
        try {
            api.deleteAgent(id)
        } catch (_: Exception) {}
    }

    suspend fun createConversation(title: String, agentId: String): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        convDao.insertConversation(ConversationEntity(id, title, agentId, now))
        try {
            api.createConversation(mapOf("title" to title, "agentId" to agentId))
        } catch (_: Exception) {}
        id
    }

    suspend fun deleteConversation(id: String) = withContext(Dispatchers.IO) {
        convDao.deleteConversation(id)
        msgDao.clearConversationMessages(id)
        logDao.clearLogsForConversation(id)
        try {
            api.deleteConversation(id)
        } catch (_: Exception) {}
    }

    suspend fun saveMessage(msg: MessageModel) = withContext(Dispatchers.IO) {
        msgDao.insertMessage(MessageEntity(msg.id, msg.conversationId, msg.role, msg.content, msg.createdAt))
    }

    suspend fun saveApproval(approval: ApprovalModel) = withContext(Dispatchers.IO) {
        val argsJson = try {
            org.json.JSONObject(approval.arguments.mapValues { it.value?.toString() ?: "" } as Map<String, String>).toString()
        } catch (_: Exception) {
            "{}"
        }
        approvalDao.insertApproval(
            ApprovalEntity(
                id = approval.id,
                runId = approval.runId,
                conversationId = approval.conversationId,
                toolName = approval.toolName,
                argumentsJson = argsJson,
                reason = approval.reason,
                riskLevel = approval.riskLevel,
                status = approval.status,
                createdAt = approval.createdAt.ifBlank {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                }
            )
        )
    }

    suspend fun resolveApproval(approvalId: String, decision: String) = withContext(Dispatchers.IO) {
        approvalDao.updateApprovalStatus(approvalId, decision)
        ApiClient.webSocketManager.resolveApproval(approvalId, decision)
        try {
            api.resolveApproval(approvalId, mapOf("decision" to decision))
        } catch (_: Exception) {}
    }

    val executionLogs: Flow<List<com.example.data.local.ExecutionLogEntity>> = logDao.getAllLogs()

    fun getLogsForConversation(convId: String): Flow<List<com.example.data.local.ExecutionLogEntity>> =
        logDao.getLogsForConversation(convId)

    suspend fun logExecutionEvent(
        runId: String,
        conversationId: String,
        agentId: String,
        level: String,
        event: String,
        message: String,
        details: String = ""
    ) = withContext(Dispatchers.IO) {
        logDao.insertLog(
            com.example.data.local.ExecutionLogEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                conversationId = conversationId,
                agentId = agentId,
                level = level,
                event = event,
                message = message,
                details = details
            )
        )
    }

    suspend fun updateAgentState(
        agentId: String,
        status: String,
        currentTask: String = "",
        activeUrl: String = "",
        lastRunId: String = ""
    ) = withContext(Dispatchers.IO) {
        stateDao.insertOrUpdateState(
            com.example.data.local.AgentStateEntity(
                agentId = agentId,
                status = status,
                currentTask = currentTask,
                activeUrl = activeUrl,
                lastRunId = lastRunId
            )
        )
    }

    fun getAgentState(agentId: String): Flow<com.example.data.local.AgentStateEntity?> =
        stateDao.getState(agentId)


    private fun AgentEntity.toModel(): AgentModel = AgentModel(
        id = id,
        name = name,
        description = description,
        icon = icon,
        systemPrompt = systemPrompt,
        primaryProvider = primaryProvider,
        primaryModel = primaryModel,
        primaryProviderId = primaryProviderId,
        fallbackProviderId = fallbackProviderId,
        fallbackModel = fallbackModel,
        temperature = temperature,
        maxSteps = maxSteps,
        approvalPolicy = approvalPolicy,
        computerPermission = computerPermission,
        shellPermission = shellPermission,
        filesystemPermission = filesystemPermission,
        networkPermission = networkPermission,
        automationPermission = automationPermission,
        subagentPermission = subagentPermission
    )

    private fun AgentModel.toEntity(): AgentEntity = AgentEntity(
        id = id,
        name = name,
        description = description,
        icon = icon,
        systemPrompt = systemPrompt,
        primaryProvider = primaryProvider,
        primaryModel = primaryModel,
        primaryProviderId = primaryProviderId,
        fallbackProviderId = fallbackProviderId,
        fallbackModel = fallbackModel,
        temperature = temperature,
        maxSteps = maxSteps,
        approvalPolicy = approvalPolicy,
        computerPermission = computerPermission,
        shellPermission = shellPermission,
        filesystemPermission = filesystemPermission,
        networkPermission = networkPermission,
        automationPermission = automationPermission,
        subagentPermission = subagentPermission
    )
}
