package com.example.data.model

import com.squareup.moshi.JsonClass
import java.util.UUID

@JsonClass(generateAdapter = true)
data class AgentModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val icon: String = "smart_toy",
    val systemPrompt: String,
    val primaryProvider: String = "openai_compatible",
    val primaryModel: String = "",
    val primaryProviderId: String? = null,
    val fallbackProvider: String? = null,
    val fallbackModel: String? = null,
    val fallbackProviderId: String? = null,
    val temperature: Float = 0.7f,
    val maxSteps: Int = 25,
    val approvalPolicy: String = "require_approval", // allow, deny, require_approval
    val computerPermission: Boolean = true,
    val shellPermission: Boolean = true,
    val filesystemPermission: Boolean = true,
    val networkPermission: Boolean = true,
    val automationPermission: Boolean = true,
    val subagentPermission: Boolean = true
)

@JsonClass(generateAdapter = true)
data class ConversationModel(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val agentId: String,
    val createdAt: String = ""
)

@JsonClass(generateAdapter = true)
data class MessageModel(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: String, // user, assistant, tool, system
    val content: String,
    val createdAt: String = ""
)

@JsonClass(generateAdapter = true)
data class ApprovalModel(
    val id: String = UUID.randomUUID().toString(),
    val runId: String = "",
    val conversationId: String = "",
    val toolName: String,
    val arguments: Map<String, Any?> = emptyMap(),
    val reason: String,
    val riskLevel: String = "medium", // low, medium, high, critical
    val status: String = "pending", // pending, approved, rejected
    val createdAt: String = ""
)

@JsonClass(generateAdapter = true)
data class AutomationModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val type: String = "cron", // one_time, cron, recurring
    val cronExpression: String = "0 8 * * *",
    val agentId: String,
    val prompt: String,
    val enabled: Boolean = true,
    val lastRunAt: String? = null,
    val nextRunAt: String? = null,
    val lastStatus: String? = null
)

@JsonClass(generateAdapter = true)
data class ComputerSessionModel(
    val id: String = UUID.randomUUID().toString(),
    val status: String = "idle", // running, idle, busy, stopped
    val activeUrl: String = "https://openai.com",
    val cursorX: Int = 120,
    val cursorY: Int = 240,
    val lastAction: String = "Initialized browser environment",
    val latestScreenshotBase64: String? = null
)

@JsonClass(generateAdapter = true)
data class FileItemModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String,
    val mimeType: String = "text/markdown",
    val size: Long = 1024,
    val conversationId: String? = null,
    val runId: String? = null,
    val downloadUrl: String = "",
    val contentPreview: String = ""
)

@JsonClass(generateAdapter = true)
data class ConnectorModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String, // http, oauth2, mcp, github, slack, notion, gmail
    val description: String = "",
    val status: String = "connected" // connected, disconnected, error
)

@JsonClass(generateAdapter = true)
data class WsEventEnvelope(
    val eventId: String = "",
    val seq: Int = 0,
    val type: String, // assistant.delta, tool.started, tool.completed, approval.created, etc.
    val timestamp: String = "",
    val runId: String? = null,
    val conversationId: String? = null,
    val payload: Map<String, Any?> = emptyMap()
)

// UI Chat representation
data class ChatItem(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // user, assistant, event, tool, approval
    val text: String = "",
    val toolName: String? = null,
    val toolStatus: String? = null,
    val isStreaming: Boolean = false,
    val approval: ApprovalModel? = null,
    val timestamp: Long = System.currentTimeMillis()
)

// AI provider config (metadata only; key lives in SecureStore)
data class ProviderModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String, // openai, openai_compatible, anthropic, gemini, ollama, ...
    val baseUrl: String,
    val defaultModel: String = "",
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val status: String = "unknown",
    val lastError: String? = null,
    val latencyMs: Long? = null,
    val hasApiKey: Boolean = false,
    val maskedKey: String = ""
)

data class WorkspaceStatus(
    val database: String = "Unknown",
    val aiProvider: String = "Not configured",
    val gateway: String = "Local",
    val computerWorker: String = "Offline"
)
