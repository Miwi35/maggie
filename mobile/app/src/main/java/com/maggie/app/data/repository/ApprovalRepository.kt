package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.PendingApproval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class ApprovalRepository(
    private val apiService: MaggieApiService,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getPending(): Result<List<PendingApproval>> = runCatching {
        apiService.getPendingApprovals()
    }

    suspend fun approve(id: String): Result<PendingApproval> = runCatching { apiService.approve(id) }

    suspend fun deny(id: String): Result<PendingApproval> = runCatching { apiService.deny(id) }

    /**
     * What a held action is about, in the user's words, when its arguments only carry an
     * id: the title of the event `delete_event` would remove. Null when the tool is not
     * one that needs it or the event cannot be read — the question is then asked without it.
     */
    suspend fun describe(approval: PendingApproval): String? {
        if (approval.toolName != DELETE_EVENT) return null
        val id = (approval.arguments["id"] as? JsonPrimitive)?.content ?: return null
        return runCatching { apiService.getEvent(id).summary }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Every change to one of the user's approvals, as the agent publishes it. The topic
     * carries the signed-in user's id: the hub would match `/approvals/{userId}` literally
     * and deliver nothing.
     */
    fun observe(): Flow<PendingApproval> = flow {
        val userId = authRepository.getUserId() ?: return@flow
        emitAll(
            mercureService.subscribe(MercureTopics.agentScoped(userId, MercureTopics.APPROVALS))
                .mapNotNull { event ->
                    runCatching { json.decodeFromString<PendingApproval>(event.data) }.getOrNull()
                },
        )
    }

    private companion object {
        const val DELETE_EVENT = "delete_event"
    }
}
