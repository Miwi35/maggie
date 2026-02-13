package com.maggie.app.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.Task
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.util.DateRanges
import com.maggie.app.util.EventExpander
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.Instant

data class DashboardUiState(
    val todayEvents: List<ExpandedEvent> = emptyList(),
    val tomorrowEvents: List<ExpandedEvent> = emptyList(),
    val weekEvents: List<ExpandedEvent> = emptyList(),
    val monthEvents: List<ExpandedEvent> = emptyList(),
    val todayTasks: List<Task> = emptyList(),
    val tomorrowTasks: List<Task> = emptyList(),
    val weekTasks: List<Task> = emptyList(),
    val monthTasks: List<Task> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class DashboardViewModel(
    private val eventRepository: EventRepository,
    private val taskRepository: TaskRepository,
    private val agendaRepository: AgendaRepository,
    private val mercureService: MercureService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState

    init {
        refresh()
        subscribeToMercure()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val ranges = object {
                    val today = DateRanges.today()
                    val tomorrow = DateRanges.tomorrow()
                    val week = DateRanges.thisWeek()
                    val month = DateRanges.thisMonth()
                }

                // Fetch all data
                val agendas = agendaRepository.getAgendas()
                val agendaMap = agendas.associateBy { it.id }

                val rangeEvents = eventRepository.refreshEvents().getOrDefault(emptyList())
                val recurringEvents = eventRepository.getRecurringBefore(ranges.month.start.toString())

                // Merge & deduplicate
                val seen = mutableSetOf<String>()
                val allEvents = mutableListOf<Event>()
                for (e in rangeEvents + recurringEvents) {
                    if (seen.add(e.id)) allEvents.add(e)
                }

                // Expand events for each range
                val todayExpanded = EventExpander.expandForRange(allEvents, ranges.today.start, ranges.today.end, agendaMap)
                val tomorrowExpanded = EventExpander.expandForRange(allEvents, ranges.tomorrow.start, ranges.tomorrow.end, agendaMap)
                val weekExpanded = EventExpander.expandForRange(allEvents, ranges.week.start, ranges.week.end, agendaMap)
                    .filter { !isInRange(it.startAt, ranges.today) && !isInRange(it.startAt, ranges.tomorrow) }
                val monthExpanded = EventExpander.expandForRange(allEvents, ranges.month.start, ranges.month.end, agendaMap)
                    .filter { !isInRange(it.startAt, ranges.week) && !isInRange(it.startAt, ranges.tomorrow) }

                // Fetch tasks
                val rawTasks = taskRepository.getUndoneTasks(ranges.month.end.toString())
                val undueTasks = taskRepository.getUndoneUndatedTasks()

                // Today tasks: overdue + today + undated
                val todayEnd = ranges.today.end
                val todayTasks = rawTasks.filter { t ->
                    t.dueDate != null && Instant.parse(t.dueDate) < todayEnd
                } + undueTasks

                // Tomorrow tasks
                val tomorrowRange = ranges.tomorrow
                val tomorrowTasks = rawTasks.filter { t ->
                    t.dueDate != null && isInRange(t.dueDate, tomorrowRange)
                }

                // Week tasks (exclude today/tomorrow)
                val weekTasks = rawTasks.filter { t ->
                    t.dueDate != null &&
                        Instant.parse(t.dueDate) >= ranges.tomorrow.end &&
                        Instant.parse(t.dueDate) < ranges.week.end
                }

                // Month tasks (exclude this week)
                val monthTasks = rawTasks.filter { t ->
                    t.dueDate != null &&
                        Instant.parse(t.dueDate) >= ranges.week.end &&
                        Instant.parse(t.dueDate) < ranges.month.end
                }

                _uiState.value = DashboardUiState(
                    todayEvents = todayExpanded,
                    tomorrowEvents = tomorrowExpanded,
                    weekEvents = weekExpanded,
                    monthEvents = monthExpanded,
                    todayTasks = todayTasks,
                    tomorrowTasks = tomorrowTasks,
                    weekTasks = weekTasks,
                    monthTasks = monthTasks,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message,
                    isLoading = false,
                )
            }
        }
    }

    fun toggleTaskDone(taskId: String, done: Boolean) {
        viewModelScope.launch {
            taskRepository.toggleDone(taskId, done)
            refresh()
        }
    }

    private fun isInRange(dateStr: String, range: com.maggie.app.util.DateRange): Boolean {
        val d = Instant.parse(dateStr)
        return d >= range.start && d < range.end
    }

    private fun subscribeToMercure() {
        viewModelScope.launch {
            mercureService.subscribe("/api/events/{id}")
                .catch { /* SSE reconnects automatically */ }
                .collect { refresh() }
        }
        viewModelScope.launch {
            mercureService.subscribe("/api/tasks/{id}")
                .catch { /* SSE reconnects automatically */ }
                .collect { refresh() }
        }
    }
}
