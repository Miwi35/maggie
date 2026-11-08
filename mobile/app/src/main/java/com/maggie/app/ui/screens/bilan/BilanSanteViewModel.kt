package com.maggie.app.ui.screens.bilan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.HealthDay
import com.maggie.app.data.repository.HealthDayRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

data class BilanSanteUiState(
    /** The day's journal entry — figures and the two scales — or null while unread or unreadable. */
    val entry: HealthDay? = null,
    val error: String? = null,
)

/**
 * The Santé step of the Bilan (MAG-754): the day's figures from the health journal, and « Moral » / « Fatigue » on
 * ten pastilles, each written to the journal at the tap and then to the bilan's `answers`. A value off 1–10 is never sent. Maggie writing the same entry by voice reaches the
 * step through Mercure.
 */
class BilanSanteViewModel(
    private val repository: HealthDayRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BilanSanteUiState())
    val uiState: StateFlow<BilanSanteUiState> = _uiState

    private val writes = Mutex()
    private var day: LocalDate? = null

    init {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.HEALTH_DAYS))
                .catch { /* SSE reconnects automatically */ }
                .coalesced()
                .collect { day?.let(::load) }
        }
    }

    /** Reads [day]'s entry once per day of the session; the screen calls it as soon as it knows the day. */
    fun open(day: LocalDate) {
        if (this.day == day) return
        this.day = day
        _uiState.value = BilanSanteUiState()
        load(day)
    }

    private fun load(day: LocalDate) {
        viewModelScope.launch {
            repository.getDay(day.toString())
                .onSuccess { fresh -> _uiState.update { it.copy(entry = fresh) } }
        }
    }

    fun pickMood(value: Int, onRecorded: (Int) -> Unit) = pick(value, onRecorded, HealthDay::mood) { date, v -> repository.setMood(date, v) }

    fun pickFatigue(value: Int, onRecorded: (Int) -> Unit) = pick(value, onRecorded, HealthDay::fatigue) { date, v -> repository.setFatigue(date, v) }

    private fun pick(
        value: Int,
        onRecorded: (Int) -> Unit,
        current: (HealthDay) -> Int?,
        write: suspend (String, Int) -> Result<HealthDay>,
    ) {
        val date = day?.toString() ?: return
        if (value !in HealthDay.SCALE) return
        if (_uiState.value.entry?.let(current) == value) return
        viewModelScope.launch {
            writes.withLock {
                write(date, value)
                    .onSuccess { saved ->
                        _uiState.update { it.copy(entry = saved, error = null) }
                        onRecorded(value)
                    }
                    .onFailure { _uiState.update { it.copy(error = MESSAGE_WRITE) } }
            }
        }
    }

    companion object {
        const val MESSAGE_WRITE = "Impossible d’enregistrer pour l’instant. Réessayez."
    }
}
