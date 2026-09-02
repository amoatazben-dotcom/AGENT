package com.example.data.local

import androidx.room.Entity
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

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val agentId: String,
    val createdAt: String
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: String
)

@Entity(tableName = "approvals")
data class ApprovalEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val conversationId: String,
    val toolName: String,
    val reason: String,
    val riskLevel: String,
    val status: String,
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

