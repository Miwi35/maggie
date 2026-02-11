package com.maggie.app.ui.screens.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Event
import com.maggie.app.data.repository.EventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class AgendaUiState(
    val events: List<Event> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class AgendaViewModel(
    private val repository: EventRepository,
    private val mercureService: MercureService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AgendaUiState())
    val uiState: StateFlow<AgendaUiState> = _uiState

    init {
        loadEvents()
        subscribeToEventUpdates()
    }

    fun loadEvents() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.getEvents()
                .onSuccess { events ->
                    _uiState.value = _uiState.value.copy(events = events, isLoading = false)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(error = error.message, isLoading = false)
                }
        }
    }

    private fun subscribeToEventUpdates() {
        viewModelScope.launch {
            mercureService.subscribe("/api/events/{id}")
                .catch { /* SSE connection errors — silently retry on next app resume */ }
                .collect { loadEvents() }
        }
    }
}
