package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.model.FinanceTimeline
import com.maggie.app.data.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The two horizons of the planning card (MAG-554): the Gantt over 36 months, or the frise over 20 years. */
enum class PlanningHorizon(val label: String) {
    MONTHS_36("36 mois"),
    YEARS_20("20 ans"),
}

/** A scenario the frise can be compared with: [id] is what `?compare=` takes. */
data class TimelineScenarioChoice(val id: String, val label: String)

/**
 * The scenarios offered for the comparison: the three automatic ones, always there. The user's own
 * scenarios have no client on the phone yet — they are chosen in the admin or by asking Maggie.
 */
val AUTOMATIC_SCENARIOS = listOf(
    TimelineScenarioChoice("auto-rapid", "Rapide"),
    TimelineScenarioChoice("auto-balanced", "Équilibré"),
    TimelineScenarioChoice("auto-quality", "Qualité de vie"),
)

data class TimelineUiState(
    val horizon: PlanningHorizon = PlanningHorizon.MONTHS_36,
    val isLoading: Boolean = false,
    /** Kept when a later read fails: the frise already drawn is still the owner's. */
    val timeline: FinanceTimeline? = null,
    val error: String? = null,
    /** The scenario drawn beside the followed one; null for none. */
    val compareId: String? = null,
    /** Said once when the API refused the comparison and the frise went back to the followed scenario alone. */
    val comparisonNotice: String? = null,
)

/**
 * The 20-year frise of the planning card (MAG-554). Nothing is read while the card shows 36 months;
 * choosing « 20 ans » reads the frise, choosing a scenario to compare reads it again with `compare`.
 * A comparison the API refuses (the followed scenario itself, a scenario that is gone) falls back to
 * the followed scenario alone, with the API's sentence.
 */
class FinanceTimelineViewModel(
    private val repository: ProjectRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState

    /** Numbers the reads, so an answer that lands after a newer request (or after « 36 mois ») is dropped. */
    private var read = 0

    fun selectHorizon(horizon: PlanningHorizon) {
        if (_uiState.value.horizon == horizon) return
        _uiState.value = _uiState.value.copy(horizon = horizon, comparisonNotice = null)
        if (horizon == PlanningHorizon.YEARS_20) {
            load()
        } else {
            // Back on the Gantt: a read still on its way is for a view that is no longer there.
            read++
            _uiState.value = _uiState.value.copy(isLoading = false, error = null)
        }
    }

    /** [id] null: no comparison. */
    fun selectComparison(id: String?) {
        if (_uiState.value.compareId == id) return
        _uiState.value = _uiState.value.copy(compareId = id, comparisonNotice = null)
        if (_uiState.value.horizon == PlanningHorizon.YEARS_20) load()
    }

    fun retry() = load()

    fun dismissNotice() {
        _uiState.value = _uiState.value.copy(comparisonNotice = null)
    }

    private fun load() {
        val mine = ++read
        val compare = _uiState.value.compareId
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            repository.getTimeline(compare)
                .onSuccess { timeline ->
                    if (mine != read) return@onSuccess
                    _uiState.value = _uiState.value.copy(isLoading = false, timeline = timeline, error = null)
                }
                .onFailure { failure ->
                    if (mine != read) return@onFailure
                    if (compare != null && failure is ApiRefusalException && failure.status in REFUSED_COMPARISON) {
                        _uiState.value = _uiState.value.copy(compareId = null, comparisonNotice = failure.message)
                        load()
                    } else {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = MESSAGE_LOAD)
                    }
                }
        }
    }

    companion object {
        const val MESSAGE_LOAD = "La frise sur 20 ans n'a pas pu être chargée."
        private val REFUSED_COMPARISON = setOf(400, 404)
    }
}
