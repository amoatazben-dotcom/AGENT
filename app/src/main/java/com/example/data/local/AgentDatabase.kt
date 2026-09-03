package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Main Room database for AgentForge — local-first single source of truth.
 * v3 adds: providers, models, runs, events, tools, automations, memories,
 * files, computer sessions, connection profiles, settings.
 * Uses MIGRATION_2_3 (no destructive migration in production).
 */
@Database(
    entities = [
        AgentEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        ApprovalEntity::class,
        ExecutionLogEntity::class,
        AgentStateEntity::class,
        AIProviderEntity::class,
        ProviderModelEntity::class,
        RunEntity::class,
        RunEventEntity::class,
        ToolCallEntity::class,
        AutomationEntity::class,
        AutomationRunEntity::class,
        MemoryEntity::class,
        FileEntity::class,
        ComputerSessionEntity::class,
        ConnectionProfileEntity::class,
        AppSettingEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun approvalDao(): ApprovalDao
    abstract fun executionLogDao(): ExecutionLogDao
    abstract fun agentStateDao(): AgentStateDao
    abstract fun providerDao(): ProviderDao
    abstract fun providerModelDao(): ProviderModelDao
    abstract fun runDao(): RunDao
    abstract fun runEventDao(): RunEventDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun automationDao(): AutomationDao
    abstract fun automationRunDao(): AutomationRunDao
    abstract fun memoryDao(): MemoryDao
    abstract fun fileDao(): FileDao
    abstract fun computerSessionDao(): ComputerSessionDao
    abstract fun connectionProfileDao(): ConnectionProfileDao
    abstract fun appSettingDao(): AppSettingDao

    companion object {
        const val DATABASE_NAME = "agentforge_local_db"

        @Volatile
        private var INSTANCE: AgentDatabase? = null

        fun getDatabase(context: Context): AgentDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AgentDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Typealias for backwards compatibility across existing repositories and callers.
 */
typealias AppDatabase = AgentDatabase
