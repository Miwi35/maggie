package com.maggie.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.NutritionHomeState
import com.maggie.app.data.repository.HomeCardRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class NutritionHomeUiState(
    val state: NutritionHomeState? = null,
    val isLoading: Boolean = true,
    /** The last read failed; [state], when there is one, is the previous answer. */
    val failed: Boolean = false,
) {
    val status: HomeCardStatus
        get() = homeCardStatus(state, loading = isLoading, failed = failed && !isLoading) { it.isEmpty }
}

/**
 * The Nutrition card (MAG-564): the meals of the selected day, read from `/api/home/nutrition`, again when
 * the day changes and each time the API says it moved (`home/nutrition`).
 */
class NutritionHomeViewModel(private val repository: HomeCardRepository) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(NutritionHomeUiState())
    val uiState: StateFlow<NutritionHomeUiState> = _uiState

    private var day = HomeDay.TODAY
    private var reading: Job? = null

    init {
        read()
        viewModelScope.launch {
            repository.changes(MODULE).collect { read() }
        }
    }

    /** The selector moved: the other day's meals are not shown meanwhile. */
    fun selectDay(next: HomeDay) {
        if (next == day) return
        day = next
        _uiState.value = NutritionHomeUiState()
        read()
    }

    /** « Réessayer »: the card shows its loading state again while it reads. */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        read()
    }

    // A newer read replaces the one in flight, so a late answer for the previous day never lands.
    private fun read() {
        reading?.cancel()
        val asked = day
        reading = viewModelScope.launch {
            val result = repository.state(MODULE, asked)
            currentCoroutineContext().ensureActive()
            result
                .mapCatching { json.decodeFromJsonElement(NutritionHomeState.serializer(), it) }
                .onSuccess { state -> _uiState.value = NutritionHomeUiState(state = state, isLoading = false) }
                .onFailure { _uiState.update { it.copy(isLoading = false, failed = true) } }
        }
    }

    private companion object {
        const val MODULE = "nutrition"
    }
}
