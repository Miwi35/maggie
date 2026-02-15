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

    private val json = Json { ignoreUnknownKeys = true }

    init {
        observePersistedMessages()
        syncFromServer()
        subscribeToChatUpdates()
    }

    private fun observePersistedMessages() {
        viewModelScope.launch {
            repository.observeMessages().collect { messages ->
                _uiState.value = _uiState.value.copy(messages = messages)
            }
        }
    }

    private fun syncFromServer() {
        viewModelScope.launch {
            repository.syncMessages()
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                repository.sendMessage(text)
            } catch (_: Exception) {
                // Network errors handled silently
            }

            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    private fun subscribeToChatUpdates() {
        viewModelScope.launch {
            // Subscribe to user-specific chat topic
            mercureService.subscribe("/chat/{userId}")
                .catch { /* SSE connection errors — silently retry on next app resume */ }
                .collect { event ->
                    try {
                        val message = json.decodeFromString<ChatMessage>(event.data)
                        repository.handleMercureMessage(message)
                        // Clear loading when we receive an assistant/system message
                        if (message.role != "user") {
                            _uiState.value = _uiState.value.copy(isLoading = false)
                        }
                    } catch (_: Exception) {
                        // Ignore parse errors
                    }
                }
        }
    }
}
