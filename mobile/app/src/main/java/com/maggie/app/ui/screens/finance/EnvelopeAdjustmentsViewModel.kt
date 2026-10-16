package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.AdjustmentLine
import com.maggie.app.data.model.EnvelopeAdjustment
import com.maggie.app.data.model.EnvelopeAdjustments
import com.maggie.app.data.model.EnvelopeAdjustmentsRequest
import com.maggie.app.data.model.SPLIT_STEP_CENTS
import com.maggie.app.data.repository.BudgetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** The step of the ± buttons and of the slider: a whole euro, like the ruler of a mother (MAG-413). */
const val ADJUSTMENT_STEP_CENTS = SPLIT_STEP_CENTS

data class EnvelopeAdjustmentsUiState(
    /** The month read; the amounts proposed are for the next one. */
    val month: YearMonth,
    val adjustments: EnvelopeAdjustments? = null,
    /**
     * What was set by hand for the next month, by category, and not saved yet. Only amounts that
     * differ from the stored one are kept: the draft is what « Enregistrer » would write.
     */
    val draft: Map<String, Int> = emptyMap(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    /** The amount shown for the next month: the draft if there is one, else what is budgeted. */
    fun amountFor(line: EnvelopeAdjustment): Int = draft[line.categoryId] ?: line.targetAmountCents

    val hasDraft: Boolean get() = draft.isNotEmpty()

    /** The suggestions still to take: not overridden by hand (the draft already says what the owner wants). */
    val pendingSuggestions: List<EnvelopeAdjustment>
        get() = adjustments?.envelopes.orEmpty().filter { it.suggestion != null && it.categoryId !in draft }
}

/**
 * « Ajuster » (MAG-461): each monthly envelope of a month read against what was really spent, with
 * the amount of the month after. Nothing is written without a gesture: ± and the slider fill a
 * draft, « Enregistrer » writes it; « Appliquer » writes one suggestion, « Tout appliquer » all of
 * them. A refused write changes nothing — the draft stays, and so does what is on screen.
 */
class EnvelopeAdjustmentsViewModel(
    private val budgetRepository: BudgetRepository,
    initialMonth: YearMonth = YearMonth.from(LocalDate.now()),
) : ViewModel() {

    private val _uiState = MutableStateFlow(EnvelopeAdjustmentsUiState(month = initialMonth))
    val uiState: StateFlow<EnvelopeAdjustmentsUiState> = _uiState

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    // A newer read replaces the one in flight: a slow answer for an earlier month must not overwrite it.
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val month = _uiState.value.month
            try {
                val adjustments = budgetRepository.getEnvelopeAdjustments(month.toString()).getOrThrow()
                _uiState.value = _uiState.value.copy(adjustments = adjustments, isLoading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: READ_FAILED)
            }
        }
    }

    /** Reads another month; what was set by hand for the previous one is dropped. */
    fun shiftMonth(months: Long) {
        val state = _uiState.value
        _uiState.value = state.copy(month = state.month.plusMonths(months), adjustments = null, draft = emptyMap(), message = null)
        refresh()
    }

    /** ± : moves the next month's amount of [categoryId] by [deltaCents], never below zero. */
    fun step(categoryId: String, deltaCents: Int) {
        val state = _uiState.value
        val line = state.adjustments?.envelopes?.firstOrNull { it.categoryId == categoryId } ?: return
        setAmount(categoryId, state.amountFor(line) + deltaCents)
    }

    /** The slider: sets the next month's amount of [categoryId] to [amountCents]. */
    fun setAmount(categoryId: String, amountCents: Int) {
        val state = _uiState.value
        val line = state.adjustments?.envelopes?.firstOrNull { it.categoryId == categoryId } ?: return
        if (state.isSaving) return
        val amount = amountCents.coerceAtLeast(0)
        val draft = if (amount == line.targetAmountCents) state.draft - categoryId else state.draft + (categoryId to amount)
        _uiState.value = state.copy(draft = draft, message = null)
    }

    /** Drops what was set by hand for [categoryId]. */
    fun resetDraft(categoryId: String) {
        _uiState.value = _uiState.value.let { it.copy(draft = it.draft - categoryId) }
    }

    /** « Appliquer »: writes the suggestion of [categoryId] as it is proposed — a season if it is one. */
    fun apply(categoryId: String) {
        val line = _uiState.value.adjustments?.envelopes?.firstOrNull { it.categoryId == categoryId } ?: return
        val suggestion = line.suggestion ?: return
        write(listOf(AdjustmentLine(categoryId, suggestion.proposedCents, suggestion.seasonal != null)))
    }

    /** « Tout appliquer »: every suggestion not overridden by hand; the draft is left for « Enregistrer ». */
    fun applyAll() {
        val lines = _uiState.value.pendingSuggestions.mapNotNull { line ->
            line.suggestion?.let { AdjustmentLine(line.categoryId, it.proposedCents, it.seasonal != null) }
        }
        if (lines.isEmpty()) return
        write(lines)
    }

    /** « Enregistrer »: writes everything set by hand with ± and the slider, as plain amounts. */
    fun save() {
        val lines = _uiState.value.draft.map { (categoryId, cents) -> AdjustmentLine(categoryId, cents) }
        if (lines.isEmpty()) return
        write(lines)
    }

    // One POST; on success the written categories leave the draft and the list is read again.
    private fun write(lines: List<AdjustmentLine>) {
        val state = _uiState.value
        if (state.isSaving) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null, message = null)
            try {
                val result = budgetRepository.applyEnvelopeAdjustments(
                    EnvelopeAdjustmentsRequest(state.month.toString(), lines),
                ).getOrThrow()
                val written = lines.map { it.categoryId }.toSet()
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    draft = _uiState.value.draft - written,
                    message = appliedMessage(result.applied.size.takeIf { it > 0 } ?: lines.size),
                )
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing was written: the draft and the list stay as they were.
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message ?: WRITE_FAILED)
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

private fun appliedMessage(count: Int) =
    if (count == 1) "1 enveloppe ajustée" else "$count enveloppes ajustées"

private const val READ_FAILED = "Impossible de lire les enveloppes."
private const val WRITE_FAILED = "Rien n'a été enregistré."
