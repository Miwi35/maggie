package com.maggie.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.GroceryHomeState
import com.maggie.app.data.repository.GroceryCardRepository
import com.maggie.app.util.DateRanges
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class GroceryHomeUiState(
    val state: GroceryHomeState? = null,
    val isLoading: Boolean = true,
    /** The last read failed; [state], when there is one, is the previous answer. */
    val failed: Boolean = false,
) {
    val status: HomeCardStatus
        get() = homeCardStatus(state, loading = isLoading, failed = failed && !isLoading) { it.isEmpty }
}

/**
 * The Courses card (MAG-563): the open list by shop and what the meals lack, read from the grocery resources (MAG-590)
 * and again each time Mercure says one of them moved. It does not depend on the day.
 */
class GroceryHomeViewModel(
    private val repository: GroceryCardRepository,
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(GroceryHomeUiState())
    val uiState: StateFlow<GroceryHomeUiState> = _uiState

    private var load: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            repository.changes().collect { read(silently = true) }
        }
    }

    /** « Réessayer »: the card shows its loading state again while it reads. */
    fun refresh() = read(silently = false)

    private fun read(silently: Boolean) {
        load?.cancel()
        if (!silently) _uiState.update { it.copy(isLoading = true) }
        load = viewModelScope.launch {
            val today = clock().atZone(DateRanges.zone).toLocalDate()
            repository.read(today).fold(
                onSuccess = { state -> _uiState.value = GroceryHomeUiState(state = state, isLoading = false) },
                // A refresh that fails keeps what the card shows: the next one will do.
                onFailure = { _uiState.update { current -> if (silently && current.state != null) current else current.copy(isLoading = false, failed = true) } },
            )
        }
    }
}
