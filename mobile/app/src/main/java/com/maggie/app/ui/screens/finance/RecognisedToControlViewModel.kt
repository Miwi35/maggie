package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.RETROSPECT_UNRATED
import com.maggie.app.data.model.RecognisedGroup
import com.maggie.app.data.model.RecognisedLine
import com.maggie.app.data.model.RecognisedToControl
import com.maggie.app.data.model.controlSummary
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** « Corriger » on a line: the category and the qualification the owner is putting by hand in place of the operation's. */
data class RecognisedCorrection(
    val line: RecognisedLine,
    val categoryId: String?,
    val qualification: Qualification = Qualification.NONE,
    /** The qualification the operation set is being read: saving before would overwrite it with nothing. */
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = !isLoading && !isSaving && categoryId != null
}

data class RecognisedToControlUiState(
    val isLoading: Boolean = true,
    /** True once a read has succeeded: before it, an empty list means nothing. */
    val loaded: Boolean = false,
    val data: RecognisedToControl = RecognisedToControl(),
    val categories: List<Category> = emptyList(),
    /** The group a « Valider » or a « Tout à conserver » is in flight on. */
    val controllingKey: String? = null,
    /** The line a satisfaction is in flight on. */
    val judgingId: String? = null,
    val correction: RecognisedCorrection? = null,
    val message: String? = null,
    /** A list that could not be read. */
    val loadError: String? = null,
    /** A gesture that was refused or lost: the lines stay where they were. */
    val actionError: String? = null,
    /** The line the cursor is on: it stays when the fiche is closed, so the list can show where the owner was. */
    val selectedId: String? = null,
    /** True while a line's fiche is open; false on the list. */
    val ficheOpen: Boolean = false,
) {
    val isEmpty: Boolean get() = loaded && data.groups.isEmpty()

    /** The lines in the order the list shows them: the groups one after the other. */
    val flatLines: List<RecognisedLine> get() = data.groups.flatMap { it.transactions }

    val current: RecognisedLine? get() = selectedId?.let { id -> flatLines.firstOrNull { it.id == id } }

    /** The group [current] belongs to: the fiche names its operation. */
    val currentGroup: RecognisedGroup? get() = selectedId?.let { id -> data.groups.firstOrNull { group -> group.transactions.any { it.id == id } } }

    /** 1-based place of [current] in [flatLines], 0 when none. */
    val position: Int get() = selectedId?.let { id -> flatLines.indexOfFirst { it.id == id } + 1 } ?: 0

    val total: Int get() = flatLines.size

    val hasPrevious: Boolean get() = position > 1

    val hasNext: Boolean get() = position in 1 until total
}

/**
 * « Reconnues à contrôler » (MAG-460): the lines a rule or a series qualified this month and the owner has not
 * checked, by operation, oldest first in each. A check is kept by the server, so the lines that left are not read
 * again: a gesture takes them out of the list as soon as the API has said yes, and a refusal leaves them in place.
 */
class RecognisedToControlViewModel(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val month: String = today.toString().take(7)

    private val _uiState = MutableStateFlow(RecognisedToControlUiState())
    val uiState: StateFlow<RecognisedToControlUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, loadError = null)

        viewModelScope.launch {
            loadCategories()
            transactionRepository.getRecognisedToControl(month)
                .onSuccess { data ->
                    _uiState.value = _uiState.value.withData(data).copy(isLoading = false, loaded = true, loadError = null)
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        loadError = failure.message ?: "Chargement impossible.",
                    )
                }
        }
    }

    // The headings do not change between two gestures: read once, kept. Only « Corriger » needs them.
    private suspend fun loadCategories() {
        if (_uiState.value.categories.isNotEmpty()) {
            return
        }
        categoryRepository.getCategories()
            .onSuccess { categories -> _uiState.value = _uiState.value.copy(categories = categories) }
    }

    /** Opens the fiche of [lineId]. */
    fun select(lineId: String) {
        if (_uiState.value.flatLines.none { it.id == lineId }) {
            return
        }
        _uiState.value = _uiState.value.copy(selectedId = lineId, ficheOpen = true)
    }

    /** Back to the list; the cursor stays on the line last open. */
    fun backToList() {
        _uiState.value = _uiState.value.copy(ficheOpen = false)
    }

    fun next() = move(+1)

    fun previous() = move(-1)

    private fun move(step: Int) {
        val state = _uiState.value
        if (state.position == 0) {
            return
        }
        val target = state.flatLines.getOrNull(state.position - 1 + step)?.id ?: return
        _uiState.value = state.copy(selectedId = target)
    }

    /**
     * Swaps in [newData]. The cursor stays where it is unless its line has left: then it goes to the line that followed
     * it (else the one before), and the fiche closes when nothing remains.
     */
    private fun RecognisedToControlUiState.withData(newData: RecognisedToControl): RecognisedToControlUiState {
        val id = selectedId ?: return copy(data = newData)
        val after = newData.groups.flatMap { it.transactions.map { line -> line.id } }
        if (id in after) {
            return copy(data = newData)
        }
        val moved = neighbourAfterRemoval(flatLines.map { it.id }, id, after)
        return copy(data = newData, selectedId = moved, ficheOpen = ficheOpen && moved != null)
    }

    /** « Valider » or, with [keep], « Tout à conserver »: the whole group is checked in one stroke and leaves the list. */
    fun controlGroup(groupKey: String, keep: Boolean) {
        val state = _uiState.value
        val group = state.data.groups.firstOrNull { it.key == groupKey } ?: return
        if (state.controllingKey != null) {
            return
        }

        // Marked before the coroutine starts: two taps land in the same frame, and one check must not be sent twice.
        _uiState.value = state.copy(controllingKey = groupKey, actionError = null, message = null)

        viewModelScope.launch {
            transactionRepository.controlRecognised(group.transactions.map { it.id }, keep)
                .onSuccess { result ->
                    _uiState.value = _uiState.value
                        .withData(_uiState.value.data.withoutGroup(groupKey))
                        .copy(controllingKey = null, message = controlSummary(group, result))
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(controllingKey = null, actionError = it.message ?: "Contrôle impossible.")
                }
        }
    }

    /** « À conserver » / « Superflue » on one spend: tapping the verdict the line already has takes it back. */
    fun judge(lineId: String, verdict: String) {
        val state = _uiState.value
        val line = state.data.groups.flatMap { it.transactions }.firstOrNull { it.id == lineId } ?: return
        if (!line.judgeable || state.judgingId != null) {
            return
        }
        val next = if (line.retrospect == verdict) RETROSPECT_UNRATED else verdict

        _uiState.value = state.copy(judgingId = lineId, actionError = null)

        viewModelScope.launch {
            transactionRepository.setRetrospect(lineId, next)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        judgingId = null,
                        data = _uiState.value.data.withJudgement(lineId, next),
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(judgingId = null, actionError = it.message ?: "Jugement impossible.")
                }
        }
    }

    /** « Corriger »: opens the qualification by hand, starting from the line's own category and what the operation set. */
    fun startCorrection(lineId: String) {
        val line = _uiState.value.data.groups.flatMap { it.transactions }.firstOrNull { it.id == lineId } ?: return
        _uiState.value = _uiState.value.copy(correction = RecognisedCorrection(line, line.categoryId), actionError = null)

        viewModelScope.launch {
            transactionRepository.getTransaction(lineId)
                .onSuccess { transaction ->
                    updateCorrection(lineId) { it.copy(qualification = transaction.qualification, isLoading = false) }
                }
                .onFailure {
                    // Saving over a qualification that could not be read would erase it: the line stays as it is.
                    if (_uiState.value.correction?.line?.id == lineId) {
                        _uiState.value = _uiState.value.copy(
                            correction = null,
                            actionError = it.message ?: "Correction impossible.",
                        )
                    }
                }
        }
    }

    fun setCorrectionCategory(categoryId: String?) = updateCorrection { it.copy(categoryId = categoryId) }

    fun setCorrectionQualification(qualification: Qualification) = updateCorrection { it.copy(qualification = qualification) }

    fun cancelCorrection() {
        if (_uiState.value.correction?.isSaving == true) {
            return
        }
        _uiState.value = _uiState.value.copy(correction = null)
    }

    /** Files the line by hand: it is no longer recognised by an operation, so it leaves the list. */
    fun saveCorrection() {
        val correction = _uiState.value.correction ?: return
        val categoryId = correction.categoryId
        if (!correction.canSave || categoryId == null) {
            return
        }

        updateCorrection { it.copy(isSaving = true, error = null) }

        viewModelScope.launch {
            transactionRepository.setQualification(correction.line.id, categoryId, correction.qualification)
                .onSuccess {
                    // The line leaves the list: the fiche shows the one that followed it.
                    _uiState.value = _uiState.value
                        .withData(_uiState.value.data.withoutLine(correction.line.id))
                        .copy(correction = null, message = "Ligne corrigée")
                }
                .onFailure { failure ->
                    updateCorrection { it.copy(isSaving = false, error = failure.message ?: "Correction impossible.") }
                }
        }
    }

    private fun updateCorrection(lineId: String? = null, change: (RecognisedCorrection) -> RecognisedCorrection) {
        val correction = _uiState.value.correction ?: return
        if (lineId != null && correction.line.id != lineId) {
            return
        }
        _uiState.value = _uiState.value.copy(correction = change(correction))
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun clearActionError() {
        _uiState.value = _uiState.value.copy(actionError = null)
    }
}
