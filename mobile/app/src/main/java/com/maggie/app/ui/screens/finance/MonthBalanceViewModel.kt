package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.MonthBalance
import com.maggie.app.data.repository.MonthBalanceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Dashboard › État courant: the balance of [month] of [year], from one answer. */
data class MonthBalanceUiState(
    val balance: MonthBalance? = null,
    val year: Int = 0,
    val month: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class MonthBalanceViewModel(
    private val repository: MonthBalanceRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(MonthBalanceUiState(year = today.year, month = today.monthValue))
    val uiState: StateFlow<MonthBalanceUiState> = _uiState

    private var loadJob: Job? = null

    init {
        refresh()
        subscribeToMercure()
    }

    // A transaction or an envelope that changes moves the bars, the ring and the lines to qualify.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            merge(
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.TRANSACTIONS)),
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.ENVELOPES)),
            )
                .catch { /* SSE reconnects automatically */ }
                .coalesced()
                .collect { refresh() }
        }
    }

    /** Loads the month shown; an answer that comes after a newer request, or after a month change, is dropped. */
    fun refresh() {
        loadJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        val state = _uiState.value
        loadJob = viewModelScope.launch {
            val result = repository.getBalance(state.year, state.month)
            currentCoroutineContext().ensureActive()
            result
                .onSuccess { balance ->
                    _uiState.value = _uiState.value.copy(balance = balance, isLoading = false)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(error = e.message ?: "Bilan indisponible", isLoading = false)
                }
        }
    }

    fun shiftPeriod(months: Long) {
        val state = _uiState.value
        val shifted = LocalDate.of(state.year, state.month, 1).plusMonths(months)
        // The figures on screen belong to the month left: they must not stay while the new ones load.
        _uiState.value = state.copy(year = shifted.year, month = shifted.monthValue, balance = null, error = null)
        refresh()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
