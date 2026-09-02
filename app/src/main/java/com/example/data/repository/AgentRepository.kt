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
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val defaultAgent = AgentEntity(
            id = "agent-default-1",
            name = "الوكيل التنفيذي الذاتي (Executive Agent)",
            description = "منسق متعدد الأدوات للأبحاث، تصفح الويب، تنفيذ الأوامر، وإدارة الحاويات.",
            icon = "smart_toy",
            systemPrompt = "You are an executive autonomous AI agent equipped with browser, shell, and filesystem capabilities.",
            primaryProvider = "gemini",
            primaryModel = "gemini-1.5-flash",
            temperature = 0.7f,
            maxSteps = 25,
            approvalPolicy = "require_approval",
            computerPermission = true,
            shellPermission = true,
            filesystemPermission = true,
            networkPermission = true,
            automationPermission = true,
            subagentPermission = true
        )
        val coderAgent = AgentEntity(
            id = "agent-coder-2",
            name = "مهندس البرمجيات والطرفية (Code Engineer)",
            description = "متخصص في تشغيل نصوص الطرفية، فحص الأكواد، واختبار المستودعات البرمجية.",
            icon = "terminal",
            systemPrompt = "You are a Principal Software Engineer executing code tasks inside container sandboxes.",
            primaryProvider = "openai",
            primaryModel = "gpt-4o",
            temperature = 0.2f,
            maxSteps = 30,
            approvalPolicy = "require_approval",
            computerPermission = true,
            shellPermission = true,
            filesystemPermission = true,
            networkPermission = true,
            automationPermission = true,
            subagentPermission = true
        )
        val researchAgent = AgentEntity(
            id = "agent-research-3",
            name = "محلل البيانات والأبحاث (Research Specialist)",
            description = "متخصص في جمع المعلومات من الويب، المقارنات العلمية، وتوليد تقارير Markdown مفصلة.",
            icon = "search",
            systemPrompt = "You are an autonomous research agent collecting, synthesizing, and formatting deep research.",
            primaryProvider = "gemini",
            primaryModel = "gemini-1.5-pro",
            temperature = 0.4f,
            maxSteps = 20,
            approvalPolicy = "require_approval",
            computerPermission = true,
            shellPermission = false,
            filesystemPermission = true,
            networkPermission = true,
            automationPermission = true,
            subagentPermission = true
        )
        agentDao.insertAgents(listOf(defaultAgent, coderAgent, researchAgent))

        val welcomeConv = ConversationEntity(
            id = "conv-welcome-1",
            title = "مهمة البحث والتصفح الذاتي",
            agentId = defaultAgent.id,
            createdAt = now
        )
        convDao.insertConversation(welcomeConv)

        val sampleMsg = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = welcomeConv.id,
            role = "assistant",
            content = "مرحباً بك في **AgentForge**! أنا وكيلك الذكي المستقل، أعمل محلياً بالكامل عبر قاعدة بيانات Room ومجهز بأدوات التصفح، الطرفية، ونظام الملفات.\n\nيمكنك تجربة أحد الأوامر مثل:\n• «تصفح موقع OpenAI والتقط لقطة شاشة للحاسوب وانشئ ملف تقرير»\n• «قارن بين مكتبات الذكاء الاصطناعي»\n• «احذف الملف التجريبي (لاختبار الموافقة البشرية)»",
            createdAt = now
        )
        msgDao.insertMessage(sampleMsg)

        val initialLogs = listOf(
            com.example.data.local.ExecutionLogEntity(
                id = "log-init-1",
                runId = "run-bootstrap-01",
                conversationId = welcomeConv.id,
                agentId = defaultAgent.id,
                level = "info",
                event = "system.boot",
                message = "تم تشغيل المحرك المحلي لقاعدة بيانات Room وتجهيز طبقة الوكلاء المستقلة.",
                details = "بيئة التشغيل: Sandbox محلي آمن مع دعم كامل للغة العربية",
                timestamp = System.currentTimeMillis() - 360000
            ),
            com.example.data.local.ExecutionLogEntity(
                id = "log-init-2",
                runId = "run-bootstrap-01",
                conversationId = welcomeConv.id,
                agentId = defaultAgent.id,
                level = "tool",
                event = "tool.registered",
                message = "تسجيل أدوات التصفح، الطرفية، نظام الملفات، وتوليد التقارير.",
                details = "سياسة الأمان: اشتراط الموافقة البشرية على العمليات الحساسة",
                timestamp = System.currentTimeMillis() - 180000
            ),
            com.example.data.local.ExecutionLogEntity(
                id = "log-init-3",
                runId = "run-bootstrap-01",
                conversationId = welcomeConv.id,
                agentId = defaultAgent.id,
                level = "info",
                event = "agent.ready",
                message = "الوكيل جاهز لتلقي المهام محلياً وتنفيذها ذاتياً.",
                details = "النماذج المعتمدة: Gemini 1.5 Pro / Flash مع محرك استدلال محلي",
                timestamp = System.currentTimeMillis() - 60000
            )
        )
        logDao.insertLogs(initialLogs)
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

    suspend fun saveMessage(msg: MessageModel) = withContext(Dispatchers.IO) {
        msgDao.insertMessage(MessageEntity(msg.id, msg.conversationId, msg.role, msg.content, msg.createdAt))
    }

    suspend fun saveApproval(approval: ApprovalModel) = withContext(Dispatchers.IO) {
        approvalDao.insertApproval(
            ApprovalEntity(
                id = approval.id,
                runId = approval.runId,
                conversationId = approval.conversationId,
                toolName = approval.toolName,
                reason = approval.reason,
                riskLevel = approval.riskLevel,
                status = approval.status,
                createdAt = approval.createdAt
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
