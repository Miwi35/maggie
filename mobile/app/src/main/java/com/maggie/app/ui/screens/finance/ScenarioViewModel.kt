package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.ScenarioWriteRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.RepaymentFigures
import com.maggie.app.data.model.Scenario
import com.maggie.app.data.model.ScenarioComparison
import com.maggie.app.data.model.scenarioCopyName
import com.maggie.app.data.repository.ScenarioRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class ScenarioUiState(
    val scenarios: List<Scenario> = emptyList(),
    val isLoading: Boolean = false,
    /** False until the first answer: the screen shows a spinner, not « Aucun scénario ». */
    val hasLoaded: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /** The gap of N of each scenario against the current one, worded by the API; empty without a current scenario. */
    val gaps: Map<String, String> = emptyMap(),
    /** The scenario a write is under way for: its buttons wait. */
    val busyId: String? = null,
    /** Ticked for the comparison, in the order they were ticked: the first is the reference. */
    val selected: List<String> = emptyList(),
    val comparison: ScenarioComparison? = null,
    val isComparing: Boolean = false,
    val comparisonError: String? = null,
    /** What each repayment of a listed scenario comes to, by repayment id; absent while unread or when the read failed. */
    val repaymentFigures: Map<String, RepaymentFigures> = emptyMap(),
    val editor: ScenarioEditor? = null,
    val options: ScenarioOptions? = null,
) {
    /** The only scenario the planning follows; null when the owner chose none. */
    val current: Scenario? get() = scenarios.firstOrNull { it.current }
}

/**
 * The scenarios of the projects (MAG-551): the list with the one current scenario, the comparison of two to six
 * of them, and the editor of a scenario. No scenario is current until the owner picks one. A write answers
 * with the scenario; the list is read again so the current one, the gaps of N and the comparison follow, and
 * Mercure does the same for what changes elsewhere (the admin, Maggie).
 */
class ScenarioViewModel(
    private val repository: ScenarioRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScenarioUiState(isLoading = true))
    val uiState: StateFlow<ScenarioUiState> = _uiState

    /** Numbers the comparisons, so an answer that lands after a newer one was asked for is dropped. */
    private var comparisonRead = 0
    private var draftKeys = 1000

    init {
        refresh()
        subscribeToMercure()
    }

    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.FINANCE_SCENARIOS))
                .catch { /* MercureService reconnects by itself */ }
                .coalesced()
                .collect { load(showLoading = false) }
        }
    }

    fun refresh() {
        viewModelScope.launch { load(showLoading = true) }
    }

    private suspend fun load(showLoading: Boolean) {
        if (showLoading) _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        repository.getScenarios()
            .onSuccess { scenarios ->
                val ids = scenarios.map { it.id }.toSet()
                val selected = _uiState.value.selected.filter { it in ids }
                _uiState.value = _uiState.value.copy(
                    scenarios = scenarios,
                    isLoading = false,
                    hasLoaded = true,
                    selected = selected,
                    comparison = if (selected.size >= 2) _uiState.value.comparison else null,
                )
            }
            .onFailure {
                _uiState.value = _uiState.value.copy(isLoading = false, error = MESSAGE_LOAD)
                return
            }
        loadGaps()
        loadRepaymentFigures()
        if (_uiState.value.selected.size >= 2) readComparison()
    }

    // The list is the summary: the figures of a repayment (real gain, effect on N…) come with the detail of its
    // scenario. A failed read leaves the repayment listed without its figures.
    private suspend fun loadRepaymentFigures() {
        val withRepayments = _uiState.value.scenarios.filter { scenario -> scenario.repayments.any { it.id != null } }
        val figures = buildMap {
            withRepayments.forEach { scenario -> repository.getRepaymentFigures(scenario.id).onSuccess { putAll(it) } }
        }
        _uiState.value = _uiState.value.copy(repaymentFigures = figures)
    }

    // « N 9 mois plus tôt que « Équilibré » »: the API words it, from a comparison against the current scenario.
    // Without it the list simply shows no gap line.
    private suspend fun loadGaps() {
        val state = _uiState.value
        val current = state.current
        val others = state.scenarios.filter { it.id != current?.id }.take(MAX_COMPARED - 1)
        if (current == null || others.isEmpty()) {
            _uiState.value = state.copy(gaps = emptyMap())
            return
        }
        repository.compare(listOf(current.id) + others.map { it.id })
            .onSuccess { comparison ->
                _uiState.value = _uiState.value.copy(
                    gaps = comparison.differences.filter { it.sentence.isNotBlank() }.associate { it.scenarioId to it.sentence },
                )
            }
            .onFailure { _uiState.value = _uiState.value.copy(gaps = emptyMap()) }
    }

    /** Rendre courant: the only scenario the planning and the home card follow. */
    fun makeCurrent(id: String) = write(id, MESSAGE_MAKE_CURRENT, success = { name -> "« $name » est le scénario courant" }) {
        repository.makeCurrent(id)
    }

    /** « Aucun scénario courant »: the planning goes back to the owner's order of projects. */
    fun clearCurrent() = write(null, MESSAGE_CLEAR_CURRENT, success = { "Aucun scénario courant" }) {
        repository.clearCurrent()
    }

    fun duplicate(id: String) {
        val source = _uiState.value.scenarios.firstOrNull { it.id == id } ?: return
        val name = scenarioCopyName(source.name, _uiState.value.scenarios.map { it.name })
        write(id, MESSAGE_DUPLICATE, success = { "« $name » créé" }) { repository.duplicate(id, name) }
    }

    /** Deletes a scenario of the owner. The automatic ones are never deleted. */
    fun delete(id: String) {
        val scenario = _uiState.value.scenarios.firstOrNull { it.id == id } ?: return
        if (scenario.isAuto) return
        write(id, MESSAGE_DELETE, success = { "« $it » supprimé" }) { repository.delete(id) }
    }

    /** « Ignorer » on a suggestion of the advisor: the draft is deleted. */
    fun dismissSuggestion(id: String) {
        val scenario = _uiState.value.scenarios.firstOrNull { it.id == id } ?: return
        if (!scenario.isAdvisor) return
        write(id, MESSAGE_DISMISS, success = { "Suggestion ignorée" }) { repository.delete(id) }
    }

    fun rename(id: String, name: String) {
        val scenario = _uiState.value.scenarios.firstOrNull { it.id == id } ?: return
        if (scenario.isAuto) return
        if (name.isBlank()) {
            _uiState.value = _uiState.value.copy(error = MESSAGE_NAME, message = null)
            return
        }
        write(id, MESSAGE_RENAME, success = { "Scénario renommé" }) { repository.update(id, ScenarioWriteRequest.rename(name.trim())) }
    }

    // One write at a time per scenario; its answer is followed by a fresh read of the list.
    private fun write(id: String?, fallback: String, success: (String) -> String, call: suspend () -> Result<*>) {
        if (_uiState.value.busyId != null) return
        val name = _uiState.value.scenarios.firstOrNull { it.id == id }?.name.orEmpty()
        _uiState.value = _uiState.value.copy(busyId = id ?: NO_SCENARIO, error = null, message = null)
        viewModelScope.launch {
            call()
                .onSuccess {
                    _uiState.value = _uiState.value.copy(busyId = null, message = success(name))
                    load(showLoading = false)
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(busyId = null, error = refusal(failure, fallback))
                }
        }
    }

    // ---- The comparison --------------------------------------------------------------------------------------------

    /** Ticks or unticks a scenario for the comparison; two to six give the curves and the table. */
    fun toggleCompared(id: String) {
        val state = _uiState.value
        if (state.scenarios.none { it.id == id }) return
        val selected = when {
            id in state.selected -> state.selected - id
            state.selected.size >= MAX_COMPARED -> {
                _uiState.value = state.copy(error = MESSAGE_TOO_MANY, message = null)
                return
            }
            else -> state.selected + id
        }
        val comparing = selected.size >= 2
        if (!comparing) comparisonRead++
        _uiState.value = state.copy(
            selected = selected,
            comparison = if (comparing) state.comparison else null,
            comparisonError = null,
            isComparing = comparing,
            error = null,
            message = null,
        )
        if (comparing) viewModelScope.launch { readComparison() }
    }

    fun clearComparison() {
        comparisonRead++
        _uiState.value = _uiState.value.copy(selected = emptyList(), comparison = null, isComparing = false, comparisonError = null)
    }

    // A failed read keeps the comparison already on screen only if it is for the same scenarios; otherwise it says so.
    private suspend fun readComparison() {
        val ids = _uiState.value.selected
        if (ids.size < 2) return
        val mine = ++comparisonRead
        _uiState.value = _uiState.value.copy(isComparing = true, comparisonError = null)
        repository.compare(ids)
            .onSuccess { comparison ->
                if (mine != comparisonRead) return@onSuccess
                _uiState.value = _uiState.value.copy(comparison = comparison, isComparing = false)
            }
            .onFailure { failure ->
                if (mine != comparisonRead) return@onFailure
                _uiState.value = _uiState.value.copy(isComparing = false, comparison = null, comparisonError = refusal(failure, MESSAGE_COMPARE))
            }
    }

    // ---- The editor ------------------------------------------------------------------------------------------------

    /** Opens the editor on a new scenario (every project, in the owner's order) or on [id]. */
    fun openEditor(id: String?) {
        val state = _uiState.value
        if (id == null) {
            _uiState.value = state.copy(editor = ScenarioEditor(draft = ScenarioDraft.starting(state.options?.projects.orEmpty())), error = null, message = null)
        } else {
            val scenario = state.scenarios.firstOrNull { it.id == id }
            if (scenario == null) {
                _uiState.value = state.copy(editor = null, error = MESSAGE_NOT_FOUND)
                return
            }
            _uiState.value = state.copy(
                editor = ScenarioEditor(scenarioId = id, draft = ScenarioDraft.of(scenario, state.options?.projects?.map { it.id })),
                error = null,
            )
        }
        loadOptions()
    }

    private fun loadOptions() {
        viewModelScope.launch {
            val projects = repository.getProjects().getOrNull()
            val loans = repository.getLoans().getOrNull()
            val placements = repository.getPlacements().getOrNull()
            if (projects == null) {
                _uiState.value = _uiState.value.copy(error = MESSAGE_OPTIONS)
                return@launch
            }
            val state = _uiState.value
            val editor = state.editor
            // A new scenario opened before the projects were known starts from all of them once they are.
            val started = if (editor != null && editor.isNew && editor.draft.projectIds.isEmpty() && editor.draft.name.isEmpty()) {
                editor.copy(draft = ScenarioDraft.starting(projects))
            } else {
                editor?.let { it.copy(draft = it.draft.copy(projectIds = it.draft.projectIds.filter { id -> projects.any { p -> p.id == id } })) }
            }
            _uiState.value = state.copy(
                options = ScenarioOptions(projects, loans.orEmpty(), placements.orEmpty()),
                editor = started,
            )
        }
    }

    fun editDraft(transform: (ScenarioDraft) -> ScenarioDraft) {
        val editor = _uiState.value.editor ?: return
        _uiState.value = _uiState.value.copy(editor = editor.copy(draft = transform(editor.draft), error = null))
    }

    fun addRepayment() = editDraft { it.copy(repayments = it.repayments + RepaymentDraft(key = draftKeys++)) }

    fun removeRepayment(key: Int) = editDraft { it.copy(repayments = it.repayments.filter { r -> r.key != key }) }

    fun editRepayment(key: Int, transform: (RepaymentDraft) -> RepaymentDraft) =
        editDraft { it.copy(repayments = it.repayments.map { r -> if (r.key == key) transform(r) else r }) }

    fun closeEditor() {
        _uiState.value = _uiState.value.copy(editor = null)
    }

    fun saveEditor() {
        val editor = _uiState.value.editor ?: return
        if (editor.isSaving) return
        val request = editor.draft.toRequest()
        if (request == null) {
            _uiState.value = _uiState.value.copy(editor = editor.copy(showErrors = true))
            return
        }

        _uiState.value = _uiState.value.copy(editor = editor.copy(showErrors = true, isSaving = true, error = null))
        viewModelScope.launch {
            val id = editor.scenarioId
            val result = if (id == null) repository.create(request) else repository.update(id, request)
            result
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        editor = _uiState.value.editor?.copy(isSaving = false, done = true),
                        message = if (id == null) "Scénario créé" else "Scénario enregistré",
                    )
                    load(showLoading = false)
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(
                        editor = _uiState.value.editor?.copy(isSaving = false, error = refusal(failure, MESSAGE_SAVE)),
                    )
                }
        }
    }

    /** The sheet is closed: what the last action said is not news any more. */
    fun clearFeedback() {
        _uiState.value = _uiState.value.copy(error = null, message = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    companion object {
        /** The API's own sentence when it refused (400, 404), else [fallback] (no network, a server error). */
        internal fun refusal(failure: Throwable, fallback: String): String =
            (failure as? ApiRefusalException)?.message?.takeIf { it.isNotBlank() } ?: fallback

        const val MAX_COMPARED = 6
        private const val NO_SCENARIO = "none"

        const val MESSAGE_LOAD = "Les scénarios n'ont pas pu être chargés."
        const val MESSAGE_MAKE_CURRENT = "Le scénario n'a pas pu être rendu courant."
        const val MESSAGE_CLEAR_CURRENT = "Le scénario courant n'a pas pu être retiré."
        const val MESSAGE_DUPLICATE = "Le scénario n'a pas pu être dupliqué."
        const val MESSAGE_DELETE = "Le scénario n'a pas été supprimé."
        const val MESSAGE_DISMISS = "La suggestion n'a pas pu être ignorée."
        const val MESSAGE_RENAME = "Le scénario n'a pas été renommé."
        const val MESSAGE_NAME = "Donnez un nom au scénario."
        const val MESSAGE_SAVE = "Le scénario n'a pas été enregistré."
        const val MESSAGE_COMPARE = "La comparaison n'a pas pu être chargée."
        const val MESSAGE_TOO_MANY = "Six scénarios au plus dans une comparaison."
        const val MESSAGE_NOT_FOUND = "Ce scénario n'existe plus. Tirez pour actualiser."
        const val MESSAGE_OPTIONS = "Les projets n'ont pas pu être chargés."
    }
}
