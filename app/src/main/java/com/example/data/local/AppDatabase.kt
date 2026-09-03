package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents")
    fun getAllAgents(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun getAgentById(id: String): AgentEntity?

    @Query("SELECT * FROM agents")
    suspend fun getAllOnce(): List<AgentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgents(agents: List<AgentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgent(agent: AgentEntity)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun deleteAgent(id: String)

    @Query("DELETE FROM agents")
    suspend fun deleteAllAgents()

    @Query("SELECT COUNT(*) FROM agents")
    suspend fun count(): Int
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY createdAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conv: ConversationEntity)

    @Query("UPDATE conversations SET agentId = :newAgentId WHERE agentId = :oldAgentId")
    suspend fun reassignAgent(oldAgentId: String, newAgentId: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt ASC")
    fun getMessagesForConversation(convId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt ASC")
    suspend fun getMessagesOnce(convId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(msg: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :convId")
    suspend fun clearConversationMessages(convId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}

@Dao
interface ApprovalDao {
    @Query("SELECT * FROM approvals ORDER BY createdAt DESC")
    fun getAllApprovals(): Flow<List<ApprovalEntity>>

    @Query("SELECT * FROM approvals WHERE status = 'pending' ORDER BY createdAt DESC")
    fun getPendingApprovals(): Flow<List<ApprovalEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApproval(approval: ApprovalEntity)

    @Query("UPDATE approvals SET status = :status WHERE id = :id")
    suspend fun updateApprovalStatus(id: String, status: String)

    @Query("DELETE FROM approvals")
    suspend fun deleteAllApprovals()
}

@Dao
interface ExecutionLogDao {
    @Query("SELECT * FROM execution_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<ExecutionLogEntity>>

    @Query("SELECT * FROM execution_logs WHERE conversationId = :convId ORDER BY timestamp ASC")
    fun getLogsForConversation(convId: String): Flow<List<ExecutionLogEntity>>

    @Query("SELECT * FROM execution_logs WHERE runId = :runId ORDER BY timestamp ASC")
    fun getLogsForRun(runId: String): Flow<List<ExecutionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ExecutionLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<ExecutionLogEntity>)

    @Query("DELETE FROM execution_logs WHERE conversationId = :convId")
    suspend fun clearLogsForConversation(convId: String)

    @Query("DELETE FROM execution_logs")
    suspend fun deleteAllLogs()
}

@Dao
interface AgentStateDao {
    @Query("SELECT * FROM agent_states")
    fun getAllStates(): Flow<List<AgentStateEntity>>

    @Query("SELECT * FROM agent_states WHERE agentId = :agentId")
    fun getState(agentId: String): Flow<AgentStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateState(state: AgentStateEntity)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM ai_providers ORDER BY updatedAt DESC")
    fun getAllProviders(): Flow<List<AIProviderEntity>>

    @Query("SELECT * FROM ai_providers WHERE id = :id")
    suspend fun getProviderById(id: String): AIProviderEntity?

    @Query("SELECT * FROM ai_providers WHERE enabled = 1 ORDER BY isDefault DESC, updatedAt DESC")
    suspend fun getEnabledProviders(): List<AIProviderEntity>

    @Query("SELECT * FROM ai_providers WHERE isDefault = 1 AND enabled = 1 LIMIT 1")
    suspend fun getDefaultProvider(): AIProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: AIProviderEntity)

    @Query("UPDATE ai_providers SET isDefault = 0")
    suspend fun clearDefaults()

    @Query("DELETE FROM ai_providers WHERE id = :id")
    suspend fun deleteProvider(id: String)

    @Query("DELETE FROM ai_providers")
    suspend fun deleteAllProviders()
}

@Dao
interface ProviderModelDao {
    @Query("SELECT * FROM provider_models WHERE providerId = :providerId ORDER BY modelId ASC")
    fun getModelsForProvider(providerId: String): Flow<List<ProviderModelEntity>>

    @Query("SELECT * FROM provider_models WHERE providerId = :providerId ORDER BY modelId ASC")
    suspend fun getModelsOnce(providerId: String): List<ProviderModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(models: List<ProviderModelEntity>)

    @Query("DELETE FROM provider_models WHERE providerId = :providerId")
    suspend fun clearForProvider(providerId: String)

    @Query("DELETE FROM provider_models")
    suspend fun deleteAll()
}

@Dao
interface RunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: RunEntity)

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun getRun(id: String): RunEntity?

    @Query("SELECT * FROM runs WHERE conversationId = :convId ORDER BY createdAt DESC")
    fun getRunsForConversation(convId: String): Flow<List<RunEntity>>

    @Query("DELETE FROM runs")
    suspend fun deleteAll()
}

@Dao
interface RunEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: RunEventEntity)

    @Query("SELECT * FROM run_events WHERE runId = :runId ORDER BY seq ASC")
    suspend fun getForRun(runId: String): List<RunEventEntity>

    @Query("DELETE FROM run_events")
    suspend fun deleteAll()
}

@Dao
interface ToolCallDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(call: ToolCallEntity)

    @Query("SELECT * FROM tool_calls WHERE runId = :runId ORDER BY createdAt ASC")
    suspend fun getForRun(runId: String): List<ToolCallEntity>

    @Query("DELETE FROM tool_calls")
    suspend fun deleteAll()
}

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<AutomationEntity>>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getById(id: String): AutomationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(auto: AutomationEntity)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM automations")
    suspend fun deleteAll()
}

@Dao
interface AutomationRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: AutomationRunEntity)

    @Query("SELECT * FROM automation_runs WHERE automationId = :autoId ORDER BY startedAt DESC")
    suspend fun getForAutomation(autoId: String): List<AutomationRunEntity>

    @Query("DELETE FROM automation_runs")
    suspend fun deleteAll()
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Query("SELECT * FROM memories WHERE agentId = :agentId ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recentForAgent(agentId: String, limit: Int = 10): List<MemoryEntity>

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}

@Dao
interface FileDao {
    @Query("SELECT * FROM files ORDER BY createdAt DESC")
    fun getAll(): Flow<List<FileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: FileEntity)

    @Query("DELETE FROM files WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM files")
    suspend fun deleteAll()
}

@Dao
interface ComputerSessionDao {
    @Query("SELECT * FROM computer_sessions ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<ComputerSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ComputerSessionEntity)

    @Query("DELETE FROM computer_sessions")
    suspend fun deleteAll()
}

@Dao
interface ConnectionProfileDao {
    @Query("SELECT * FROM connection_profiles")
    fun getAll(): Flow<List<ConnectionProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ConnectionProfileEntity)

    @Query("DELETE FROM connection_profiles")
    suspend fun deleteAll()
}

@Dao
interface AppSettingDao {
    @Query("SELECT * FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): AppSettingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: AppSettingEntity)

    @Query("DELETE FROM app_settings")
    suspend fun deleteAll()
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `ai_providers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `organizationId` TEXT, `projectId` TEXT, `defaultModel` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL, `supportsStreaming` INTEGER NOT NULL, `supportsTools` INTEGER NOT NULL, `supportsVision` INTEGER NOT NULL, `customHeadersJson` TEXT NOT NULL, `timeoutMs` INTEGER NOT NULL, `status` TEXT NOT NULL, `lastTestedAt` TEXT, `lastError` TEXT, `latencyMs` INTEGER, `hasApiKey` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `provider_models` (`id` TEXT NOT NULL, `providerId` TEXT NOT NULL, `modelId` TEXT NOT NULL, `name` TEXT NOT NULL, `fetchedAt` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`providerId`) REFERENCES `ai_providers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_models_providerId` ON `provider_models` (`providerId`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `runs` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `agentId` TEXT NOT NULL, `status` TEXT NOT NULL, `userPrompt` TEXT NOT NULL, `currentStep` INTEGER NOT NULL, `maxSteps` INTEGER NOT NULL, `promptTokens` INTEGER NOT NULL, `completionTokens` INTEGER NOT NULL, `totalTokens` INTEGER NOT NULL, `error` TEXT, `createdAt` TEXT NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_runs_conversationId` ON `runs` (`conversationId`)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_runs_agentId` ON `runs` (`agentId`)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_runs_status` ON `runs` (`status`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `run_events` (`id` TEXT NOT NULL, `runId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `type` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `createdAt` TEXT NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_events_runId` ON `run_events` (`runId`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `tool_calls` (`id` TEXT NOT NULL, `runId` TEXT NOT NULL, `toolName` TEXT NOT NULL, `argumentsJson` TEXT NOT NULL, `status` TEXT NOT NULL, `resultJson` TEXT, `error` TEXT, `createdAt` TEXT NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tool_calls_runId` ON `tool_calls` (`runId`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `automations` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `type` TEXT NOT NULL, `cronExpression` TEXT, `agentId` TEXT NOT NULL, `prompt` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `lastRunAt` TEXT, `nextRunAt` TEXT, `lastStatus` TEXT, `lastResult` TEXT, `lastError` TEXT, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `automation_runs` (`id` TEXT NOT NULL, `automationId` TEXT NOT NULL, `runId` TEXT, `status` TEXT NOT NULL, `result` TEXT, `error` TEXT, `startedAt` TEXT NOT NULL, `completedAt` TEXT, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `memories` (`id` TEXT NOT NULL, `agentId` TEXT NOT NULL, `conversationId` TEXT, `kind` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_agentId` ON `memories` (`agentId`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `files` (`id` TEXT NOT NULL, `conversationId` TEXT, `runId` TEXT, `name` TEXT NOT NULL, `path` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_conversationId` ON `files` (`conversationId`)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_runId` ON `files` (`runId`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `computer_sessions` (`id` TEXT NOT NULL, `runId` TEXT, `status` TEXT NOT NULL, `activeUrl` TEXT NOT NULL, `cursorX` INTEGER NOT NULL, `cursorY` INTEGER NOT NULL, `lastAction` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, `createdAt` TEXT NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `connection_profiles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `mode` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `webSocketUrl` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `lastConnectionStatus` TEXT NOT NULL, `lastConnectedAt` TEXT, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))");
        // Extend agents with provider-id columns (nullable; default keeps legacy string provider)
        try { db.execSQL("ALTER TABLE `agents` ADD COLUMN `primaryProviderId` TEXT"); } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE `agents` ADD COLUMN `fallbackProviderId` TEXT"); } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE `agents` ADD COLUMN `fallbackModel` TEXT"); } catch (_: Exception) {}
        // Approvals: binding + audit columns
        try { db.execSQL("ALTER TABLE `approvals` ADD COLUMN `argumentsJson` TEXT NOT NULL DEFAULT '{}'"); } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE `approvals` ADD COLUMN `decisionAt` TEXT"); } catch (_: Exception) {}
        try { db.execSQL("CREATE INDEX `index_approvals_runId` ON `approvals` (`runId`)"); } catch (_: Exception) {}
        try { db.execSQL("CREATE INDEX `index_approvals_status` ON `approvals` (`status`)"); } catch (_: Exception) {}
    }
}
