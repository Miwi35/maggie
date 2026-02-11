package com.maggie.app.data.repository

import com.maggie.app.data.api.ChatResponse
import com.maggie.app.data.api.MaggieApiService

class ChatRepository(private val apiService: MaggieApiService) {
    suspend fun sendMessage(message: String): Result<ChatResponse> = runCatching {
        apiService.sendChat(message)
    }
}
