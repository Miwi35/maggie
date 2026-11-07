package com.maggie.app.ui.screens.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.BodyMeasurement
import com.maggie.app.data.model.DailyBalance
import com.maggie.app.data.model.DailyEnergy
import com.maggie.app.data.repository.BodyMeasurementRepository
import com.maggie.app.data.repository.DailyEnergyRepository
import com.maggie.app.data.repository.NutritionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class SuiviUiState(
    /** The weighings of the last year and the six days before it, newest first, excluded ones included. */
    val measurements: List<BodyMeasurement> = emptyList(),
    val isLoading: Boolean = false,
    /** False until the first answer: the screen shows a spinner, not an empty card. */
    val hasLoaded: Boolean = false,
    val period: SuiviPeriod = SuiviPeriod.DEFAULT,
    /** The column tapped; `null` is the last one, today. */
    val selectedIndex: Int? = null,
    /** The days of the last year, oldest first, as the API sends them: the Pas and Énergie cards (MAG-746). */
    val energy: List<DailyEnergy> = emptyList(),
    /** True once the energy answered; false with [energyFailed] unset means it is still being read. */
    val energyLoaded: Boolean = false,
    /** The energy could not be read: its cards say so, the Poids card is not affected. */
    val energyFailed: Boolean = false,
    /** The day tapped on the Pas or Énergie cards, or on a day column of Poids; `null` is today. */
    val selectedDate: LocalDate? = null,
    /** Today's « Bilan du jour » (MAG-748), the card Suivi opens on; `null` until read, or when it could not be. */
    val balance: DailyBalance? = null,
    val error: String? = null,
    val message: String? = null,
)

/**
 * « Santé › Suivi » (MAG-706): today's « Bilan du jour » on top (MAG-748), the weighings behind the Poids card, the period it shows, the column chosen, and
 * « Écarter ». Both lists — the weighings and, for the Pas and Énergie cards (MAG-746), the daily energy — are read
 * once for the longest period: changing the period asks nothing of the API. The [clock]
 * says today and the time zone the days are read in.
 */
class SuiviViewModel(
    private val repository: BodyMeasurementRepository,
    private val energyRepository: DailyEnergyRepository,
    private val nutritionRepository: NutritionRepository,
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
        // « Mangé » in Nutrition, or a dish Maggie records, moves the bilan card on the way back to Suivi.
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.FOOD_INTAKES))
                .catch { /* MercureService reconnects by itself */ }
                .collect { loadBalance() }
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
        loadEnergy()
        loadBalance()
    }

    /** The bilan card is extra: a failure leaves it out and says nothing, the weighings are the screen's. */
    private suspend fun loadBalance() {
        nutritionRepository.getDailyBalance(today.toString())
            .onSuccess { _uiState.value = _uiState.value.copy(balance = it) }
    }

    private suspend fun loadEnergy() {
        val to = today
        energyRepository.getDailyEnergy(to.minusDays(ENERGY_HISTORY_DAYS - 1L).toString(), to.toString())
            .onSuccess { _uiState.value = _uiState.value.copy(energy = it, energyLoaded = true, energyFailed = false) }
            .onFailure { _uiState.value = _uiState.value.copy(energyFailed = true) }
    }

    /** A new period shows its last column, today. */
    fun selectPeriod(period: SuiviPeriod) {
        if (period == _uiState.value.period) return
        _uiState.value = _uiState.value.copy(period = period, selectedIndex = null, selectedDate = null)
    }

    /** On a one-day column the day follows: the Pas and Énergie cards show it too. */
    fun selectColumn(index: Int) {
        val period = _uiState.value.period
        val date = if (period.perDay && index in 0 until period.dayCount) energyStart(period, today).plusDays(index.toLong()) else null
        _uiState.value = _uiState.value.copy(selectedIndex = index, selectedDate = date)
    }

    /** A bar of the Pas or Énergie cards: the Poids card follows when it has a column for that day. */
    fun selectDay(date: LocalDate) {
        val period = _uiState.value.period
        val offset = ChronoUnit.DAYS.between(energyStart(period, today), date).toInt()
        val column = if (period.perDay && offset in 0 until period.dayCount) offset else null
        _uiState.value = _uiState.value.copy(selectedDate = date, selectedIndex = column)
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
