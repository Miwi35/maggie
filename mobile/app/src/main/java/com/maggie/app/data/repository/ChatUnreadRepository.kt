package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.ChatUnread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json

/**
 * What the owner has read of the conversation, kept by the server so a message read on the web is
 * read on the phone too (MAG-687). Nothing is stored here.
 */
class ChatUnreadRepository(
    private val apiService: MaggieApiService,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(): Result<ChatUnread> = runCatching { apiService.getChatUnread() }

    /** Everything up to [messageId] is read; the answer is the state after it. */
    suspend fun markRead(messageId: String): Result<ChatUnread> = runCatching { apiService.markChatRead(messageId) }

    /** Every change to the read state, from any device. The topic carries the user's id, or the hub delivers nothing. */
    fun observe(): Flow<ChatUnread> = flow {
        val userId = authRepository.getUserId() ?: return@flow
        emitAll(
            mercureService.subscribe(MercureTopics.agentScoped(userId, MercureTopics.CHAT_READ))
                .mapNotNull { event -> runCatching { json.decodeFromString<ChatUnread>(event.data) }.getOrNull() },
        )
    }
}
