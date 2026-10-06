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
}
