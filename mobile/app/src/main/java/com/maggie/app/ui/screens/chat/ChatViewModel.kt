package com.maggie.app.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
)

class ChatViewModel(
    private val repository: ChatRepository,
    private val mercureService: MercureService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    init {
        subscribeToChatUpdates()
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val userMessage = ChatMessage(role = "user", content = text)
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + userMessage,
            isLoading = true,
        )

        viewModelScope.launch {
            repository.sendMessage(text)
                .onSuccess { response ->
                    val assistantMessage = ChatMessage(role = "assistant", content = response.response)
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + assistantMessage,
                        isLoading = false,
                    )
                }
                .onFailure {
                    val errorMessage = ChatMessage(role = "assistant", content = "Erreur : impossible de joindre Maggie.")
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + errorMessage,
                        isLoading = false,
                    )
                }
        }
    }

    private fun subscribeToChatUpdates() {
        viewModelScope.launch {
            mercureService.subscribe("/agent/chat/default")
                .catch { /* SSE connection errors — silently retry on next app resume */ }
                .collect { event ->
                    // Only add messages from Mercure when not loading (avoids duplicating HTTP response)
                    if (!_uiState.value.isLoading) {
                        try {
                            val json = Json.parseToJsonElement(event.data).jsonObject
                            val response = json["response"]?.jsonPrimitive?.content ?: return@collect
                            val assistantMessage = ChatMessage(role = "assistant", content = response)
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages + assistantMessage,
                            )
                        } catch (_: Exception) {
                            // Ignore parse errors
                        }
                    }
                }
        }
    }
}
