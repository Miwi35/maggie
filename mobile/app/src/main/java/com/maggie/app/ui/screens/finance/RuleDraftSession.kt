package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.CategorizationRuleCreateRequest
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.RulePreviewRequest
import com.maggie.app.data.model.categoryIri
import com.maggie.app.data.model.ruleDirectionFor
import com.maggie.app.data.repository.CategorizationRuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * « Créer un filtre »: the draft of a rule written from a line, its live preview and its save, shared by the
 * transaction sheet and the « À qualifier » card. The owner's screen state holds the draft; this reads and writes it.
 */
internal class RuleDraftSession(
    private val scope: CoroutineScope,
    private val ruleRepository: CategorizationRuleRepository,
    private val read: () -> RuleDraftState?,
    private val write: (RuleDraftState?) -> Unit,
    private val applyToExisting: Boolean = false,
    private val onSaved: () -> Unit,
) {
    private var previewJob: Job? = null

    /** Opens the draft filled from a line: its label, the direction of its amount, its category. */
    fun open(label: String, amountCents: Int, categoryId: String?, qualification: Qualification) {
        val draft = RuleDraftState(
            labelPattern = label.trim(),
            direction = ruleDirectionFor(amountCents),
            categoryId = categoryId,
            qualification = qualification,
        )
        write(draft)
        preview(draft, delayMs = 0)
    }

    fun close() {
        previewJob?.cancel()
        write(null)
    }

    /** The pattern changed: the list of matching lines follows once typing pauses. */
    fun changePattern(text: String) {
        val draft = read() ?: return
        val changed = draft.copy(labelPattern = text, error = null)
        write(changed)
        preview(changed, delayMs = PREVIEW_DELAY_MS)
    }

    /** The amount (or its tolerance) changed: the rule may now hold on direction and amount alone. */
    fun changeAmount(amount: String, tolerance: String) {
        val draft = read() ?: return
        val changed = draft.copy(amount = amount, tolerance = tolerance, error = null)
        write(changed)
        preview(changed, delayMs = PREVIEW_DELAY_MS)
    }

    fun changeCategory(categoryId: String?) {
        val draft = read() ?: return
        val changed = draft.copy(categoryId = categoryId, error = null)
        write(changed)
        preview(changed, delayMs = 0)
    }

    fun changeQualification(qualification: Qualification) {
        update { it.copy(qualification = qualification, error = null) }
    }

    // A newer request cancels the older one: a slow answer for « CARRE » never overwrites « CARREFOUR ».
    private fun preview(draft: RuleDraftState, delayMs: Long) {
        previewJob?.cancel()
        if (draft.problem != null) {
            update { it.copy(preview = null, isPreviewing = false) }
            return
        }
        update { it.copy(isPreviewing = true) }
        previewJob = scope.launch {
            delay(delayMs)
            val result = ruleRepository.previewRule(
                RulePreviewRequest(
                    labelPattern = draft.labelPattern.trim().ifEmpty { null },
                    direction = draft.direction,
                    minAmountCents = draft.amountBounds?.first,
                    maxAmountCents = draft.amountBounds?.second,
                    categoryId = draft.categoryId,
                ),
            )
            result
                .onSuccess { preview -> update { it.copy(preview = preview, isPreviewing = false, error = null) } }
                .onFailure { e -> update { it.copy(isPreviewing = false, error = e.message ?: "L'aperçu est indisponible pour le moment.") } }
        }
    }

    /** Saves the rule and closes the draft, then tells the owner so it reloads what the rule filed. */
    fun save() {
        val draft = read() ?: return
        val categoryId = draft.categoryId ?: return
        if (draft.problem != null || draft.isSaving) return
        previewJob?.cancel()
        scope.launch {
            update { it.copy(isSaving = true, isPreviewing = false, error = null) }
            ruleRepository.createRule(
                CategorizationRuleCreateRequest(
                    labelPattern = draft.labelPattern.trim().ifEmpty { null },
                    category = categoryIri(categoryId),
                    qualification = draft.qualification,
                    direction = draft.direction,
                    minAmountCents = draft.amountBounds?.first,
                    maxAmountCents = draft.amountBounds?.second,
                    applyToExisting = applyToExisting,
                ),
            )
                .onSuccess {
                    write(null)
                    onSaved()
                }
                .onFailure { e -> update { it.copy(isSaving = false, error = e.message) } }
        }
    }

    private fun update(change: (RuleDraftState) -> RuleDraftState) {
        val draft = read() ?: return
        write(change(draft))
    }

    private companion object {
        const val PREVIEW_DELAY_MS = 300L
    }
}
