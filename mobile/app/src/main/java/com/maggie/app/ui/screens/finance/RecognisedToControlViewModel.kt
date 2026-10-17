package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.RETROSPECT_UNRATED
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
) {
    val isEmpty: Boolean get() = loaded && data.groups.isEmpty()
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
                    _uiState.value = _uiState.value.copy(isLoading = false, loaded = true, loadError = null, data = data)
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
                    _uiState.value = _uiState.value.copy(
                        controllingKey = null,
                        data = _uiState.value.data.withoutGroup(groupKey),
                        message = controlSummary(group, result),
                    )
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
                    _uiState.value = _uiState.value.copy(
                        correction = null,
                        data = _uiState.value.data.withoutLine(correction.line.id),
                        message = "Ligne corrigée",
                    )
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
