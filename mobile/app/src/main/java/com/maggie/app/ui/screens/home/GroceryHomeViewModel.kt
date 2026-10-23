package com.maggie.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.GroceryHomeState
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.repository.HomeCardRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class GroceryHomeUiState(
    val state: GroceryHomeState? = null,
    val isLoading: Boolean = true,
    /** The last read failed; [state], when there is one, is the previous answer. */
    val failed: Boolean = false,
)

/**
 * The Courses card (MAG-563): the open list by shop, read from `/api/home/grocery`. The list does not
 * depend on the day, so the card is read once and again each time the API says it moved (`home/grocery`).
 */
class GroceryHomeViewModel(private val repository: HomeCardRepository) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(GroceryHomeUiState())
    val uiState: StateFlow<GroceryHomeUiState> = _uiState

    init {
        refresh()
        viewModelScope.launch {
            repository.changes(MODULE).collect { read() }
        }
    }

    /** « Réessayer »: the card shows its loading state again while it reads. */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch { read() }
    }

    private suspend fun read() {
        repository.state(MODULE, HomeDay.TODAY)
            .mapCatching { json.decodeFromJsonElement(GroceryHomeState.serializer(), it) }
            .onSuccess { state -> _uiState.value = GroceryHomeUiState(state = state, isLoading = false) }
            .onFailure { _uiState.update { it.copy(isLoading = false, failed = true) } }
    }

    private companion object {
        const val MODULE = "grocery"
    }
}
