package com.maggie.app.ui.screens.fullcalendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.AgendaCreateRequest
import com.maggie.app.data.api.GoogleCalendarImportRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.GoogleCalendar
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealSlot
import com.maggie.app.data.model.Proaction
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.Trip
import com.maggie.app.data.model.UserPreference
import com.maggie.app.data.model.endDateAfter
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.ProactionRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.repository.TripRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.util.DateRanges
import com.maggie.app.util.DayPeriod
import com.maggie.app.util.EventExpander
import com.maggie.app.util.SingleFlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
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
    // The « Maggie » line: her proactions are events of an internal agenda, not one of the user's (MAG-328).
    val proactions: List<Proaction> = emptyList(),
    val proactionsEnabled: Boolean = true,
    // The trips Maggie chose for the events of the day on screen (MAG-658): read for the day view only.
    val trips: List<Trip> = emptyList(),
)

/** The id of the one « Repas » line, and of the meals' [ExpandedEvent.agendaIri]. */
const val MEALS_FILTER_ID = "__meals__"
const val MEALS_COLOR = "#FF6B35"

/** The id of the one « Maggie » line, and of the proactions' [ExpandedEvent.agendaIri]. */
const val PROACTIONS_FILTER_ID = "__proactions__"
const val PROACTIONS_COLOR = "#7C4DFF"

class FullCalendarViewModel(
    private val eventRepository: EventRepository,
    private val taskRepository: TaskRepository,
    private val agendaRepository: AgendaRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val userPreferenceRepository: UserPreferenceRepository,
    private val mealRepository: MealRepository,
    private val proactionRepository: ProactionRepository,
    private val tripRepository: TripRepository,
    // A module's own view (MAG-354): only what that module files, nothing of the user's
    // agendas, events or tasks. `null` is the general calendar.
    private val module: String? = null,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(FullCalendarUiState())
    val uiState: StateFlow<FullCalendarUiState> = _uiState

    private var allEvents: List<Event> = emptyList()
    private var meals: List<Meal> = emptyList()
    private var proactions: List<Proaction> = emptyList()
    private var agendaMap: Map<String, Agenda> = emptyMap()
    private val loader = SingleFlight(viewModelScope) { load() }
    private var forceNext = false
    private var periodJob: Job? = null
    private var tripsJob: Job? = null

    // Saved preferences only seed the initial state (MAG-120): once the user has picked a view or
    // toggled an agenda, or the seed was applied, they are not consulted again.
    private var preference: UserPreference? = null
    private var agendasLoaded = false
    private var agendasFetched = false
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
                        loader.run()
                        fetchPreferenceIfMissing()
                    }
                }
        }
        viewModelScope.launch {
            applyPreference(userPreferenceRepository.preference.filterNotNull().first())
        }
        // The cache is the single source: a read, a push or an edit lands in the tables and shows here.
        viewModelScope.launch {
            eventRepository.observeEvents().collect {
                allEvents = it
                expandForCurrentRange()
            }
        }
        viewModelScope.launch {
            agendaRepository.observeAgendas().collect { applyAgendas(it) }
        }
        subscribeToMercure()
        subscribeToTrips()
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

    fun toggleProactions() {
        _uiState.value = _uiState.value.copy(proactionsEnabled = !_uiState.value.proactionsEnabled)
        expandForCurrentRange()
    }

    fun toggleAgenda(agendaId: String) {
        agendasSettled = true
        val current = _uiState.value.enabledAgendas.toMutableSet()
        if (agendaId in current) current.remove(agendaId) else current.add(agendaId)
        _uiState.value = _uiState.value.copy(enabledAgendas = current)
        expandForCurrentRange()
    }

    /** Pull-to-refresh: the visible period and the agendas are read again whatever the cache holds. */
    fun refresh() {
        forceNext = true
        loader.run()
    }

    private suspend fun load() {
        val force = forceNext
        forceNext = false
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        try {
            if (module != null) {
                val mealsFailure = loadMeals()
                _uiState.value = _uiState.value.copy(isLoading = false, error = mealsFailure?.message)
                expandForCurrentRange()
                return
            }
            val agendas = agendaRepository.loadAgendas(force).getOrThrow()
            agendasFetched = true
            applyAgendas(agendas)

            // The events reach the screen through the cache (observeEvents), once the period is in it.
            eventRepository.loadPeriod(visiblePeriod(), force).getOrThrow()

            taskRepository.refreshTasks()
            val tasks = taskRepository.getUndoneTasks()
            // Best effort: the meals failing must not blank the user's own calendar.
            val mealsFailure = loadMeals()
            val proactionsFailure = loadProactions()
            loadTrips()

            _uiState.value = _uiState.value.copy(
                tasks = tasks,
                isLoading = false,
                error = (mealsFailure ?: proactionsFailure)?.message,
            )
            expandForCurrentRange()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
        }
    }

    // The cache holds the agendas; a list that is empty before the first read says nothing about the account.
    private fun applyAgendas(agendas: List<Agenda>) {
        if (module != null || (agendas.isEmpty() && !agendasFetched)) return
        val known = agendaMap.keys
        agendaMap = agendas.associateBy { it.id }

        // Auto-enable new agendas: the ones the screen has not seen yet, not the ones the user or the preference left out.
        val previousEnabled = _uiState.value.enabledAgendas
        val allIds = agendas.map { it.id }.toSet()
        val enabledAgendas = if (previousEnabled.isEmpty() && known.isEmpty()) allIds
        else previousEnabled + (allIds - known)

        _uiState.value = _uiState.value.copy(agendas = agendas, enabledAgendas = enabledAgendas)
        agendasLoaded = true
        applyAgendaPreference()
        expandForCurrentRange()
    }

    // A period already read within the hour is served by the cache: no request.
    private fun loadVisiblePeriod() {
        if (module != null) return
        periodJob?.cancel()
        periodJob = viewModelScope.launch {
            eventRepository.loadPeriod(visiblePeriod())
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    private fun visiblePeriod(): DayPeriod {
        val date = _uiState.value.currentDate
        return when (_uiState.value.viewType) {
            CalendarViewType.MONTH -> DateRanges.periodOfMonthGrid(date)
            CalendarViewType.WEEK -> DateRanges.periodOfWeek(date)
            CalendarViewType.DAY -> DateRanges.periodOfDate(date)
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

    // Same rule as the meals: a reply that lands after the user has left the range is dropped.
    // Best effort too: Maggie's runs failing must not blank the user's own calendar.
    private suspend fun loadProactions(): Throwable? {
        if (module != null) return null
        val period = visiblePeriod()
        val range = period.asRange()
        val result = proactionRepository.getProactions(range.start.toString(), range.end.toString())
        result.onSuccess {
            if (visiblePeriod() == period) {
                proactions = it
                _uiState.value = _uiState.value.copy(proactions = it)
            }
        }
        return result.exceptionOrNull()
    }

    // Best effort and silent: the trip lines are an extra under the events, so a failure leaves the
    // agenda without them rather than with an error. A reply that lands after the user has left the
    // day is dropped, like the meals'.
    private suspend fun loadTrips() {
        val day = _uiState.value.currentDate
        if (module != null || _uiState.value.viewType != CalendarViewType.DAY) {
            if (_uiState.value.trips.isNotEmpty()) _uiState.value = _uiState.value.copy(trips = emptyList())
            return
        }
        val result = tripRepository.getTrips(day)
        if (_uiState.value.currentDate != day || _uiState.value.viewType != CalendarViewType.DAY) return
        // A failed read keeps the lines already shown: they are matched by event id, so none of another day can appear.
        val trips = result.getOrNull() ?: return
        _uiState.value = _uiState.value.copy(trips = trips)
    }

    private fun onRangeChanged() {
        expandForCurrentRange()
        tripsJob?.cancel()
        tripsJob = viewModelScope.launch { loadTrips() }
        loadVisiblePeriod()
        viewModelScope.launch {
            val failure = loadMeals()
            if (failure != null) _uiState.value = _uiState.value.copy(error = failure.message)
            expandForCurrentRange()
        }
        viewModelScope.launch {
            val failure = loadProactions()
            if (failure != null) _uiState.value = _uiState.value.copy(error = failure.message)
            expandForCurrentRange()
        }
    }

    /**
     * Each run as a timed event of the « Maggie » line: « 08:30 Summary », closed with ✓ or ⚠ once it
     * is past and done or failed. The id stays the proaction's, which is also its event id.
     */
    private fun proactionEvents(): List<ExpandedEvent> {
        if (module != null || !_uiState.value.proactionsEnabled) return emptyList()
        val now = clock.instant()
        val range = getVisibleRange()
        return proactions.mapNotNull { proaction ->
            val start = proaction.startAt?.let(::instantOf) ?: return@mapNotNull null
            if (start < range.start || start >= range.end) return@mapNotNull null
            val end = proaction.endAt?.let(::instantOf) ?: start
            val time = start.atZone(DateRanges.zone).toLocalTime().format(PROACTION_TIME)
            val mark = when {
                end > now -> ""
                proaction.isCompleted -> " ✓"
                proaction.isFailed -> " ⚠"
                else -> ""
            }
            ExpandedEvent(
                id = proaction.id,
                summary = "$time ${proaction.summary}$mark",
                startAt = start.toString(),
                endAt = end.toString(),
                timeZone = proaction.timeZone ?: DateRanges.zone.id,
                agendaIri = PROACTIONS_FILTER_ID,
                agendaColor = PROACTIONS_COLOR,
                agendaName = "Maggie",
            )
        }.sortedBy { it.sortKey }
    }

    /** Moves a run to [startAt]; the API keeps it five minutes long. The list is read again either way. */
    fun moveProaction(id: String, startAt: Instant) {
        viewModelScope.launch {
            proactionRepository.move(id, startAt.toString())
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
            reloadProactions()
        }
    }

    fun deleteProaction(id: String) {
        viewModelScope.launch {
            proactionRepository.delete(id)
                .onSuccess {
                    proactions = proactions.filterNot { it.id == id }
                    _uiState.value = _uiState.value.copy(proactions = proactions)
                    expandForCurrentRange()
                }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
            reloadProactions()
        }
    }

    private suspend fun reloadProactions() {
        val failure = loadProactions()
        if (failure != null) _uiState.value = _uiState.value.copy(error = failure.message)
        expandForCurrentRange()
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
                    startDate = day,
                    endDate = endDateAfter(day),
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
        _uiState.value = _uiState.value.copy(expandedEvents = expanded + mealEvents() + proactionEvents())
    }

    fun createAgenda(name: String, color: String?, description: String?) {
        viewModelScope.launch {
            agendaRepository.createAgenda(AgendaCreateRequest(name, color, description))
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun deleteAgenda(id: String) {
        viewModelScope.launch {
            agendaRepository.deleteAgenda(id)
                .onSuccess { eventRepository.dropEventsOfAgenda(id) }
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
    // the login screen has run, so the user id is not there yet at init. Events and agendas are
    // not listened to here: CalendarCacheFeed applies them to the cache, which this screen observes.
    // One change is announced on several topics and a sync announces many rows: the burst reloads once.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            authRepository.token
                .map { it != null }
                .distinctUntilChanged()
                .collectLatest { authenticated ->
                    if (!authenticated) return@collectLatest
                    val userId = authRepository.getUserId() ?: return@collectLatest
                    merge(
                        *listOf(MercureTopics.TASKS, MercureTopics.MEALS, MercureTopics.PROACTIONS).map { topic ->
                            mercureService.subscribe(MercureTopics.userScoped(userId, topic))
                                .catch { /* SSE reconnects automatically */ }
                        }.toTypedArray(),
                    )
                        .coalescedAndRechecked()
                        .collect { reloadSideData() }
                }
        }
    }

    // A trip is created, moved or removed by Maggie alone: the day's trips are read again.
    private fun subscribeToTrips() {
        viewModelScope.launch {
            authRepository.token
                .map { it != null }
                .distinctUntilChanged()
                .collectLatest { authenticated ->
                    if (!authenticated) return@collectLatest
                    val userId = authRepository.getUserId() ?: return@collectLatest
                    mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.TRIPS))
                        .catch { /* SSE reconnects automatically */ }
                        .coalesced()
                        .collect { loadTrips() }
                }
        }
    }

    private suspend fun reloadSideData() {
        try {
            if (module == null) {
                taskRepository.refreshTasks()
                _uiState.value = _uiState.value.copy(tasks = taskRepository.getUndoneTasks())
            }
            val mealsFailure = loadMeals()
            val failure = loadProactions() ?: mealsFailure
            if (failure != null) _uiState.value = _uiState.value.copy(error = failure.message)
            expandForCurrentRange()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = e.message)
        }
    }

    private companion object {
        val PROACTION_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        // The API may answer with an offset (`+02:00`) where the timeline parses instants.
        fun instantOf(value: String): Instant? =
            runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }
}
