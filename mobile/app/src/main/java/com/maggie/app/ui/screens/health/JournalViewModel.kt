package com.maggie.app.ui.screens.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.HealthDay
import com.maggie.app.data.repository.HealthDayRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

data class JournalUiState(
    /** The day chosen, today by default; never after today. */
    val date: LocalDate,
    /** The chosen day's entry; `null` until it answered. A day the journal does not hold has an entry with `id` null. */
    val entry: HealthDay? = null,
    /** The journal's days of the last [JOURNAL_HISTORY_DAYS], oldest first, as the API sends them. */
    val days: List<HealthDay> = emptyList(),
    val isLoading: Boolean = false,
    /** False until the first answer: the screen shows a spinner, not an empty entry. */
    val hasLoaded: Boolean = false,
    /** The note is on its way: « Enregistrer » waits, so a second tap sends nothing. */
    val savingNote: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

/**
 * « Santé › Journal » (MAG-750): one entry per day — the mood and fatigue on 1–10, a free note, the day's figures —
 * and the journal's previous days. A tap on a scale writes it at once; the note waits for « Enregistrer ». Maggie's
 * own writes, and the figures the API computes, arrive on the `health/days` topic. The [clock] says today, in Paris
 * as the API counts the days.
 */
class JournalViewModel(
    private val repository: HealthDayRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    val clock: Clock = Clock.system(ZoneId.of("Europe/Paris")),
) : ViewModel() {

    private val _uiState = MutableStateFlow(JournalUiState(date = LocalDate.now(clock), isLoading = true))
    val uiState: StateFlow<JournalUiState> = _uiState

    val zone: ZoneId get() = clock.zone

    val today: LocalDate get() = LocalDate.now(clock)

    init {
        refresh()
        subscribeToMercure()
    }

    // What Maggie notes in a conversation, a weighing or a meal moving a figure, reaches the entry without a reload.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.HEALTH_DAYS))
                .catch { /* MercureService reconnects by itself */ }
                .collect { load(showLoading = false) }
        }
    }

    fun refresh() {
        viewModelScope.launch { load(showLoading = true) }
    }

    private suspend fun load(showLoading: Boolean) {
        if (showLoading) _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        val date = _uiState.value.date
        val end = today
        val (entry, days) = coroutineScope {
            val entry = async { repository.getDay(date.toString()) }
            val days = async { repository.getDays(end.minusDays(JOURNAL_HISTORY_DAYS - 1L).toString(), end.toString()) }
            entry.await() to days.await()
        }
        var state = _uiState.value.copy(isLoading = false)
        // Another day chosen meanwhile: its own read answers for it.
        if (state.date == date) {
            entry.onSuccess { state = state.copy(entry = it, hasLoaded = true) }
            if (entry.isFailure) state = state.copy(error = MESSAGE_LOAD)
        }
        days.onSuccess { state = state.copy(days = it) }
        _uiState.value = state
    }

    /** A day of « Jours précédents » or of the arrows: what the journal already holds shows at once, then the API's. */
    fun selectDate(date: LocalDate) {
        if (date > today || date == _uiState.value.date) return
        val known = _uiState.value.days.firstOrNull { it.date == date.toString() }
        _uiState.value = _uiState.value.copy(date = date, entry = known, error = null)
        viewModelScope.launch {
            repository.getDay(date.toString())
                .onSuccess { day -> if (_uiState.value.date == date) _uiState.value = _uiState.value.copy(entry = day, hasLoaded = true) }
                .onFailure { if (_uiState.value.date == date) _uiState.value = _uiState.value.copy(error = MESSAGE_LOAD) }
        }
    }

    fun previousDay() = selectDate(_uiState.value.date.minusDays(1))

    fun nextDay() = selectDate(_uiState.value.date.plusDays(1))

    /** A tap on a pastille of « Moral »; on the value already chosen it empties the scale. */
    fun pickMood(value: Int) {
        val current = currentEntry()
        val next = value.takeUnless { current.mood == it }
        write(current, current.copy(mood = next), MESSAGE_SCALE) { repository.setMood(it, next) }
    }

    /** A tap on a pastille of « Fatigue »; on the value already chosen it empties the scale. */
    fun pickFatigue(value: Int) {
        val current = currentEntry()
        val next = value.takeUnless { current.fatigue == it }
        write(current, current.copy(fatigue = next), MESSAGE_SCALE) { repository.setFatigue(it, next) }
    }

    /** « Enregistrer »: the note trimmed, a blank one empties it. */
    fun saveNote(text: String) {
        if (_uiState.value.savingNote) return
        val note = text.trim().takeIf { it.isNotEmpty() }
        val current = currentEntry()
        _uiState.value = _uiState.value.copy(savingNote = true)
        write(current, current.copy(note = note), MESSAGE_NOTE, saved = MESSAGE_NOTE_SAVED) { repository.setNote(it, note) }
    }

    private fun currentEntry(): HealthDay = _uiState.value.entry ?: HealthDay(date = _uiState.value.date.toString())

    /** Shown at once; the API's entry replaces it, or the one before comes back with the reason it refused. */
    private fun write(
        before: HealthDay,
        optimistic: HealthDay,
        fallback: String,
        saved: String? = null,
        send: suspend (String) -> Result<HealthDay>,
    ) {
        val date = before.date
        _uiState.value = _uiState.value.copy(entry = optimistic, error = null)
        viewModelScope.launch {
            send(date)
                .onSuccess { day ->
                    val state = _uiState.value
                    _uiState.value = state.copy(
                        entry = if (state.date.toString() == day.date) day else state.entry,
                        days = withDay(state.days, day),
                        savingNote = false,
                        message = saved ?: state.message,
                    )
                }
                .onFailure { failure ->
                    val state = _uiState.value
                    _uiState.value = state.copy(
                        entry = if (state.date.toString() == date) before else state.entry,
                        savingNote = false,
                        error = refusal(failure, fallback),
                    )
                }
        }
    }

    /** [day] in its place among [days], oldest first. */
    private fun withDay(days: List<HealthDay>, day: HealthDay): List<HealthDay> =
        (days.filterNot { it.date == day.date } + day).sortedBy { it.date }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /** A 400 carries the API's reason; anything else is the screen's own sentence. */
    private fun refusal(failure: Throwable, fallback: String): String =
        (failure as? ApiRefusalException)?.takeIf { it.status == 400 }?.message?.takeIf { it.isNotBlank() } ?: fallback

    companion object {
        const val MESSAGE_LOAD = "Le journal n'a pas pu être chargé."
        const val MESSAGE_SCALE = "La valeur n'a pas été enregistrée."
        const val MESSAGE_NOTE = "La note n'a pas été enregistrée."
        const val MESSAGE_NOTE_SAVED = "Note enregistrée"
    }
}
