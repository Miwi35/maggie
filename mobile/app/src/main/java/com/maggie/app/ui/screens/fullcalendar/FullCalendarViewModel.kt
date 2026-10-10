package com.maggie.app.ui.screens.fullcalendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.AgendaCreateRequest
import com.maggie.app.data.api.GoogleCalendarImportRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.GoogleCalendar
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealSlot
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.UserPreference
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.util.DateRanges
import com.maggie.app.util.EventExpander
import com.maggie.app.util.SingleFlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
    val currentDate: LocalDate = DateRanges.todayDate(),
    val expandedEvents: List<ExpandedEvent> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val agendas: List<Agenda> = emptyList(),
    val enabledAgendas: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val googleCalendars: List<GoogleCalendar> = emptyList(),
    val isLoadingGoogle: Boolean = false,
    // The « Repas » line of the filters: the meals are not an agenda of the user's (MAG-354).
    val mealsEnabled: Boolean = true,
)

/** The id of the one « Repas » line, and of the meals' [ExpandedEvent.agendaIri]. */
const val MEALS_FILTER_ID = "__meals__"
const val MEALS_COLOR = "#FF6B35"

class FullCalendarViewModel(
    private val eventRepository: EventRepository,
    private val taskRepository: TaskRepository,
    private val agendaRepository: AgendaRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val userPreferenceRepository: UserPreferenceRepository,
    private val mealRepository: MealRepository,
    // A module's own view (MAG-354): only what that module files, nothing of the user's
    // agendas, events or tasks. `null` is the general calendar.
    private val module: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FullCalendarUiState())
    val uiState: StateFlow<FullCalendarUiState> = _uiState

    private var allEvents: List<Event> = emptyList()
    private var meals: List<Meal> = emptyList()
    private var agendaMap: Map<String, Agenda> = emptyMap()
    private val loader = SingleFlight(viewModelScope) { load() }

    // Saved preferences only seed the initial state (MAG-120): once the user has picked a view or
    // toggled an agenda, or the seed was applied, they are not consulted again.
    private var preference: UserPreference? = null
    private var agendasLoaded = false
    private var viewSettled = false
    private var agendasSettled = false

    init {
        // Load once the auth token is available (and again on each login), rather
        // than racing the login in init.
        viewModelScope.launch {
            authRepository.token
                .map { it != null }
                .distinctUntilChanged()
                .collect { authenticated ->
                    if (authenticated) {
                        refresh()
                        fetchPreferenceIfMissing()
                    }
                }
        }
        viewModelScope.launch {
            applyPreference(userPreferenceRepository.preference.filterNotNull().first())
        }
        subscribeToMercure()
    }

    // The request can fail or be slow: the defaults stay until (and unless) it lands.
    private suspend fun fetchPreferenceIfMissing() {
        if (userPreferenceRepository.preference.value != null) return
        try {
            userPreferenceRepository.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun applyPreference(pref: UserPreference) {
        preference = pref
        if (!viewSettled) {
            viewSettled = true
            _uiState.value = _uiState.value.copy(viewType = viewTypeOf(pref.defaultCalendarView))
        }
        applyAgendaPreference()
        expandForCurrentRange()
    }

    private fun applyAgendaPreference() {
        val pref = preference ?: return
        if (!agendasLoaded || agendasSettled) return
        agendasSettled = true
        val wanted = pref.enabledAgendaIds.map { it.substringAfterLast('/') }.toSet()
        val agendaIds = _uiState.value.agendas.map { it.id }
        val matching = agendaIds.filter { it.substringAfterLast('/') in wanted }
        // Empty = never customised; no match = stale ids: both keep every agenda visible.
        if (matching.isEmpty()) return
        _uiState.value = _uiState.value.copy(enabledAgendas = matching.toSet())
    }

    private fun viewTypeOf(value: String) = when (value) {
        "week" -> CalendarViewType.WEEK
        "day" -> CalendarViewType.DAY
        else -> CalendarViewType.MONTH
    }

    fun setViewType(type: CalendarViewType) {
        viewSettled = true
        _uiState.value = _uiState.value.copy(viewType = type)
        onRangeChanged()
    }

    fun navigateForward() {
        val current = _uiState.value.currentDate
        val newDate = when (_uiState.value.viewType) {
            CalendarViewType.MONTH -> current.plusMonths(1)
            CalendarViewType.WEEK -> current.plusWeeks(1)
            CalendarViewType.DAY -> current.plusDays(1)
        }
        _uiState.value = _uiState.value.copy(currentDate = newDate)
        onRangeChanged()
    }

    fun navigateBackward() {
        val current = _uiState.value.currentDate
        val newDate = when (_uiState.value.viewType) {
            CalendarViewType.MONTH -> current.minusMonths(1)
            CalendarViewType.WEEK -> current.minusWeeks(1)
            CalendarViewType.DAY -> current.minusDays(1)
        }
        _uiState.value = _uiState.value.copy(currentDate = newDate)
        onRangeChanged()
    }

    fun goToToday() {
        _uiState.value = _uiState.value.copy(currentDate = DateRanges.todayDate())
        onRangeChanged()
    }

    fun navigateToDate(date: LocalDate) {
        _uiState.value = _uiState.value.copy(currentDate = date)
        onRangeChanged()
    }

    fun toggleMeals() {
        _uiState.value = _uiState.value.copy(mealsEnabled = !_uiState.value.mealsEnabled)
        expandForCurrentRange()
    }

    fun toggleAgenda(agendaId: String) {
        agendasSettled = true
        val current = _uiState.value.enabledAgendas.toMutableSet()
        if (agendaId in current) current.remove(agendaId) else current.add(agendaId)
        _uiState.value = _uiState.value.copy(enabledAgendas = current)
        expandForCurrentRange()
    }

    fun refresh() = loader.run()

    private suspend fun load() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        try {
            if (module != null) {
                val mealsFailure = loadMeals()
                _uiState.value = _uiState.value.copy(isLoading = false, error = mealsFailure?.message)
                expandForCurrentRange()
                return
            }
            val agendas = agendaRepository.refreshAgendas().getOrThrow()
            agendaMap = agendas.associateBy { it.id }

            val rangeEvents = eventRepository.refreshEvents().getOrThrow()
            val range = getVisibleRange()
            val recurringEvents = eventRepository.getRecurringBefore(range.start.toString())

            val seen = mutableSetOf<String>()
            allEvents = (rangeEvents + recurringEvents).filter { seen.add(it.id) }

            taskRepository.refreshTasks()
            val tasks = taskRepository.getUndoneTasks()
            // Best effort: the meals failing must not blank the user's own calendar.
            val mealsFailure = loadMeals()

            // Auto-enable new agendas
            val previousEnabled = _uiState.value.enabledAgendas
            val allIds = agendas.map { it.id }.toSet()
            val enabledAgendas = if (previousEnabled.isEmpty()) allIds
            else previousEnabled + (allIds - previousEnabled)

            _uiState.value = _uiState.value.copy(
                agendas = agendas,
                enabledAgendas = enabledAgendas,
                tasks = tasks,
                isLoading = false,
                error = mealsFailure?.message,
            )
            agendasLoaded = true
            applyAgendaPreference()
            expandForCurrentRange()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
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

    private fun visibleDays(): ClosedRange<LocalDate> {
        val date = _uiState.value.currentDate
        return when (_uiState.value.viewType) {
            CalendarViewType.MONTH -> date.withDayOfMonth(1)..date.withDayOfMonth(1).plusMonths(1).minusDays(1)
            CalendarViewType.WEEK -> date.with(DayOfWeek.MONDAY)..date.with(DayOfWeek.MONDAY).plusDays(6)
            CalendarViewType.DAY -> date..date
        }
    }

    // A meal is a day and a slot (MAG-251): both ends of the range are included. A reply that
    // lands after the user has moved to another range is dropped, so a slow answer never
    // replaces the meals of the range now on screen. Returns the failure, if any.
    private suspend fun loadMeals(): Throwable? {
        val days = visibleDays()
        val result = mealRepository.getMeals(fromDay = days.start.toString(), toDay = days.endInclusive.toString())
        result.onSuccess { if (visibleDays() == days) meals = it }
        return result.exceptionOrNull()
    }

    private fun onRangeChanged() {
        expandForCurrentRange()
        viewModelScope.launch {
            val failure = loadMeals()
            if (failure != null) _uiState.value = _uiState.value.copy(error = failure.message)
            expandForCurrentRange()
        }
    }

    private fun mealEvents(): List<ExpandedEvent> {
        if (module == null && !_uiState.value.mealsEnabled) return emptyList()
        val days = visibleDays()
        return meals
            .filter { LocalDate.parse(it.date) in days }
            .sortedWith(compareBy<Meal>({ it.date }, { it.slot }))
            .map { meal ->
                val day = LocalDate.parse(meal.date)
                ExpandedEvent(
                    id = meal.id,
                    summary = "${if (meal.slot == MealSlot.LUNCH) "Déj" else "Dîner"}: ${meal.summary}",
                    allDay = true,
                    startAt = day.atStartOfDay(PARIS).toInstant().toString(),
                    endAt = day.plusDays(1).atStartOfDay(PARIS).toInstant().toString(),
                    agendaIri = MEALS_FILTER_ID,
                    agendaColor = MEALS_COLOR,
                    agendaName = "Repas",
                )
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
        _uiState.value = _uiState.value.copy(expandedEvents = expanded + mealEvents())
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

    fun importGoogleCalendar(googleCalendarId: String) {
        viewModelScope.launch {
            agendaRepository.importGoogleCalendar(GoogleCalendarImportRequest(googleCalendarId))
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

    // Subscribed once the user is known, and again on each login: the ViewModel is built before
    // the login screen has run, so the user id is not there yet at init. One change is announced
    // on several topics and a sync announces many rows: the burst reloads the screen once.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            authRepository.token
                .map { it != null }
                .distinctUntilChanged()
                .collectLatest { authenticated ->
                    if (!authenticated) return@collectLatest
                    val userId = authRepository.getUserId() ?: return@collectLatest
                    merge(
                        *listOf(MercureTopics.EVENTS, MercureTopics.TASKS, MercureTopics.AGENDAS, MercureTopics.MEALS).map { topic ->
                            mercureService.subscribe(MercureTopics.userScoped(userId, topic))
                                .catch { /* SSE reconnects automatically */ }
                        }.toTypedArray(),
                    )
                        .coalescedAndRechecked()
                        .collect { refresh() }
                }
        }
    }

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
    }
}
