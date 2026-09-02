package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.local.AgentDao
import com.example.data.local.AgentDatabase
import com.example.data.local.AgentStateDao
import com.example.data.local.ApprovalDao
import com.example.data.local.ConversationDao
import com.example.data.local.ExecutionLogDao
import com.example.data.local.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module to provide the [AgentDatabase] instance and its associated DAOs
 * to be injected into ViewModels, repositories, and worker services.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAgentDatabase(
        @ApplicationContext context: Context
    ): AgentDatabase {
        return Room.databaseBuilder(
            context,
            AgentDatabase::class.java,
            AgentDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideAgentDao(database: AgentDatabase): AgentDao {
        return database.agentDao()
    }

    @Provides
    fun provideConversationDao(database: AgentDatabase): ConversationDao {
        return database.conversationDao()
    }

    @Provides
    fun provideMessageDao(database: AgentDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    fun provideApprovalDao(database: AgentDatabase): ApprovalDao {
        return database.approvalDao()
    }

    @Provides
    fun provideExecutionLogDao(database: AgentDatabase): ExecutionLogDao {
        return database.executionLogDao()
    }

    @Provides
    fun provideAgentStateDao(database: AgentDatabase): AgentStateDao {
        return database.agentStateDao()
    }
}
