package com.maggie.app.ui.screens.fullcalendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Period
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.PeriodRepository
import com.maggie.app.ui.screens.shared.EventFormState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

enum class PeriodField { NAME, AGENDA, DATES }

/** What the « Période » form holds: a new period when [periodId] is null. [done] tells the screen to close. */
data class PeriodForm(
    val periodId: String? = null,
    val name: String = "",
    val agendaIri: String? = null,
    val dates: EventFormState,
    val rrule: String? = null,
    val zone: ZoneId = ZoneId.of("Europe/Paris"),
    val showErrors: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
) {
    val isNew: Boolean get() = periodId == null

    fun errors(): Map<PeriodField, String> = buildMap {
        if (name.isBlank()) put(PeriodField.NAME, "Donnez un nom à la période.")
        if (agendaIri == null) put(PeriodField.AGENDA, "Choisissez un agenda.")
        if (dates.endsBeforeStart) put(PeriodField.DATES, "La fin ne peut pas précéder le début.")
    }

    val shownErrors: Map<PeriodField, String> get() = if (showErrors) errors() else emptyMap()

    /** The body of the write: the end of a day-long period goes exclusive; the repetition of an existing one is not touched. */
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name.trim())
        put("agenda", agendaIri)
        dates.patch(zone).forEach { (key, value) -> put(key, value) }
        if (isNew) {
            put("timeZone", zone.id)
            rrule?.let { put("rrule", it) }
        }
    }
}

data class PeriodUiState(
    val periods: List<Period> = emptyList(),
    val agendas: List<Agenda> = emptyList(),
    val tab: PeriodTab = PeriodTab.UPCOMING,
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /** The row highlighted beside the form on a tablet. */
    val selectedId: String? = null,
    val form: PeriodForm? = null,
)

/**
 * « Périodes » of Agenda (MAG-815): the list, and the form that creates, edits and deletes one. The navigation scopes
 * it to the list's entry; on a tablet the form is the list's detail pane, on a phone a route.
 */
class PeriodViewModel(
    private val repository: PeriodRepository,
    private val agendaRepository: AgendaRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(PeriodUiState(isLoading = true))
    val uiState: StateFlow<PeriodUiState> = _uiState

    init {
        refresh()
        subscribeToMercure()
    }

    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.PERIODS))
                .catch { /* MercureService reconnects by itself */ }
                .coalescedAndRechecked()
                .collect { load(showLoading = false) }
        }
    }

    fun refresh() {
        viewModelScope.launch { load(showLoading = true) }
    }

    private suspend fun load(showLoading: Boolean) {
        if (showLoading) _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        agendaRepository.loadAgendas().onSuccess { agendas -> _uiState.value = _uiState.value.copy(agendas = agendas) }
        repository.getPeriods()
            .onSuccess { fresh ->
                val state = _uiState.value
                _uiState.value = state.copy(
                    periods = fresh,
                    isLoading = false,
                    hasLoaded = true,
                    selectedId = state.selectedId?.takeIf { id -> fresh.any { it.id == id } },
                )
            }
            .onFailure { _uiState.value = _uiState.value.copy(isLoading = false, error = MESSAGE_LOAD) }
    }

    fun selectTab(tab: PeriodTab) {
        _uiState.value = _uiState.value.copy(tab = tab)
    }

    fun groups(): List<PeriodGroup> {
        val state = _uiState.value
        return periodGroups(state.periods, state.tab, clock.instant(), clock.zone)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun openNew(today: LocalDate = LocalDate.now(clock)) {
        val state = _uiState.value
        val default = state.agendas.firstOrNull { it.isDefault } ?: state.agendas.firstOrNull()
        _uiState.value = state.copy(
            selectedId = null,
            form = PeriodForm(
                agendaIri = default?.let { "/api/agendas/${it.id}" },
                dates = EventFormState(
                    allDay = true,
                    start = today.atStartOfDay(),
                    end = today.plusDays(6).atStartOfDay(),
                ),
            ),
        )
    }

    fun openEdit(id: String) {
        val state = _uiState.value
        val period = state.periods.firstOrNull { it.id == id }
        if (period == null) {
            _uiState.value = state.copy(form = null, error = MESSAGE_NOT_FOUND)
            return
        }
        _uiState.value = state.copy(selectedId = id, form = formOf(period))
    }

    fun closeForm() {
        _uiState.value = _uiState.value.copy(form = null, selectedId = null)
    }

    fun editForm(change: (PeriodForm) -> PeriodForm) {
        val form = _uiState.value.form ?: return
        _uiState.value = _uiState.value.copy(form = change(form).copy(error = null))
    }

    fun save() {
        val form = _uiState.value.form ?: return
        if (form.isSaving) return
        if (form.errors().isNotEmpty()) {
            _uiState.value = _uiState.value.copy(form = form.copy(showErrors = true))
            return
        }
        _uiState.value = _uiState.value.copy(form = form.copy(showErrors = true, isSaving = true, error = null))
        viewModelScope.launch {
            val id = form.periodId
            val body = form.toJson()
            val result = if (id == null) repository.createPeriod(body) else repository.updatePeriod(id, body)
            result
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        form = _uiState.value.form?.copy(isSaving = false, done = true),
                        message = if (id == null) "Période créée" else "Période enregistrée",
                    )
                    load(showLoading = false)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(form = _uiState.value.form?.copy(isSaving = false, error = MESSAGE_SAVE))
                }
        }
    }

    /** Takes the period out of the list at once; it comes back with the reason if the API refuses. */
    fun delete(id: String) {
        val before = _uiState.value
        if (before.periods.none { it.id == id }) return
        _uiState.value = before.copy(
            periods = before.periods.filter { it.id != id },
            form = before.form?.let { if (it.periodId == id) it.copy(done = true) else it },
            selectedId = before.selectedId?.takeIf { it != id },
            error = null,
        )
        viewModelScope.launch {
            repository.deletePeriod(id)
                .onSuccess { _uiState.value = _uiState.value.copy(message = "Période supprimée") }
                .onFailure {
                    _uiState.value = _uiState.value.copy(periods = before.periods, error = MESSAGE_DELETE)
                    load(showLoading = false)
                }
        }
    }

    private fun formOf(period: Period): PeriodForm {
        val dates = if (period.allDay && period.firstDay != null) {
            EventFormState(allDay = true, start = period.firstDay!!.atStartOfDay(), end = (period.lastDay ?: period.firstDay!!).atStartOfDay())
        } else {
            val start = period.startInstant?.atZone(period.zone)?.toLocalDateTime() ?: LocalDate.now(clock).atTime(9, 0)
            val end = period.endInstant?.atZone(period.zone)?.toLocalDateTime() ?: start.plusHours(1)
            EventFormState(allDay = false, start = start, end = end)
        }
        return PeriodForm(
            periodId = period.id,
            name = period.name,
            agendaIri = period.agenda,
            dates = dates,
            rrule = period.rrule,
            zone = period.zone,
        )
    }

    companion object {
        const val MESSAGE_LOAD = "Les périodes n'ont pas pu être chargées."
        const val MESSAGE_NOT_FOUND = "Cette période n'existe plus."
        const val MESSAGE_SAVE = "La période n'a pas été enregistrée."
        const val MESSAGE_DELETE = "La période n'a pas été supprimée."
    }
}
