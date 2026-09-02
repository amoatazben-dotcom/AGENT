package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Main Room database for AgentForge, storing local agents, conversations,
 * chat messages, approval requests, execution logs, and live agent execution states.
 */
@Database(
    entities = [
        AgentEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        ApprovalEntity::class,
        ExecutionLogEntity::class,
        AgentStateEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun approvalDao(): ApprovalDao
    abstract fun executionLogDao(): ExecutionLogDao
    abstract fun agentStateDao(): AgentStateDao

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
                    .fallbackToDestructiveMigration()
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
