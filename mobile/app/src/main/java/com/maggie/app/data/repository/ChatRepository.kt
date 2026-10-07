package com.maggie.app.data.repository

import android.util.Log
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.local.dao.ChatMessageDao
import com.maggie.app.data.local.entity.ChatMessageEntity
import com.maggie.app.data.model.AgUiEvent
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

    /**
     * Send a user message to the agent. Returns the persisted messages (user + assistant).
     *
     * [screenContext] is the screen the assistant was summoned from (MAG-30): it goes in
     * its own field, so what the agent stores and returns is the question alone.
     */
    suspend fun sendMessage(content: String, screenContext: String? = null): List<ChatMessage> {
        val response = apiService.sendChat(message = content, screenContext = screenContext)
        if (response.messages.isNotEmpty()) {
            chatMessageDao.upsertAll(response.messages.map { ChatMessageEntity.fromModel(it) })
        }
        return response.messages
    }

    /** Upsert a message received from Mercure into local storage. */
    suspend fun handleMercureMessage(message: ChatMessage) {
        chatMessageDao.upsert(ChatMessageEntity.fromModel(message))
    }

    /** Load the most recent messages (API-first, Room fallback). */
    suspend fun loadRecentMessages(limit: Int = 20): List<ChatMessage> {
        return try {
            val messages = apiService.getMessagesPaginated(limit = limit)
            if (messages.isNotEmpty()) {
                chatMessageDao.upsertAll(messages.map { ChatMessageEntity.fromModel(it) })
            }
            messages
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load recent messages from API, falling back to Room: ${e.message}")
            chatMessageDao.loadBefore("9999-12-31T23:59:59Z", limit)
                .reversed()
                .map { it.toModel() }
        }
    }

    /** Load older messages before the given message ID (API-first, Room fallback). */
    suspend fun loadOlderMessages(beforeId: String, limit: Int = 20): List<ChatMessage> {
        return try {
            val messages = apiService.getMessagesPaginated(beforeId = beforeId, limit = limit)
            if (messages.isNotEmpty()) {
                chatMessageDao.upsertAll(messages.map { ChatMessageEntity.fromModel(it) })
            }
            messages
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load older messages from API, falling back to Room: ${e.message}")
            val pivot = chatMessageDao.getById(beforeId)
            val pivotDate = pivot?.createdAt ?: return emptyList()
            chatMessageDao.loadBefore(pivotDate, limit)
                .reversed()
                .map { it.toModel() }
        }
    }

    /** Search messages (API-first, Room fallback). */
    suspend fun searchMessages(query: String, limit: Int = 20): List<ChatMessage> {
        return try {
            apiService.searchMessages(query = query, limit = limit)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to search messages from API, falling back to Room: ${e.message}")
            chatMessageDao.searchByContent(query, limit).map { it.toModel() }
        }
    }

    /** Load context around a specific message (API-first, Room fallback). */
    suspend fun loadMessageContext(messageId: String): List<ChatMessage> {
        return try {
            val response = apiService.getMessageContext(messageId = messageId)
            if (response.messages.isNotEmpty()) {
                chatMessageDao.upsertAll(response.messages.map { ChatMessageEntity.fromModel(it) })
            }
            response.messages
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load message context from API: ${e.message}")
            val entity = chatMessageDao.getById(messageId)
            listOfNotNull(entity?.toModel())
        }
    }

    /** Send a user message via AG-UI streaming. Returns a flow of AG-UI events. */
    fun sendMessageStream(content: String, screenContext: String? = null): Flow<AgUiEvent> {
        return apiService.sendChatStream(message = content, screenContext = screenContext)
    }

    /** Persist a completed assistant message to Room. */
    suspend fun persistMessage(message: ChatMessage) {
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
