package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val systemPrompt: String,
    val primaryProvider: String,
    val primaryModel: String,
    val primaryProviderId: String? = null,
    val fallbackProviderId: String? = null,
    val fallbackModel: String? = null,
    val temperature: Float,
    val maxSteps: Int,
    val approvalPolicy: String,
    val computerPermission: Boolean,
    val shellPermission: Boolean,
    val filesystemPermission: Boolean,
    val networkPermission: Boolean,
    val automationPermission: Boolean,
    val subagentPermission: Boolean
)

@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("agentId")]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val agentId: String,
    val createdAt: String
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: String
)

@Entity(
    tableName = "approvals",
    indices = [Index("runId"), Index("status")]
)
data class ApprovalEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val conversationId: String,
    val toolName: String,
    val argumentsJson: String = "{}",
    val reason: String,
    val riskLevel: String,
    val status: String,
    val decisionAt: String? = null,
    val createdAt: String
)

@Entity(tableName = "execution_logs")
data class ExecutionLogEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val conversationId: String,
    val agentId: String,
    val level: String, // info, warn, error, debug, tool
    val event: String,
    val message: String,
    val details: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "agent_states")
data class AgentStateEntity(
    @PrimaryKey val agentId: String,
    val status: String, // idle, running, awaiting_approval, stopped, error
    val currentTask: String = "",
    val activeUrl: String = "",
    val lastRunId: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

// ==========================================
// AI Providers (metadata in Room; secret key in SecureStore)
// ==========================================
@Entity(tableName = "ai_providers")
data class AIProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val organizationId: String? = null,
    val projectId: String? = null,
    val defaultModel: String = "",
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val supportsStreaming: Boolean = true,
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = false,
    val customHeadersJson: String = "{}",
    val timeoutMs: Int = 60000,
    val status: String = "unknown",
    val lastTestedAt: String? = null,
    val lastError: String? = null,
    val latencyMs: Long? = null,
    val hasApiKey: Boolean = false,
    val createdAt: String,
    val updatedAt: String
)

@Entity(
    tableName = "provider_models",
    foreignKeys = [
        ForeignKey(
            entity = AIProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("providerId")]
)
data class ProviderModelEntity(
    @PrimaryKey val id: String, // "$providerId:$modelId"
    val providerId: String,
    val modelId: String,
    val name: String,
    val fetchedAt: String
)

@Entity(
    tableName = "runs",
    indices = [Index("conversationId"), Index("agentId"), Index("status")]
)
data class RunEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val agentId: String,
    val status: String,
    val userPrompt: String = "",
    val currentStep: Int = 0,
    val maxSteps: Int = 25,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val error: String? = null,
    val createdAt: String
)

@Entity(
    tableName = "run_events",
    indices = [Index("runId")]
)
data class RunEventEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val seq: Int,
    val type: String,
    val payloadJson: String,
    val createdAt: String
)

@Entity(
    tableName = "tool_calls",
    indices = [Index("runId")]
)
data class ToolCallEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val toolName: String,
    val argumentsJson: String = "{}",
    val status: String = "completed", // pending, running, completed, failed
    val resultJson: String? = null,
    val error: String? = null,
    val createdAt: String
)

@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val type: String = "cron",
    val cronExpression: String? = null,
    val agentId: String,
    val prompt: String,
    val enabled: Boolean = true,
    val lastRunAt: String? = null,
    val nextRunAt: String? = null,
    val lastStatus: String? = null,
    val lastResult: String? = null,
    val lastError: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Entity(tableName = "automation_runs")
data class AutomationRunEntity(
    @PrimaryKey val id: String,
    val automationId: String,
    val runId: String? = null,
    val status: String = "running",
    val result: String? = null,
    val error: String? = null,
    val startedAt: String,
    val completedAt: String? = null
)

@Entity(
    tableName = "memories",
    indices = [Index("agentId")]
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val conversationId: String? = null,
    val kind: String = "long-term", // working, conversation, long-term
    val content: String,
    val createdAt: String,
    val updatedAt: String
)

@Entity(
    tableName = "files",
    indices = [Index("conversationId"), Index("runId")]
)
data class FileEntity(
    @PrimaryKey val id: String,
    val conversationId: String? = null,
    val runId: String? = null,
    val name: String,
    val path: String,
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0,
    val createdAt: String
)

@Entity(tableName = "computer_sessions")
data class ComputerSessionEntity(
    @PrimaryKey val id: String,
    val runId: String? = null,
    val status: String = "stopped",
    val activeUrl: String = "about:blank",
    val cursorX: Int = 0,
    val cursorY: Int = 0,
    val lastAction: String = "",
    val updatedAt: String,
    val createdAt: String
)

@Entity(tableName = "connection_profiles")
data class ConnectionProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mode: String = "local", // local, remote, lan, custom
    val baseUrl: String = "",
    val webSocketUrl: String = "",
    val enabled: Boolean = true,
    val lastConnectionStatus: String = "unknown",
    val lastConnectedAt: String? = null
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)
