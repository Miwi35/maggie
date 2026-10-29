package com.maggie.app.ui.screens.tasks

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Task
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.util.DateRanges
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class FocusUiState(
    /** The task in focus; null when no Focus is open. */
    val taskId: String? = null,
    val task: Task? = null,
    /** The « petits pas »: the task's subtasks, in Google's order. */
    val steps: List<Task> = emptyList(),
    /** The timer is an option: off when the Focus opens, whatever the task's duration. */
    val timerEnabled: Boolean = false,
    val timer: FocusTimer = FocusTimer(),
    val remainingMs: Long = FocusTimer.DEFAULT_MS,
    val phase: FocusTimerPhase = FocusTimerPhase.IDLE,
    val isLoading: Boolean = false,
    /** « C'est fait » or « Plus tard » is in flight. */
    val isBusy: Boolean = false,
    /** The task could not be read. */
    val error: String? = null,
    /** An action failed; the screen stays as it was. */
    val message: String? = null,
    /** « C'est fait » or « Plus tard » went through: the screen goes back to the day. */
    val leaves: Boolean = false,
    /** It left by « C'est fait »: the Bravo opens instead of the day (MAG-508). */
    val completed: Boolean = false,
)

/**
 * The Focus (MAG-499): one task of the day, its first step, an optional timer and its subtasks as
 * « petits pas ». It lives with the navigation graph, so a rotation keeps the timer; [open] is
 * therefore idempotent for the task already in focus, and [close] is what ends a Focus.
 */
class FocusViewModel(
    private val taskRepository: TaskRepository,
    private val today: () -> LocalDate = { DateRanges.todayDate() },
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val tickMs: Long = TICK_MS,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FocusUiState())
    val uiState: StateFlow<FocusUiState> = _uiState

    private var tickJob: Job? = null

    fun open(taskId: String) {
        if (_uiState.value.taskId == taskId) return
        stopTicking()
        _uiState.value = FocusUiState(taskId = taskId, isLoading = true)
        viewModelScope.launch {
            taskRepository.fetchTask(taskId)
                .onSuccess { task ->
                    if (_uiState.value.taskId != taskId) return@onSuccess
                    _uiState.update { it.copy(task = task, isLoading = false) }
                    loadSteps(taskId)
                }
                .onFailure {
                    if (_uiState.value.taskId != taskId) return@onFailure
                    _uiState.update { it.copy(isLoading = false, error = "Impossible d'ouvrir la tâche.") }
                }
        }
    }

    private suspend fun loadSteps(taskId: String) {
        taskRepository.getSubtasks(taskId)
            .onSuccess { steps ->
                if (_uiState.value.taskId == taskId) _uiState.update { it.copy(steps = steps) }
            }
            .onFailure {
                if (_uiState.value.taskId == taskId) _uiState.update { it.copy(message = "Impossible de lire les petits pas.") }
            }
    }

    /** The Focus ends: the timer stops and nothing is kept for the next one. */
    fun close() {
        stopTicking()
        _uiState.value = FocusUiState()
    }

    /** « Activer le minuteur »: the ring appears, set to the task's duration (10 minutes without one); a second press removes it and stops it. */
    fun toggleTimer() {
        val state = _uiState.value
        val task = state.task ?: return
        stopTicking()
        val timer = if (state.timerEnabled) FocusTimer() else FocusTimer(totalMs = durationMsOf(task))
        _uiState.update { it.copy(timerEnabled = !it.timerEnabled) }
        setTimer(timer)
    }

    private fun durationMsOf(task: Task): Long =
        task.estimatedMinutes?.takeIf { it > 0 }?.let { it * 60_000L } ?: FocusTimer.DEFAULT_MS

    fun start() {
        val state = _uiState.value
        if (state.task == null || !state.timerEnabled || state.phase != FocusTimerPhase.IDLE) return
        run(state.timer.started(clock()))
    }

    fun pause() {
        val state = _uiState.value
        if (state.phase != FocusTimerPhase.RUNNING) return
        stopTicking()
        setTimer(state.timer.paused(clock()))
    }

    fun resume() {
        val state = _uiState.value
        if (state.phase != FocusTimerPhase.PAUSED) return
        run(state.timer.started(clock()))
    }

    /** After the end of the ten minutes: ten more. */
    fun restart() {
        if (_uiState.value.phase != FocusTimerPhase.FINISHED) return
        run(FocusTimer(totalMs = _uiState.value.timer.totalMs).started(clock()))
    }

    /** The screen came back to the front: the time left is read again, whatever the ticks did meanwhile. */
    fun refreshTimer() {
        val state = _uiState.value
        if (state.taskId == null) return
        setTimer(state.timer)
        if (_uiState.value.phase == FocusTimerPhase.RUNNING && tickJob?.isActive != true) tick()
    }

    private fun run(timer: FocusTimer) {
        setTimer(timer)
        tick()
    }

    private fun tick() {
        stopTicking()
        tickJob = viewModelScope.launch {
            while (_uiState.value.phase == FocusTimerPhase.RUNNING) {
                delay(tickMs)
                setTimer(_uiState.value.timer)
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun setTimer(timer: FocusTimer) {
        val now = clock()
        _uiState.update { it.copy(timer = timer, remainingMs = timer.remainingMs(now), phase = timer.phase(now)) }
    }

    /** Ticks a petit pas, or unticks it; the task itself is left as it is. */
    fun toggleStep(stepId: String, done: Boolean) {
        viewModelScope.launch {
            taskRepository.toggleDone(stepId, done)
                .onSuccess(::replaceStep)
                .onFailure { _uiState.update { it.copy(message = "Impossible de modifier le petit pas.") } }
        }
    }

    /** « Je bloque : découpe-la »: a petit pas written by hand, put last. */
    fun addStep(title: String) {
        val taskId = _uiState.value.taskId ?: return
        if (title.isBlank()) return
        viewModelScope.launch {
            taskRepository.createSubtask(taskId, title.trim())
                .onSuccess(::replaceStep)
                .onFailure { _uiState.update { it.copy(message = "Impossible d'ajouter le petit pas.") } }
        }
    }

    private fun replaceStep(step: Task) {
        _uiState.update { state ->
            // An answer that comes after another Focus opened belongs to that other one's list.
            if (state.taskId == null || step.parent != Task.iriOf(state.taskId)) return@update state
            val others = state.steps.filter { it.id != step.id }
            state.copy(steps = (others + step).sortedWith(Task.SIBLING_ORDER), message = null)
        }
    }

    /** « C'est fait »: the task is ticked, which the API announces as completed; on to the Bravo. */
    fun complete() {
        val state = _uiState.value
        val taskId = state.taskId ?: return
        if (state.task == null || state.isBusy) return
        _uiState.update { it.copy(isBusy = true, message = null) }
        viewModelScope.launch {
            taskRepository.toggleDone(taskId, true)
                .onSuccess { leave(taskId, completed = true) }
                .onFailure { fail(taskId, "Impossible de modifier la tâche.") }
        }
    }

    /**
     * « Plus tard, sans souci »: the task goes after the other slots still to do and « Maintenant » shows
     * the next. The day is read again first, so the order sent is the one on the server and not the one
     * the screen was opened on. With no other task to do, there is nothing to move: back to the day.
     */
    fun later() {
        val state = _uiState.value
        val taskId = state.taskId ?: return
        if (state.task == null || state.isBusy) return
        _uiState.update { it.copy(isBusy = true, message = null) }
        viewModelScope.launch {
            val failure = { fail(taskId, "Impossible de modifier la journée.") }
            taskRepository.getDayPlan(today())
                .onFailure { failure() }
                .onSuccess { day ->
                    val ids = day.taskIdsPuttingOff(taskId)
                    if (ids == day.taskIds) {
                        leave(taskId)
                        return@onSuccess
                    }
                    taskRepository.setDaySlots(day.day, ids)
                        .onSuccess { leave(taskId) }
                        .onFailure { failure() }
                }
        }
    }

    // An answer that comes after the Focus was closed, or reopened on another task, is not this Focus's.
    private fun leave(taskId: String, completed: Boolean = false) {
        if (_uiState.value.taskId != taskId) return
        stopTicking()
        _uiState.update { it.copy(isBusy = false, leaves = true, completed = completed) }
    }

    private fun fail(taskId: String, message: String) {
        if (_uiState.value.taskId != taskId) return
        _uiState.update { it.copy(isBusy = false, message = message) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    companion object {
        const val TICK_MS = 250L
    }
}
