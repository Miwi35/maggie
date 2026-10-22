package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.PlacementWriteRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.PLACEMENT_MAX_RATE
import com.maggie.app.data.model.PLACEMENT_MAX_YEARS
import com.maggie.app.data.model.Placement
import com.maggie.app.data.model.PlacementFrequency
import com.maggie.app.data.model.PlacementSimulation
import com.maggie.app.data.repository.PlacementRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

enum class PlacementField { NAME, INITIAL, DEPOSIT, RATE, YEARS }

/** What the full-screen form holds while it is filled: text as typed; the rate in percent, the durée in years. */
data class PlacementDraft(
    val name: String = "",
    val initial: String = "",
    val deposit: String = "",
    val frequency: PlacementFrequency = PlacementFrequency.MONTHLY,
    val rate: String = "",
    val years: String = "",
) {
    /** An empty amount is 0: a placement may have no initial payment, or no deposit. */
    private fun cents(text: String): Int? = if (text.isBlank()) 0 else parseCents(text)?.takeIf { it >= 0 }

    private val basisPoints: Int?
        get() {
            val value = runCatching { BigDecimal(rate.trim().replace(',', '.').replace(" ", "")) }.getOrNull() ?: return null
            return runCatching { value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact() }.getOrNull()
                ?.takeIf { it in 0..PLACEMENT_MAX_RATE }
        }

    private val yearCount: Int? get() = years.trim().toIntOrNull()?.takeIf { it in 1..PLACEMENT_MAX_YEARS }

    /** The API's rules, checked before the request. */
    fun errors(): Map<PlacementField, String> = buildMap {
        if (name.isBlank()) put(PlacementField.NAME, "Donnez un nom au placement.")
        val initialCents = cents(initial)
        val depositCents = cents(deposit)
        if (initialCents == null) put(PlacementField.INITIAL, "Indiquez un montant positif ou nul.")
        if (depositCents == null) put(PlacementField.DEPOSIT, "Indiquez un montant positif ou nul.")
        if (initialCents == 0 && depositCents == 0) put(PlacementField.DEPOSIT, PlacementViewModel.MESSAGE_NOTHING_PAID)
        if (basisPoints == null) put(PlacementField.RATE, "Indiquez un rendement entre 0 et 100 %.")
        if (yearCount == null) put(PlacementField.YEARS, "Indiquez une durée de 1 à $PLACEMENT_MAX_YEARS ans.")
    }

    fun toRequest(): PlacementWriteRequest? {
        if (errors().isNotEmpty()) return null
        return PlacementWriteRequest(
            name = name.trim(),
            initial = cents(initial) ?: return null,
            deposit = cents(deposit) ?: return null,
            frequency = frequency.key,
            annualRate = basisPoints ?: return null,
            years = yearCount ?: return null,
        )
    }

    companion object {
        fun of(placement: Placement): PlacementDraft = PlacementDraft(
            name = placement.name,
            initial = euroInput(placement.initial),
            deposit = euroInput(placement.deposit),
            frequency = PlacementFrequency.of(placement.frequency),
            rate = BigDecimal(placement.annualRate).movePointLeft(2).stripTrailingZeros().toPlainString().replace('.', ','),
            years = placement.years.toString(),
        )

        /** « 2000 », « 12,50 »: what the field shows, read back by [parseCents]. */
        private fun euroInput(cents: Int): String =
            if (cents % 100 == 0) (cents / 100).toString() else String.format(Locale.FRANCE, "%.2f", cents / 100.0)
    }
}

/** The form on screen: a new placement when [placementId] is null. [done] tells the screen to close. */
data class PlacementForm(
    val placementId: String? = null,
    val draft: PlacementDraft = PlacementDraft(years = "10"),
    /** Set by the first save: an empty field is not an error while the form is still being filled. */
    val showErrors: Boolean = false,
    val isSaving: Boolean = false,
    /** What the server refused, said in French. */
    val error: String? = null,
    val done: Boolean = false,
) {
    val isNew: Boolean get() = placementId == null
    val errors: Map<PlacementField, String> get() = if (showErrors) draft.errors() else emptyMap()
}

/** A stepper of the tablet's « Réglages du placement »: one tap, one step. */
enum class PlacementStep(val key: String) {
    DEPOSIT("deposit"),
    RATE("rate"),
    YEARS("years"),
}

data class PlacementUiState(
    val placements: List<Placement> = emptyList(),
    val isLoading: Boolean = false,
    /** False until the first answer: the screen shows a spinner, not the empty state. */
    val hasLoaded: Boolean = false,
    val selectedId: String? = null,
    /** The month-by-month curve of [selected]; null while it is on its way, or when it failed ([simulationFailed]). */
    val simulation: PlacementSimulation? = null,
    val simulationFailed: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val form: PlacementForm? = null,
) {
    val selected: Placement? get() = placements.firstOrNull { it.id == selectedId } ?: placements.firstOrNull()

    /** What the placements' deposits come to per month, on average. */
    val monthlyDeposits: Long get() = placements.sumOf { it.monthlyDeposit }
}

/**
 * « Placements » of Planification (MAG-555): the simulated placements, the projection of the one
 * selected, the tablet's steppers that PATCH it, and the full-screen form that creates, edits and
 * deletes one. The navigation scopes it to the list's entry; the form is what is in front of it.
 */
class PlacementViewModel(
    private val repository: PlacementRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlacementUiState(isLoading = true))
    val uiState: StateFlow<PlacementUiState> = _uiState

    private var simulationJob: Job? = null

    /** The placement as the server last stored it, per id: where a refused step goes back to. */
    private val stored = mutableMapOf<String, Placement>()
    /** Per placement: the change waiting for the taps to pause, and the number of the last one. */
    private val stepJobs = mutableMapOf<String, Job>()
    private val stepSeqs = mutableMapOf<String, Int>()
    /** The placements whose last change is not answered yet: a list read meanwhile keeps their values on screen. */
    private val stepping = mutableSetOf<String>()
    /** One PATCH at a time, in the order of the taps: the last one to land is the last one tapped. */
    private val writeLock = Mutex()

    init {
        refresh()
        subscribeToMercure()
    }

    // A placement written elsewhere (the admin, Maggie) reaches the screen; never over a step on its way.
    // Read twice: the list is served from Elasticsearch, which may not have indexed the change yet.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.FINANCE_PLACEMENTS))
                .catch { /* MercureService reconnects by itself */ }
                .coalescedAndRechecked()
                .collect { if (stepping.isEmpty()) load(showLoading = false) }
        }
    }

    fun refresh() {
        viewModelScope.launch { load(showLoading = true) }
    }

    private suspend fun load(showLoading: Boolean) {
        if (showLoading) _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        repository.getPlacements()
            .onSuccess { fresh ->
                fresh.forEach { stored[it.id] = it }
                val shown = fresh.map { placement ->
                    if (placement.id in stepping) _uiState.value.placements.firstOrNull { it.id == placement.id } ?: placement else placement
                }
                val state = _uiState.value
                val selectedId = state.selectedId?.takeIf { id -> shown.any { it.id == id } } ?: shown.firstOrNull()?.id
                _uiState.value = state.copy(placements = shown, isLoading = false, hasLoaded = true, selectedId = selectedId)
                loadSimulation()
            }
            .onFailure {
                _uiState.value = _uiState.value.copy(isLoading = false, error = MESSAGE_LOAD)
            }
    }

    /** The curve of the selected placement; an answer for a placement no longer selected is dropped. */
    private fun loadSimulation() {
        simulationJob?.cancel()
        val id = _uiState.value.selected?.id
        if (id == null) {
            _uiState.value = _uiState.value.copy(simulation = null, simulationFailed = false)
            return
        }
        simulationJob = viewModelScope.launch {
            val result = repository.getSimulation(id)
            if (_uiState.value.selected?.id != id) return@launch
            _uiState.value = result.fold(
                onSuccess = { _uiState.value.copy(simulation = it, simulationFailed = false) },
                onFailure = { _uiState.value.copy(simulation = null, simulationFailed = true) },
            )
        }
    }

    fun select(id: String) {
        if (_uiState.value.selected?.id == id || _uiState.value.placements.none { it.id == id }) return
        _uiState.value = _uiState.value.copy(selectedId = id, simulation = null, simulationFailed = false)
        loadSimulation()
    }

    /** The tablet's segmented control: the deposit is paid at another rhythm. */
    fun setFrequency(frequency: PlacementFrequency) {
        adjust { it.copy(frequency = frequency.key) }
    }

    /** One tap on a stepper: ±10 € of deposit, ±0,5 % of rate, ±1 year, within what the API accepts. */
    fun step(step: PlacementStep, plus: Boolean) {
        val sign = if (plus) 1 else -1
        adjust { placement ->
            when (step) {
                PlacementStep.DEPOSIT -> placement.copy(deposit = (placement.deposit + sign * DEPOSIT_STEP).coerceAtLeast(0))
                PlacementStep.RATE -> placement.copy(annualRate = (placement.annualRate + sign * RATE_STEP).coerceIn(0, PLACEMENT_MAX_RATE))
                PlacementStep.YEARS -> placement.copy(years = (placement.years + sign).coerceIn(1, PLACEMENT_MAX_YEARS))
            }
        }
    }

    /**
     * The change shows at once; it is sent once the taps pause ([STEP_DEBOUNCE_MS]), the whole
     * placement in one PATCH. Only the answer to the last change replaces what is on screen; a
     * refusal of it puts back the placement as the server last stored it, and says why in French.
     */
    private fun adjust(transform: (Placement) -> Placement) {
        val current = _uiState.value.selected ?: return
        val next = transform(current)
        if (next == current || (next.initial == 0 && next.deposit == 0)) return
        stored.putIfAbsent(current.id, current)
        replace(next)
        val id = next.id
        val seq = (stepSeqs[id] ?: 0) + 1
        stepSeqs[id] = seq
        stepping += id
        stepJobs[id]?.cancel()
        stepJobs[id] = viewModelScope.launch {
            delay(STEP_DEBOUNCE_MS)
            // Past the pause the request goes out whatever comes next; the next tap waits for its answer.
            withContext(NonCancellable) {
                writeLock.withLock {
                    // Deleted while the taps paused: nothing left to send.
                    val placement = _uiState.value.placements.firstOrNull { it.id == id } ?: run {
                        stepping -= id
                        return@withLock
                    }
                    val result = repository.updatePlacement(id, placement.toRequest())
                    result.onSuccess { stored[id] = it }
                    if (seq != stepSeqs[id]) return@withLock
                    stepping -= id
                    result
                        .onSuccess { saved ->
                            replace(saved)
                            if (_uiState.value.selected?.id == id) loadSimulation()
                        }
                        .onFailure { failure ->
                            stored[id]?.let(::replace)
                            _uiState.value = _uiState.value.copy(error = refusal(failure, MESSAGE_SAVE))
                        }
                }
            }
        }
    }

    private fun replace(placement: Placement) {
        _uiState.value = _uiState.value.copy(
            placements = _uiState.value.placements.map { if (it.id == placement.id) placement else it },
        )
    }

    fun openNew() {
        _uiState.value = _uiState.value.copy(form = PlacementForm(), error = null)
    }

    fun openEdit(id: String) {
        val placement = _uiState.value.placements.firstOrNull { it.id == id }
        if (placement == null) {
            _uiState.value = _uiState.value.copy(form = null, error = MESSAGE_NOT_FOUND)
            return
        }
        _uiState.value = _uiState.value.copy(
            form = PlacementForm(placementId = id, draft = PlacementDraft.of(placement)),
            error = null,
        )
    }

    fun editDraft(transform: (PlacementDraft) -> PlacementDraft) {
        val form = _uiState.value.form ?: return
        _uiState.value = _uiState.value.copy(form = form.copy(draft = transform(form.draft), error = null))
    }

    fun closeForm() {
        _uiState.value = _uiState.value.copy(form = null)
    }

    fun save() {
        val form = _uiState.value.form ?: return
        if (form.isSaving) return
        val request = form.draft.toRequest()
        if (request == null) {
            _uiState.value = _uiState.value.copy(form = form.copy(showErrors = true))
            return
        }

        _uiState.value = _uiState.value.copy(form = form.copy(showErrors = true, isSaving = true, error = null))
        viewModelScope.launch {
            val id = form.placementId
            val result = if (id == null) repository.createPlacement(request) else repository.updatePlacement(id, request)
            result
                .onSuccess { saved ->
                    stored[saved.id] = saved
                    _uiState.value = _uiState.value.copy(
                        form = _uiState.value.form?.copy(isSaving = false, done = true),
                        message = if (id == null) "Placement créé" else "Placement enregistré",
                        // The placement just written is the one the projection shows.
                        selectedId = saved.id,
                        simulation = null,
                        simulationFailed = false,
                    )
                    load(showLoading = false)
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(
                        form = _uiState.value.form?.copy(isSaving = false, error = refusal(failure, MESSAGE_SAVE)),
                    )
                }
        }
    }

    /** Takes the placement out of the list at once; it comes back with the reason if the API refuses. */
    fun delete(id: String) {
        val before = _uiState.value
        if (before.placements.none { it.id == id }) return
        val remaining = before.placements.filter { it.id != id }
        _uiState.value = before.copy(
            placements = remaining,
            selectedId = if (before.selected?.id == id) remaining.firstOrNull()?.id else before.selectedId,
            form = before.form?.let { if (it.placementId == id) it.copy(done = true) else it },
            error = null,
        )
        if (before.selected?.id == id) loadSimulation()
        viewModelScope.launch {
            repository.deletePlacement(id)
                .onSuccess {
                    stored.remove(id)
                    _uiState.value = _uiState.value.copy(message = "Placement supprimé")
                    load(showLoading = false)
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(
                        placements = before.placements,
                        selectedId = before.selectedId,
                        error = refusal(failure, MESSAGE_DELETE),
                    )
                    loadSimulation()
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    companion object {
        const val STEP_DEBOUNCE_MS = 400L
        /** 10 €, 0,5 %: the steps of the tablet's mock-up. */
        const val DEPOSIT_STEP = 1_000
        const val RATE_STEP = 50

        /**
         * The API refuses in English: the refusal a user can cause (nothing paid at all) is said in
         * French; anything else, and any failure that is not a refusal (no network), is [fallback].
         */
        internal fun refusal(failure: Throwable, fallback: String): String {
            val message = (failure as? ApiRefusalException)?.message ?: return fallback
            return when {
                message.contains("initial payment or a deposit", ignoreCase = true) -> MESSAGE_NOTHING_PAID
                else -> fallback
            }
        }

        const val MESSAGE_LOAD = "Les placements n'ont pas pu être chargés."
        const val MESSAGE_NOT_FOUND = "Ce placement n'existe plus."
        const val MESSAGE_SAVE = "Le placement n'a pas été enregistré."
        const val MESSAGE_DELETE = "Le placement n'a pas été supprimé."
        const val MESSAGE_NOTHING_PAID = "Un placement a besoin d'un versement initial ou de versements réguliers."
        const val MESSAGE_SIMULATION = "La projection n'a pas pu être calculée."
    }
}

/** Whether the minus of [step] still leaves a placement the API takes: a deposit never goes to 0 without an initial payment. */
internal fun canStepDown(step: PlacementStep, placement: Placement): Boolean = when (step) {
    PlacementStep.DEPOSIT -> placement.deposit > 0 && (placement.initial > 0 || placement.deposit > PlacementViewModel.DEPOSIT_STEP)
    PlacementStep.RATE -> placement.annualRate > 0
    PlacementStep.YEARS -> placement.years > 1
}

/** Whether the plus of [step] stays within the API's bounds. */
internal fun canStepUp(step: PlacementStep, placement: Placement): Boolean = when (step) {
    PlacementStep.DEPOSIT -> true
    PlacementStep.RATE -> placement.annualRate < PLACEMENT_MAX_RATE
    PlacementStep.YEARS -> placement.years < PLACEMENT_MAX_YEARS
}

private fun Placement.toRequest() = PlacementWriteRequest(
    name = name,
    initial = initial,
    deposit = deposit,
    frequency = frequency,
    annualRate = annualRate,
    years = years,
)
