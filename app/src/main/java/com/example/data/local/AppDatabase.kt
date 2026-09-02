package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents")
    fun getAllAgents(): Flow<List<AgentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgents(agents: List<AgentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgent(agent: AgentEntity)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun deleteAgent(id: String)

    @Query("DELETE FROM agents")
    suspend fun deleteAllAgents()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY createdAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conv: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt ASC")
    fun getMessagesForConversation(convId: String): Flow<List<MessageEntity>>

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
