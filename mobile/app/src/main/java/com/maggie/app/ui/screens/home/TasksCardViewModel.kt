package com.maggie.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.HomeTasks
import com.maggie.app.data.repository.HomeCardRepository
import com.maggie.app.data.repository.RoutineRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.util.DateRanges
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * What the « Tâches » card holds (MAG-561). [card] is the last answer of the API, moved by the ticks the
 * user made since; [error] says the first read failed (nothing to show); [message] says a tick failed over
 * a card still shown. Ticks in flight are in [pendingTasks] and [pendingRoutines]: a second tap waits.
 */
data class TasksCardUiState(
    val day: HomeDay = HomeDay.TODAY,
    val card: HomeTasks? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val pendingTasks: Set<String> = emptySet(),
    val pendingRoutines: Set<String> = emptySet(),
) {
    /** Tomorrow is read-only: its rows and its chips are not tickable. */
    val canTick: Boolean get() = card?.editable == true
}

/**
 * The « Tâches » card of the Accueil: today's (or tomorrow's) tasks and the routines of the moment, tickable
 * from the home. A tick is shown at once (the row struck, the ring moved) and then asked of the API with the
 * calls the Tâches screen makes; refused, it is taken back and said in words. The card is read again when
 * the API announces a change on `home/tasks`, and after each tick.
 */
class TasksCardViewModel(
    private val homeCardRepository: HomeCardRepository,
    private val taskRepository: TaskRepository,
    private val routineRepository: RoutineRepository,
    private val today: () -> LocalDate = { DateRanges.todayDate() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(TasksCardUiState())
    val uiState: StateFlow<TasksCardUiState> = _uiState

    private var loadJob: Job? = null
    private var listenJob: Job? = null

    /** The card came on for [day]: read, and listened to while it is on. */
    fun show(day: HomeDay) {
        val sameDay = _uiState.value.day == day
        _uiState.update {
            if (sameDay) it else it.copy(day = day, card = null, error = null, message = null)
        }
        listen()
        load(silent = sameDay && _uiState.value.card != null, force = true)
    }

    /** The card left the screen: the pushes are not listened to any more. */
    fun onClosed() {
        listenJob?.cancel()
        listenJob = null
    }

    /** « Réessayer ». */
    fun retry() = load(silent = false, force = true)

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /** Ticks the task [id] if it is open, unticks it if it is done. */
    fun toggleTask(id: String) {
        val state = _uiState.value
        val card = state.card ?: return
        if (!card.editable || id in state.pendingTasks) return
        val task = card.tasks.firstOrNull { it.id == id } ?: return
        val done = !task.done
        loadJob?.cancel() // an answer older than the tick must not undo it
        _uiState.update {
            it.copy(
                card = it.card?.withTaskDone(id, done),
                pendingTasks = it.pendingTasks + id,
                message = null,
            )
        }
        viewModelScope.launch {
            taskRepository.toggleDone(id, done)
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _uiState.update {
                        it.copy(card = it.card?.withTaskDone(id, !done), message = TASK_FAILED)
                    }
                }
            _uiState.update { it.copy(pendingTasks = it.pendingTasks - id) }
            readAgain()
        }
    }

    /** Ticks the routine [id] for today's date if it is open, unticks it if it is checked. */
    fun toggleRoutine(id: String) {
        val state = _uiState.value
        val card = state.card ?: return
        if (!card.editable || id in state.pendingRoutines) return
        val routine = card.routines.firstOrNull { it.id == id } ?: return
        val checked = !routine.checked
        loadJob?.cancel()
        _uiState.update {
            it.copy(
                card = it.card?.withRoutineChecked(id, checked),
                pendingRoutines = it.pendingRoutines + id,
                message = null,
            )
        }
        viewModelScope.launch {
            routineRepository.setChecked(id, today(), checked)
                .onFailure {
                    _uiState.update {
                        it.copy(card = it.card?.withRoutineChecked(id, !checked), message = ROUTINE_FAILED)
                    }
                }
            _uiState.update { it.copy(pendingRoutines = it.pendingRoutines - id) }
            readAgain()
        }
    }

    private fun listen() {
        if (listenJob?.isActive == true) return
        listenJob = viewModelScope.launch {
            homeCardRepository.changes(HomeModules.TASKS).collect { readAgain() }
        }
    }

    /** A read nobody asked for: it waits while a tick is in flight, whose end reads again. */
    private fun readAgain() = load(silent = true, force = false)

    /**
     * Reads the card for the day shown. [silent]: the card stays as it is meanwhile, and keeps standing if the
     * read fails. Unless [force], the read waits for the ticks in flight, so it never undoes a row the user
     * just ticked before the API has heard of it.
     */
    private fun load(silent: Boolean, force: Boolean) {
        val state = _uiState.value
        if (!force && (state.pendingTasks.isNotEmpty() || state.pendingRoutines.isNotEmpty())) return
        loadJob?.cancel()
        val day = state.day
        if (!silent) _uiState.update { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            homeCardRepository.state(HomeModules.TASKS, day)
                .mapCatching { JSON.decodeFromJsonElement(HomeTasks.serializer(), it) }
                .rethrowCancellation()
                .onSuccess { card ->
                    _uiState.update { if (it.day == day) it.copy(card = card, isLoading = false, error = null) else it }
                }
                .onFailure {
                    _uiState.update {
                        when {
                            it.day != day -> it
                            it.card != null -> it.copy(isLoading = false)
                            else -> it.copy(isLoading = false, error = READ_FAILED)
                        }
                    }
                }
        }
    }

    companion object {
        const val READ_FAILED = "Impossible de charger les tâches."
        const val TASK_FAILED = "Impossible de cocher la tâche. Réessaie."
        const val ROUTINE_FAILED = "Impossible de cocher la routine. Réessaie."

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
