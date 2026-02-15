package com.maggie.app.data.repository

import android.util.Log
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

    /** Sync messages from the server (incremental: only fetch after latest local). */
    suspend fun syncMessages() {
        try {
            val latestTimestamp = chatMessageDao.getLatestTimestamp()
            val messages = apiService.getMessages(afterDate = latestTimestamp)
            if (messages.isNotEmpty()) {
                chatMessageDao.upsertAll(messages.map { ChatMessageEntity.fromModel(it) })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync messages: ${e.message}")
        }
    }

    /** Send a user message to the agent. Returns the persisted messages (user + assistant). */
    suspend fun sendMessage(content: String): List<ChatMessage> {
        val response = apiService.sendChat(message = content)
        if (response.messages.isNotEmpty()) {
            chatMessageDao.upsertAll(response.messages.map { ChatMessageEntity.fromModel(it) })
        }
        return response.messages
    }

    /** Upsert a message received from Mercure into local storage. */
    suspend fun handleMercureMessage(message: ChatMessage) {
        chatMessageDao.upsert(ChatMessageEntity.fromModel(message))
    }

    /** Clear all chat history. */
    suspend fun clearHistory() {
        chatMessageDao.deleteAll()
    }

    companion object {
        private const val TAG = "ChatRepository"
    }
}
