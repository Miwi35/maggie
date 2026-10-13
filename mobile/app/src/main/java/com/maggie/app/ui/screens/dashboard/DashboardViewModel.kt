package com.maggie.app.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.Task
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.util.DateRange
import com.maggie.app.util.DateRanges
import com.maggie.app.util.DayPeriod
import com.maggie.app.util.EventExpander
import com.maggie.app.util.SingleFlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

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
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState

    private val loader = SingleFlight(viewModelScope) { load() }
    private var forceNext = false
    private var allEvents: List<Event> = emptyList()
    private var agendaMap: Map<String, Agenda> = emptyMap()

    init {
        // Refresh once the auth token is available (and again whenever the user
        // logs in), instead of firing a single fetch in init that can race the
        // login completing — which left the dashboard empty until a manual pull.
        viewModelScope.launch {
            authRepository.token
                .map { it != null }
                .distinctUntilChanged()
                .collect { authenticated -> if (authenticated) reload() }
        }
        // The cache is the single source of the events: a read, a push or an edit lands in it and shows here.
        viewModelScope.launch {
            eventRepository.observeEvents().collect {
                allEvents = it
                _uiState.value = withEvents(_uiState.value)
            }
        }
        viewModelScope.launch {
            agendaRepository.observeAgendas().collect {
                agendaMap = it.associateBy { agenda -> agenda.id }
                _uiState.value = withEvents(_uiState.value)
            }
        }
        subscribeToMercure()
    }

    // What the cache holds is served as it is while it is fresh (loadPeriod, loadAgendas).
    private fun reload() = loader.run()

    /** Pull-to-refresh: the period and the agendas are read again whatever the cache holds. */
    fun refresh() {
        forceNext = true
        loader.run()
    }

    private fun withEvents(state: DashboardUiState): DashboardUiState {
        val ranges = currentRanges()
        val today = EventExpander.expandForRange(allEvents, ranges.today.start, ranges.today.end, agendaMap)
        val tomorrow = EventExpander.expandForRange(allEvents, ranges.tomorrow.start, ranges.tomorrow.end, agendaMap)
        val week = EventExpander.expandForRange(allEvents, ranges.week.start, ranges.week.end, agendaMap)
            .filter { !startsIn(it, ranges.today) && !startsIn(it, ranges.tomorrow) }
        val month = EventExpander.expandForRange(allEvents, ranges.month.start, ranges.month.end, agendaMap)
            .filter { !startsIn(it, ranges.week) && !startsIn(it, ranges.tomorrow) }
        return state.copy(todayEvents = today, tomorrowEvents = tomorrow, weekEvents = week, monthEvents = month)
    }

    private fun currentRanges() = Ranges(
        today = DateRanges.today(),
        tomorrow = DateRanges.tomorrow(),
        week = DateRanges.thisWeek(),
        month = DateRanges.thisMonth(),
    )

    private class Ranges(
        val today: DateRange,
        val tomorrow: DateRange,
        val week: DateRange,
        val month: DateRange,
    )

    private suspend fun load() {
        val force = forceNext
        forceNext = false
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        try {
            val ranges = currentRanges()

            // The period lands in the cache, which the events are read from (observeEvents).
            agendaMap = agendaRepository.loadAgendas(force).getOrThrow().associateBy { it.id }
            eventRepository.loadPeriod(DayPeriod(DateRanges.todayDate(), DateRanges.todayDate().plusMonths(1)), force).getOrThrow()

            // Fetch tasks. A due date is a day (MAG-404): compared as days, never as instants.
            val today = DateRanges.todayDate()
            val tomorrow = today.plusDays(1)
            val weekEnd = today.plusDays(7)
            val monthEnd = today.plusMonths(1)
            val rawTasks = taskRepository.getUndoneTasks(monthEnd.toString()).filter(::listedOnItsOwn)
            val undueTasks = taskRepository.getUndoneUndatedTasks().filter(::listedOnItsOwn)

            // Today tasks: overdue + today + undated
            val todayTasks = rawTasks.filter { t -> t.dueDay?.let { it <= today } == true } + undueTasks

            val tomorrowTasks = rawTasks.filter { t -> t.dueDay == tomorrow }

            // Week tasks (exclude today/tomorrow)
            val weekTasks = rawTasks.filter { t -> t.dueDay?.let { it > tomorrow && it < weekEnd } == true }

            // Month tasks (exclude this week)
            val monthTasks = rawTasks.filter { t -> t.dueDay?.let { it >= weekEnd && it < monthEnd } == true }

            _uiState.value = withEvents(DashboardUiState(
                todayTasks = todayTasks,
                tomorrowTasks = tomorrowTasks,
                weekTasks = weekTasks,
                monthTasks = monthTasks,
                isLoading = false,
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                error = e.message,
                isLoading = false,
            )
        }
    }

    /**
     * A subtask lives in its parent's detail (MAG-404). An undated one would otherwise
     * land in « Aujourd'hui » beside its parent, once per step of every Google list;
     * a dated one keeps its row, as Google Calendar shows it on its day.
     */
    private fun listedOnItsOwn(task: Task): Boolean = !task.isSubtask || task.dueDate != null

    fun toggleTaskDone(taskId: String, done: Boolean) {
        viewModelScope.launch {
            taskRepository.toggleDone(taskId, done)
            reload()
        }
    }

    /** An all-day event starts on its date, read against the days of [range] in Paris (MAG-382). */
    private fun startsIn(event: ExpandedEvent, range: DateRange): Boolean {
        val firstDay = event.startDate
        if (event.allDay && firstDay != null) {
            return firstDay >= range.start.atZone(PARIS).toLocalDate() &&
                firstDay < range.end.atZone(PARIS).toLocalDate()
        }
        return event.startAt?.let { isInRange(it, range) } ?: false
    }

    private fun isInRange(dateStr: String, range: DateRange): Boolean {
        val d = Instant.parse(dateStr)
        return d >= range.start && d < range.end
    }

    // One change is announced on several topics and a sync announces many rows: the burst reloads once.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            // Events and agendas are applied to the cache by CalendarCacheFeed, which this screen observes.
            merge(
                *listOf(MercureTopics.TASKS).map { topic ->
                    mercureService.subscribe(MercureTopics.userScoped(userId, topic))
                        .catch { /* SSE reconnects automatically */ }
                }.toTypedArray(),
            )
                .coalescedAndRechecked()
                .collect { reload() }
        }
    }

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
    }
}
