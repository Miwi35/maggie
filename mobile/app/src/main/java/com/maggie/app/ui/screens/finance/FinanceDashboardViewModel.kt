package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.FinanceScores
import com.maggie.app.data.model.TreasuryBannerState
import com.maggie.app.data.model.toBanner
import com.maggie.app.data.repository.FinanceDashboardRepository
import com.maggie.app.data.repository.TreasuryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class FinanceDashboardUiState(
    val dashboard: FinanceDashboard? = null,
    val year: Int = 0,
    val month: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    // Null when no overdraft is forecast, or when the outlook could not be loaded: the dashboard goes on without it.
    val treasuryBanner: TreasuryBannerState? = null,
    // The week and month scores load apart: their failure leaves the dashboard whole (and the other way round).
    val scores: FinanceScores? = null,
    val isLoadingScores: Boolean = false,
    val scoresError: String? = null,
)

/**
 * The day the scores are asked for: today on the current month, else the month's last day (past) or first day (future).
 * Always a date inside the month shown, so the month score is that month's.
 */
fun scoresDate(today: LocalDate, year: Int, month: Int): LocalDate {
    val shown = YearMonth.of(year, month)
    val current = YearMonth.from(today)
    return when {
        shown == current -> today
        shown.isBefore(current) -> shown.atEndOfMonth()
        else -> shown.atDay(1)
    }
}

class FinanceDashboardViewModel(
    private val dashboardRepository: FinanceDashboardRepository,
    private val treasuryRepository: TreasuryRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        FinanceDashboardUiState(year = today.year, month = today.monthValue),
    )
    val uiState: StateFlow<FinanceDashboardUiState> = _uiState

    private var treasuryJob: Job? = null
    private var scoresJob: Job? = null

    init {
        refresh()
        refreshScores()
        refreshTreasury()
        subscribeToMercure()
    }

    // A transaction or an envelope that changes moves what is left to spend.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            merge(
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.TRANSACTIONS)),
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.ENVELOPES)),
            )
                .catch { /* SSE reconnects automatically */ }
                .coalesced()
                .collect { refreshScores() }
        }
    }

    fun refreshScores() {
        scoresJob?.cancel()
        scoresJob = viewModelScope.launch {
            val state = _uiState.value
            _uiState.value = state.copy(isLoadingScores = true, scoresError = null)
            val date = scoresDate(today, state.year, state.month).toString()
            val result = dashboardRepository.getScores(date)
            currentCoroutineContext().ensureActive()
            result
                .onSuccess { scores ->
                    _uiState.value = _uiState.value.copy(scores = scores, isLoadingScores = false)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        scoresError = e.message ?: "Scores indisponibles",
                        isLoadingScores = false,
                    )
                }
        }
    }

    fun refreshTreasury() {
        treasuryJob?.cancel()
        treasuryJob = viewModelScope.launch {
            val banner = treasuryRepository.getOutlook().getOrNull()?.toBanner()
            _uiState.value = _uiState.value.copy(treasuryBanner = banner)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val state = _uiState.value
            try {
                val dashboard = dashboardRepository.getDashboard(state.year, state.month).getOrThrow()
                _uiState.value = _uiState.value.copy(dashboard = dashboard, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun shiftPeriod(months: Long) {
        val state = _uiState.value
        val shifted = LocalDate.of(state.year, state.month, 1).plusMonths(months)
        // The scores on screen belong to the month left: they must not stay while the new ones load.
        _uiState.value = state.copy(year = shifted.year, month = shifted.monthValue, scores = null, scoresError = null)
        refresh()
        refreshScores()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
