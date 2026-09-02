package com.example.data.network

import com.example.data.model.AgentModel
import com.example.data.model.ApprovalModel
import com.example.data.model.AutomationModel
import com.example.data.model.ComputerSessionModel
import com.example.data.model.ConversationModel
import com.example.data.model.MessageModel
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface AgentForgeApi {
    @GET("/health")
    suspend fun getHealth(): Response<Map<String, Any>>

    // Agents
    @GET("/api/v1/agents")
    suspend fun getAgents(): Response<List<AgentModel>>

    @POST("/api/v1/agents")
    suspend fun createAgent(@Body agent: AgentModel): Response<AgentModel>

    @PUT("/api/v1/agents/{id}")
    suspend fun updateAgent(@Path("id") id: String, @Body agent: AgentModel): Response<AgentModel>

    @DELETE("/api/v1/agents/{id}")
    suspend fun deleteAgent(@Path("id") id: String): Response<Map<String, Boolean>>

    // Conversations
    @GET("/api/v1/conversations")
    suspend fun getConversations(): Response<List<ConversationModel>>

    @POST("/api/v1/conversations")
    suspend fun createConversation(@Body body: Map<String, String>): Response<ConversationModel>

    @GET("/api/v1/conversations/{id}/messages")
    suspend fun getMessages(@Path("id") conversationId: String): Response<List<MessageModel>>

    // Send Message
    @POST("/api/v1/messages")
    suspend fun sendMessage(@Body body: Map<String, String>): Response<Map<String, Any>>

    // Approvals
    @GET("/api/v1/approvals")
    suspend fun getApprovals(): Response<List<ApprovalModel>>

    @POST("/api/v1/approvals/{id}/resolve")
    suspend fun resolveApproval(
        @Path("id") id: String,
        @Body body: Map<String, String>
    ): Response<Map<String, Any>>

    // Automations
    @GET("/api/v1/automations")
    suspend fun getAutomations(): Response<List<AutomationModel>>

    @POST("/api/v1/automations")
    suspend fun createAutomation(@Body automation: AutomationModel): Response<AutomationModel>

    @POST("/api/v1/automations/{id}/run")
    suspend fun runAutomation(@Path("id") id: String): Response<Map<String, Any>>

    // Computers
    @GET("/api/v1/computers")
    suspend fun getComputers(): Response<List<ComputerSessionModel>>

    @POST("/api/v1/computers/{id}/action")
    suspend fun sendComputerAction(
        @Path("id") id: String,
        @Body action: Map<String, Any?>
    ): Response<Map<String, Any>>
}
