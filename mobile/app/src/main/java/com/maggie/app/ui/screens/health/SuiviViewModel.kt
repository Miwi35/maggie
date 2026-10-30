package com.maggie.app.ui.screens.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.BodyMeasurement
import com.maggie.app.data.repository.BodyMeasurementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

data class SuiviUiState(
    /** The weighings of the last year and the six days before it, newest first, excluded ones included. */
    val measurements: List<BodyMeasurement> = emptyList(),
    val isLoading: Boolean = false,
    /** False until the first answer: the screen shows a spinner, not an empty card. */
    val hasLoaded: Boolean = false,
    val period: SuiviPeriod = SuiviPeriod.DEFAULT,
    /** The column tapped; `null` is the last one, today. */
    val selectedIndex: Int? = null,
    val error: String? = null,
    val message: String? = null,
)

/**
 * « Santé › Suivi » (MAG-706): the weighings behind the Poids card, the period it shows, the column chosen, and
 * « Écarter ». The list is read once for the longest period: changing the period asks nothing of the API. The [clock]
 * says today and the time zone the days are read in.
 */
class SuiviViewModel(
    private val repository: BodyMeasurementRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(SuiviUiState(isLoading = true))
    val uiState: StateFlow<SuiviUiState> = _uiState

    val zone get() = clock.zone

    val today: LocalDate get() = LocalDate.now(clock)

    init {
        refresh()
        subscribeToMercure()
    }

    // A weighing Maggie records, or one set aside in the admin, reaches the card without a reload.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.BODY_MEASUREMENTS))
                .catch { /* MercureService reconnects by itself */ }
                .coalescedAndRechecked()
                .collect { load(showLoading = false) }
        }
    }

    fun refresh() {
        viewModelScope.launch { load(showLoading = true) }
    }

    private suspend fun load(showLoading: Boolean) {
        if (showLoading) _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        repository.getBodyMeasurements(historyStartParameter(today, zone))
            .onSuccess { fresh ->
                _uiState.value = _uiState.value.copy(measurements = fresh, isLoading = false, hasLoaded = true)
            }
            .onFailure { _uiState.value = _uiState.value.copy(isLoading = false, error = MESSAGE_LOAD) }
    }

    /** A new period shows its last column, today. */
    fun selectPeriod(period: SuiviPeriod) {
        if (period == _uiState.value.period) return
        _uiState.value = _uiState.value.copy(period = period, selectedIndex = null)
    }

    fun selectColumn(index: Int) {
        _uiState.value = _uiState.value.copy(selectedIndex = index)
    }

    /** « Écarter »: the weighing is dimmed at once and leaves the trend; it comes back with the reason if the API refuses. */
    fun exclude(id: String) {
        val before = _uiState.value
        if (before.measurements.none { it.id == id && !it.excluded }) return
        _uiState.value = before.copy(
            measurements = before.measurements.map { if (it.id == id) it.copy(excluded = true) else it },
            error = null,
        )
        viewModelScope.launch {
            repository.setExcluded(id, true)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(message = MESSAGE_EXCLUDED)
                    load(showLoading = false)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        measurements = _uiState.value.measurements.map { m ->
                            if (m.id == id) m.copy(excluded = false) else m
                        },
                        error = MESSAGE_EXCLUDE,
                    )
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    companion object {
        const val MESSAGE_LOAD = "Les pesées n'ont pas pu être chargées."
        const val MESSAGE_EXCLUDE = "La pesée n'a pas été écartée."
        const val MESSAGE_EXCLUDED = "Pesée écartée"
    }
}
