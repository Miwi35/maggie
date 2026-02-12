package com.maggie.app.data.repository

import com.maggie.app.data.api.ChatResponse
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.local.dao.ChatMessageDao
import com.maggie.app.data.local.entity.ChatMessageEntity
import com.maggie.app.data.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepository(
    private val apiService: MaggieApiService,
    private val chatMessageDao: ChatMessageDao,
) {
    /** Reactive stream of all persisted chat messages. */
    fun observeMessages(): Flow<List<ChatMessage>> =
        chatMessageDao.observeAll().map { entities ->
            entities.map { it.toModel() }
        }

    /** Persist a message locally and return it with the assigned ID. */
    suspend fun saveMessage(message: ChatMessage): ChatMessage {
        val id = chatMessageDao.insert(ChatMessageEntity.fromModel(message))
        return message.copy(id = id)
    }

    /** Send message to API. Does NOT persist — caller is responsible. */
    suspend fun sendMessage(message: String): Result<ChatResponse> = runCatching {
        apiService.sendChat(message)
    }

    /** Clear all chat history. */
    suspend fun clearHistory() {
        chatMessageDao.deleteAll()
    }
}
