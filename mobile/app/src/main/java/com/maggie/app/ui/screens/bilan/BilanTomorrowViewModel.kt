package com.maggie.app.ui.screens.bilan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.repository.AgendaCardRepository
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.TripRepository
import com.maggie.app.data.repository.WorkoutRepository
import com.maggie.app.util.DateRanges
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class BilanTomorrowState(
    val loading: Boolean = true,
    val plan: TomorrowPlan? = null,
    /** An answer is on its way to Maggie: the choices wait. */
    val sending: Boolean = false,
    val failed: Boolean = false,
)

/**
 * The Demain step (MAG-755): tomorrow's programme and the trip questions. An answer goes to Maggie as a message — she
 * records it with `set_trip` (`decidedBy: user`), the only writer of trips — then the plan is read again, so the next
 * question comes from what she really kept. [onRecorded] hands the answered key to the bilan's `answers.trips`.
 */
class BilanTomorrowViewModel(
    private val agendaCardRepository: AgendaCardRepository,
    private val tripRepository: TripRepository,
    private val mealRepository: MealRepository,
    private val workoutRepository: WorkoutRepository,
    private val chatRepository: ChatRepository,
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(BilanTomorrowState())
    val uiState: StateFlow<BilanTomorrowState> = _uiState

    init {
        load()
    }

    fun load() {
        viewModelScope.launch { _uiState.update { it.copy(loading = it.plan == null, failed = false, plan = read() ?: it.plan) } }
    }

    private suspend fun read(): TomorrowPlan? {
        val today = clock().atZone(DateRanges.zone).toLocalDate()
        val tomorrow = today.plusDays(1)
        val agenda = agendaCardRepository.read(HomeDay.TOMORROW, today).getOrNull()
        if (agenda == null) {
            _uiState.update { it.copy(failed = true) }
            return null
        }
        val trips = tripRepository.getTrips(tomorrow).getOrDefault(emptyList())
        val meals = mealRepository.getMeals(tomorrow.toString(), tomorrow.toString()).getOrDefault(emptyList())
        val workouts = workoutRepository.getWorkouts().getOrDefault(emptyList())
        return TomorrowPlan.of(tomorrow, agenda.events, trips, meals, workouts)
    }

    /** Sends [choice] to Maggie; once she answered, reads tomorrow again and reports [question] as answered. */
    fun answer(question: TripQuestion, choice: TripChoice, onRecorded: (String) -> Unit) {
        val message = choice.message ?: return
        if (_uiState.value.sending) return
        _uiState.update { it.copy(sending = true) }
        viewModelScope.launch {
            val sent = runCatching { chatRepository.sendMessage(message) }.rethrowCancellation().isSuccess
            val plan = if (sent) read() else null
            if (sent) onRecorded(question.key)
            _uiState.update { it.copy(sending = false, failed = !sent, plan = plan ?: it.plan) }
        }
    }
}
