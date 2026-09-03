package com.example.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.ai.ChatMessage
import com.example.data.ai.LocalChatExecutor
import com.example.data.ai.StreamEvent
import com.example.data.local.AgentDatabase
import com.example.data.local.AutomationRunEntity
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.data.local.RunEntity
import com.example.data.security.SecureStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Executes an automation's agent run in the background (no Activity timers).
 * Gateway-first is attempted by the UI; this worker is the local fallback AND
 * the offline path: device → provider streaming, all persisted in Room.
 */
class AutomationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val automationId = inputData.getString(KEY_AUTOMATION_ID) ?: return Result.failure()
        val db = AgentDatabase.getDatabase(applicationContext)
        val secureStore = SecureStore(applicationContext)
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

        val auto = db.automationDao().getById(automationId) ?: return Result.failure()

        val runId = UUID.randomUUID().toString()
        val startedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        try {
            val agent = db.agentDao().getAgentById(auto.agentId) ?: return Result.failure()
            val provider = agent.primaryProviderId?.let { db.providerDao().getProviderById(it) }
                ?: db.providerDao().getDefaultProvider()
                ?: db.providerDao().getEnabledProviders().firstOrNull()
                ?: return Result.failure()
            val model = agent.primaryModel.ifBlank { provider.defaultModel }
            if (model.isBlank()) return Result.failure()
            val apiKey = secureStore.getApiKey(provider.id) ?: ""

            val convId = UUID.randomUUID().toString()
            db.conversationDao().insertConversation(
                ConversationEntity(convId, "Automation: ${auto.name}", agent.id, startedAt)
            )
            db.runDao().upsert(
                RunEntity(runId, convId, agent.id, "running", auto.prompt, createdAt = startedAt)
            )
            db.automationDao().upsert(auto.copy(lastRunAt = startedAt, lastStatus = "running", updatedAt = ts))
            db.automationRunDao().upsert(
                AutomationRunEntity(UUID.randomUUID().toString(), automationId, runId, "running", startedAt = startedAt)
            )

            val acc = StringBuilder()
            LocalChatExecutor.streamChat(
                provider = provider,
                apiKey = apiKey,
                model = model,
                systemPrompt = agent.systemPrompt,
                history = listOf(ChatMessage("user", auto.prompt)),
                temperature = agent.temperature
            ) { event ->
                when (event) {
                    is StreamEvent.Delta -> acc.append(event.text)
                    is StreamEvent.Failed -> throw IllegalStateException(event.error)
                    else -> Unit
                }
            }
            val final = acc.toString().ifBlank { "(empty response)" }
            db.messageDao().insertMessage(MessageEntity(UUID.randomUUID().toString(), convId, "user", auto.prompt, startedAt))
            db.messageDao().insertMessage(MessageEntity(UUID.randomUUID().toString(), convId, "assistant", final, ts))
            val done = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
            db.runDao().upsert(RunEntity(runId, convId, agent.id, "completed", createdAt = done))
            db.automationDao().upsert(auto.copy(lastRunAt = startedAt, lastStatus = "success", lastResult = final.take(2000), lastError = null, updatedAt = done))
            db.automationRunDao().upsert(AutomationRunEntity(UUID.randomUUID().toString(), automationId, runId, "success", final.take(5000), null, startedAt, done))
            return Result.success()
        } catch (e: Exception) {
            val done = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
            return try {
                db.automationDao().upsert(auto.copy(lastStatus = "failed", lastError = e.message, updatedAt = done))
                db.automationRunDao().upsert(AutomationRunEntity(UUID.randomUUID().toString(), automationId, runId, "failed", null, e.message, startedAt, done))
                if (isStopped) Result.failure() else Result.retry()
            } catch (_: Exception) {
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_AUTOMATION_ID = "automation_id"

        fun enqueue(context: Context, automationId: String) {
            val req = OneTimeWorkRequestBuilder<AutomationWorker>()
                .setInputData(workDataOf(KEY_AUTOMATION_ID to automationId))
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
