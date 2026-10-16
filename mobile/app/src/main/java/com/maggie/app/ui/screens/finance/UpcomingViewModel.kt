package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.UpcomingProjection
import com.maggie.app.data.repository.UpcomingRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

data class UpcomingUiState(
    val projection: UpcomingProjection? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

/** « À venir » (MAG-463): the week and the month after, read again when an envelope or a movement changes. */
class UpcomingViewModel(
    private val repository: UpcomingRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UpcomingUiState())
    val uiState: StateFlow<UpcomingUiState> = _uiState

    private var refreshJob: Job? = null

    init {
        refresh()
        subscribeToMercure()
    }

    // Adjusting an envelope moves what is left toward the projects; a movement moves the windows.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            merge(
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.ENVELOPES)),
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.TRANSACTIONS)),
            )
                .catch { /* SSE reconnects automatically */ }
                .coalesced()
                .collect { refresh() }
        }
    }

    /** A newer read replaces the one in flight; a failed read keeps the figures already on screen. */
    fun refresh() {
        refreshJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        refreshJob = viewModelScope.launch {
            val result = repository.getUpcoming()
            currentCoroutineContext().ensureActive()
            result
                .onSuccess { projection ->
                    _uiState.value = _uiState.value.copy(projection = projection, isLoading = false)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(error = e.message ?: READ_FAILED, isLoading = false)
                }
        }
    }

    private companion object {
        const val READ_FAILED = "Impossible de lire les prévisions"
    }
}
