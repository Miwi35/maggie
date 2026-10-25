package com.maggie.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.FinanceHomeState
import com.maggie.app.data.repository.FinanceCardRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FinanceHomeUiState(
    val state: FinanceHomeState? = null,
    val isLoading: Boolean = true,
    /** The last read failed; [state], when there is one, is the previous answer. */
    val failed: Boolean = false,
) {
    val status: HomeCardStatus
        get() = homeCardStatus(state, loading = isLoading, failed = failed && !isLoading) { it.isEmpty }
}

/**
 * The Finance card (MAG-565): the week and month rings, the lines to qualify and the next project, read
 * from the Finance dashboard (MAG-592). Finance does not depend on the day, so the card is read once and
 * again each time Mercure says a resource behind it moved.
 */
class FinanceHomeViewModel(private val repository: FinanceCardRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(FinanceHomeUiState())
    val uiState: StateFlow<FinanceHomeUiState> = _uiState

    init {
        refresh()
        viewModelScope.launch {
            repository.changes().collect { read() }
        }
    }

    /** « Réessayer »: the card shows its loading state again while it reads. */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch { read() }
    }

    private suspend fun read() {
        repository.read()
            .onSuccess { state -> _uiState.value = FinanceHomeUiState(state = state, isLoading = false) }
            .onFailure { _uiState.update { it.copy(isLoading = false, failed = true) } }
    }
}
