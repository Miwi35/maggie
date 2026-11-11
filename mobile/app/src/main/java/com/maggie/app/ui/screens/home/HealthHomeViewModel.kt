package com.maggie.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.HealthHomeState
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.repository.HealthCardRepository
import com.maggie.app.util.DateRanges
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class HealthHomeUiState(
    val state: HealthHomeState? = null,
    val isLoading: Boolean = true,
    /** The last read failed; [state], when there is one, is the previous answer. */
    val failed: Boolean = false,
) {
    val status: HomeCardStatus
        get() = homeCardStatus(state, loading = isLoading, failed = failed && !isLoading) { it.isEmpty }
}

/**
 * The Santé card (MAG-566): read for the selected day from the module's resources, again when the day changes and
 * each time Mercure says one of them moved.
 */
class HealthHomeViewModel(
    private val repository: HealthCardRepository,
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(HealthHomeUiState())
    val uiState: StateFlow<HealthHomeUiState> = _uiState

    private var day = HomeDay.TODAY
    private var reading: Job? = null

    init {
        read(silently = false)
        viewModelScope.launch {
            repository.changes().collect { read(silently = true) }
        }
    }

    /** The selector moved: the other day's figures are not shown meanwhile. */
    fun selectDay(next: HomeDay) {
        if (next == day) return
        day = next
        _uiState.value = HealthHomeUiState()
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
            val now = clock().atZone(DateRanges.zone)
            val isToday = asked == HomeDay.TODAY
            val date = if (isToday) now.toLocalDate() else now.toLocalDate().plusDays(1)
            repository.read(date, isToday, now.toLocalTime()).fold(
                onSuccess = { state -> _uiState.value = HealthHomeUiState(state = state, isLoading = false) },
                // A refresh that fails keeps what the card shows: the next one will do.
                onFailure = { _uiState.update { current -> if (silently && current.state != null) current else current.copy(isLoading = false, failed = true) } },
            )
        }
    }
}
