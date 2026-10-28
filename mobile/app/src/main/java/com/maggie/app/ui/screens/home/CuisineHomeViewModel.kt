package com.maggie.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.CuisineHomeState
import com.maggie.app.data.repository.CuisineCardRepository
import com.maggie.app.util.DateRanges
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class CuisineHomeUiState(
    val state: CuisineHomeState? = null,
    val isLoading: Boolean = true,
    /** The last read failed; [state], when there is one, is the previous answer. */
    val failed: Boolean = false,
) {
    val status: HomeCardStatus
        get() = homeCardStatus(state, loading = isLoading, failed = failed && !isLoading) { it.isEmpty }
}

/**
 * The Cuisine card (MAG-564): the meals of the selected day, read from the meals and the recipe needs (MAG-591), again when
 * the day changes and each time Mercure says one of the resources behind them moved.
 */
class CuisineHomeViewModel(
    private val repository: CuisineCardRepository,
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(CuisineHomeUiState())
    val uiState: StateFlow<CuisineHomeUiState> = _uiState

    private var day = HomeDay.TODAY
    private var reading: Job? = null

    init {
        read(silently = false)
        viewModelScope.launch {
            repository.changes().collect { read(silently = true) }
        }
    }

    /** The selector moved: the other day's meals are not shown meanwhile. */
    fun selectDay(next: HomeDay) {
        if (next == day) return
        day = next
        _uiState.value = CuisineHomeUiState()
        read(silently = false)
    }

    /** « Réessayer »: the card shows its loading state again while it reads. */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        read(silently = false)
    }

    // A newer read replaces the one in flight, so a late answer for the previous day never lands.
    private fun read(silently: Boolean) {
        reading?.cancel()
        val asked = day
        reading = viewModelScope.launch {
            val today = clock().atZone(DateRanges.zone).toLocalDate()
            val date = if (asked == HomeDay.TODAY) today else today.plusDays(1)
            repository.read(date).fold(
                onSuccess = { state -> _uiState.value = CuisineHomeUiState(state = state, isLoading = false) },
                // A refresh that fails keeps what the card shows: the next one will do.
                onFailure = { _uiState.update { current -> if (silently && current.state != null) current else current.copy(isLoading = false, failed = true) } },
            )
        }
    }
}
