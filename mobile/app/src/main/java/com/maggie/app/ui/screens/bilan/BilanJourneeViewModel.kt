package com.maggie.app.ui.screens.bilan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.Task
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.util.DateRanges
import com.maggie.app.util.DayPeriod
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** What a remaining task becomes in the bilan: the wire values of `answers.tasks[].choice`. */
enum class TaskChoice(val wire: String) {
    TOMORROW("tomorrow"),
    LATER("later"),
    ;

    companion object {
        fun of(wire: String?): TaskChoice? = entries.firstOrNull { it.wire == wire }
    }
}

data class JourneeTask(val id: String, val title: String, val choice: TaskChoice? = null, val saving: Boolean = false)

data class JourneeAgendaItem(val time: String, val title: String)

data class JourneeUiState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val done: Int = 0,
    val remaining: List<JourneeTask> = emptyList(),
    val agenda: List<JourneeAgendaItem> = emptyList(),
    /** Shown when a choice could not be saved: the task is back as it was. */
    val message: String? = null,
) {
    val total: Int get() = done + remaining.size

    /** « 4 faites sur 6 ». */
    val count: String get() = "$done ${if (done > 1) "faites" else "faite"} sur $total"
}

/**
 * The « Journée » step of the Bilan (MAG-752): what the day held and what is left. « Demain » gives a remaining
 * task tomorrow as its due day, « Plus tard » takes its due day away (it stays in the list, and the evening no
 * longer raises it). The task is changed first; only once that holds is the choice written to `answers.tasks`
 * ([record]), so a network error leaves both as they were.
 */
class BilanJourneeViewModel(
    private val taskRepository: TaskRepository,
    private val eventRepository: EventRepository,
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(JourneeUiState())
    val uiState: StateFlow<JourneeUiState> = _uiState

    private fun today(): LocalDate = clock().atZone(DateRanges.zone).toLocalDate()

    /** Reads the day; [choices] are the ones the bilan already holds (`taskId → choice`), so « Reprendre » keeps them. */
    fun load(choices: Map<String, String>) {
        _uiState.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            val day = today()
            val range = DayPeriod(day, day.plusDays(1)).asRange()
            val todayTasks = runCatching { taskRepository.getTodayTasks() }.rethrowCancellation().getOrElse {
                _uiState.update { s -> s.copy(loading = false, error = true) }
                return@launch
            }
            val done = runCatching {
                taskRepository.getCompletedBetween(range.start.toString(), range.end.toString(), 100)
            }.rethrowCancellation().getOrDefault(emptyList()).filter { !it.isSubtask }
            val open = todayTasks.filter { !it.isDone && !it.isSubtask }
            val remaining = open.map { JourneeTask(it.id, it.title, TaskChoice.of(choices[it.id])) } +
                // Already moved by an earlier visit: no longer due today, still on the list the user answered.
                choices.filterKeys { id -> open.none { it.id == id } && done.none { it.id == id } }
                    .mapNotNull { (id, wire) ->
                        val choice = TaskChoice.of(wire) ?: return@mapNotNull null
                        val task = taskRepository.fetchTask(id).getOrNull()?.takeIf { !it.isDone } ?: return@mapNotNull null
                        JourneeTask(task.id, task.title, choice)
                    }
            _uiState.update { it.copy(loading = false, error = false, done = done.size, remaining = remaining, agenda = agenda(day)) }
        }
    }

    private suspend fun agenda(day: LocalDate): List<JourneeAgendaItem> {
        eventRepository.loadPeriod(DayPeriod(day, day.plusDays(1)))
        val now = clock()
        return eventRepository.observeEvents().first()
            .filter { it.isOver(day, now) }
            .sortedBy { it.startAt }
            .map { JourneeAgendaItem(TIME.format(instantOf(it.startAt)!!.atZone(DateRanges.zone)), it.summary) }
    }

    /** Applies [choice] to [taskId]; [record] writes it in `answers.tasks` once the task has changed. */
    fun choose(taskId: String, choice: TaskChoice, record: (String, String?) -> Unit) {
        val current = _uiState.value.remaining.firstOrNull { it.id == taskId } ?: return
        if (current.saving || current.choice == choice) return
        update(taskId) { it.copy(choice = choice, saving = true) }
        _uiState.update { it.copy(message = null) }
        viewModelScope.launch {
            val dueDate = if (choice == TaskChoice.TOMORROW) today().plusDays(1).toString() else null
            taskRepository.setDueDate(taskId, dueDate)
                .onSuccess {
                    record(taskId, choice.wire)
                    update(taskId) { it.copy(saving = false) }
                }
                .onFailure {
                    update(taskId) { it.copy(choice = current.choice, saving = false) }
                    _uiState.update { s -> s.copy(message = "Impossible de ranger « ${current.title} » : réessayez.") }
                }
        }
    }

    /** « Oui, demain »: every task not yet answered goes to tomorrow. */
    fun allTomorrow(record: (String, String?) -> Unit) {
        _uiState.value.remaining.filter { it.choice == null }.forEach { choose(it.id, TaskChoice.TOMORROW, record) }
    }

    private fun update(taskId: String, change: (JourneeTask) -> JourneeTask) {
        _uiState.update { s -> s.copy(remaining = s.remaining.map { if (it.id == taskId) change(it) else it }) }
    }
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun instantOf(iso: String?): Instant? = iso?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

private fun Event.isOver(day: LocalDate, now: Instant): Boolean {
    if (allDay || status == "cancelled" || rrule != null) return false
    val start = instantOf(startAt) ?: return false
    val end = instantOf(endAt) ?: start
    return start.atZone(DateRanges.zone).toLocalDate() == day && !end.isAfter(now)
}
