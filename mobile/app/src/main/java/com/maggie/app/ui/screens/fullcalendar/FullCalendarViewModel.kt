package com.maggie.app.ui.screens.fullcalendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.AgendaCreateRequest
import com.maggie.app.data.api.GoogleCalendarImportRequest
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.GoogleCalendar
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class FullCalendarUiState(
    val viewType: CalendarViewType = CalendarViewType.MONTH,
    val currentDate: LocalDate = LocalDate.now(),
    val expandedEvents: List<ExpandedEvent> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val agendas: List<Agenda> = emptyList(),
    val enabledAgendas: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val googleCalendars: List<GoogleCalendar> = emptyList(),
    val isLoadingGoogle: Boolean = false,
)

class FullCalendarViewModel(
    private val eventRepository: EventRepository,
    private val taskRepository: TaskRepository,
    private val agendaRepository: AgendaRepository,
    private val mercureService: MercureService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FullCalendarUiState())
    val uiState: StateFlow<FullCalendarUiState> = _uiState

    private var allEvents: List<Event> = emptyList()
    private var agendaMap: Map<String, Agenda> = emptyMap()

    init {
        refresh()
        subscribeToMercure()
    }

    fun setViewType(type: CalendarViewType) {
        _uiState.value = _uiState.value.copy(viewType = type)
        expandForCurrentRange()
    }

    fun navigateForward() {
        val current = _uiState.value.currentDate
        val newDate = when (_uiState.value.viewType) {
            CalendarViewType.MONTH -> current.plusMonths(1)
            CalendarViewType.WEEK -> current.plusWeeks(1)
            CalendarViewType.DAY -> current.plusDays(1)
        }
        _uiState.value = _uiState.value.copy(currentDate = newDate)
        expandForCurrentRange()
    }

    fun navigateBackward() {
        val current = _uiState.value.currentDate
        val newDate = when (_uiState.value.viewType) {
            CalendarViewType.MONTH -> current.minusMonths(1)
            CalendarViewType.WEEK -> current.minusWeeks(1)
            CalendarViewType.DAY -> current.minusDays(1)
        }
        _uiState.value = _uiState.value.copy(currentDate = newDate)
        expandForCurrentRange()
    }

    fun goToToday() {
        _uiState.value = _uiState.value.copy(currentDate = LocalDate.now())
        expandForCurrentRange()
    }

    fun navigateToDate(date: LocalDate) {
        _uiState.value = _uiState.value.copy(currentDate = date)
        expandForCurrentRange()
    }

    fun toggleAgenda(agendaId: String) {
        val current = _uiState.value.enabledAgendas.toMutableSet()
        if (agendaId in current) current.remove(agendaId) else current.add(agendaId)
        _uiState.value = _uiState.value.copy(enabledAgendas = current)
        expandForCurrentRange()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val agendas = agendaRepository.getAgendas()
                agendaMap = agendas.associateBy { it.id }

                val rangeEvents = eventRepository.refreshEvents().getOrDefault(emptyList())
                val range = getVisibleRange()
                val recurringEvents = eventRepository.getRecurringBefore(range.start.toString())

                val seen = mutableSetOf<String>()
                allEvents = (rangeEvents + recurringEvents).filter { seen.add(it.id) }

                taskRepository.refreshTasks()
                val tasks = taskRepository.getUndoneTasks()

                _uiState.value = _uiState.value.copy(
                    agendas = agendas,
                    enabledAgendas = if (_uiState.value.enabledAgendas.isEmpty()) agendas.map { it.id }.toSet()
                    else _uiState.value.enabledAgendas,
                    tasks = tasks,
                    isLoading = false,
                )
                expandForCurrentRange()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun getTitle(): String {
        val date = _uiState.value.currentDate
        val locale = Locale.FRENCH
        return when (_uiState.value.viewType) {
            CalendarViewType.MONTH -> {
                val month = date.month.getDisplayName(TextStyle.FULL, locale)
                    .replaceFirstChar { it.uppercase() }
                "$month ${date.year}"
            }
            CalendarViewType.WEEK -> {
                val monday = date.with(DayOfWeek.MONDAY)
                val sunday = monday.plusDays(6)
                val fmt = DateTimeFormatter.ofPattern("d MMM", locale)
                "${monday.format(fmt)} - ${sunday.format(fmt)} ${date.year}"
            }
            CalendarViewType.DAY -> {
                val fmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", locale)
                date.format(fmt).replaceFirstChar { it.uppercase() }
            }
        }
    }

    private fun getVisibleRange(): com.maggie.app.util.DateRange {
        val date = _uiState.value.currentDate
        return when (_uiState.value.viewType) {
            CalendarViewType.MONTH -> DateRanges.forMonth(date)
            CalendarViewType.WEEK -> DateRanges.forWeek(date)
            CalendarViewType.DAY -> DateRanges.forDate(date)
        }
    }

    private fun expandForCurrentRange() {
        val range = getVisibleRange()
        val enabledAgendas = _uiState.value.enabledAgendas
        val filteredAgendaMap = agendaMap.filter { it.key in enabledAgendas }

        val expanded = EventExpander.expandForRange(allEvents, range.start, range.end, filteredAgendaMap)
            .filter { event ->
                val agendaId = event.agendaIri?.removePrefix("/api/agendas/")
                agendaId == null || agendaId in enabledAgendas
            }
        _uiState.value = _uiState.value.copy(expandedEvents = expanded)
    }

    fun createAgenda(name: String, color: String?, description: String?) {
        viewModelScope.launch {
            agendaRepository.createAgenda(AgendaCreateRequest(name, color, description))
                .onSuccess { refresh() }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun deleteAgenda(id: String) {
        viewModelScope.launch {
            agendaRepository.deleteAgenda(id)
                .onSuccess { refresh() }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun loadGoogleCalendars() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingGoogle = true)
            agendaRepository.getGoogleCalendars()
                .onSuccess { _uiState.value = _uiState.value.copy(googleCalendars = it, isLoadingGoogle = false) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message, isLoadingGoogle = false) }
        }
    }

    fun importGoogleCalendar(googleCalendarId: String, name: String?, color: String?) {
        viewModelScope.launch {
            agendaRepository.importGoogleCalendar(GoogleCalendarImportRequest(googleCalendarId, name, color))
                .onSuccess { refresh() }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun exportToGoogleCalendar(agendaId: String) {
        viewModelScope.launch {
            agendaRepository.exportToGoogleCalendar(agendaId)
                .onSuccess { refresh() }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
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
