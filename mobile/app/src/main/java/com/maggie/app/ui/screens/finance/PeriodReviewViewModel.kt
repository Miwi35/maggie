package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.PeriodBalance
import com.maggie.app.data.model.PeriodPlan
import com.maggie.app.data.model.PlanCategory
import com.maggie.app.data.model.PlanItem
import com.maggie.app.data.model.PlanRefusedException
import com.maggie.app.data.model.REVIEW_LEVEL_YEAR
import com.maggie.app.data.repository.ReviewRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal

/** An envelope of an event with no category, written by name: « Escapade » 450 €. */
data class OneOffDraft(val name: String, val amount: String)

/** A plan refused before anything was written: [message], and [line] when one line is to blame. */
data class PlanRefusal(val message: String, val line: String?)

/** The two steps that close a period (MAG-462): its bilan, then the plan of the next one. */
data class PeriodReviewUiState(
    val balance: PeriodBalance? = null,
    val balanceError: String? = null,
    val plan: PeriodPlan? = null,
    val planError: String? = null,
    /** The amounts typed, by category id; a category left alone is proposed at the plan's figure. */
    val edits: Map<String, String> = emptyMap(),
    val oneOffs: List<OneOffDraft> = emptyList(),
    val draftName: String = "",
    val draftAmount: String = "",
    val applying: Boolean = false,
    val applied: Boolean = false,
    val refusal: PlanRefusal? = null,
) {
    private val yearly: Boolean get() = plan?.period?.level == REVIEW_LEVEL_YEAR
    private val months: Int get() = plan?.period?.months ?: 1

    /** The categories the plan shows: one already set, or one the period behind spent on. */
    val rows: List<PlanCategory>
        get() = plan?.categories.orEmpty().filter { it.envelopeCents != null || it.suggestedCents > 0 }

    /** What is typed for [category]: the edit, else what the plan proposes. */
    fun amountOf(category: PlanCategory): String = edits[category.categoryId] ?: amountText(proposedCents(category))

    /** What the plan proposes for [category]: its envelope spread over the months, else the period behind. */
    fun proposedCents(category: PlanCategory): Int = when {
        category.envelopeCents != null -> if (yearly) category.envelopeCents else Math.round(category.envelopeCents.toDouble() / months).toInt()
        yearly -> category.suggestedCents
        else -> category.suggestedMonthlyCents
    }

    /**
     * What « Appliquer le plan » writes, with the name of each line for a refusal. A line already set and left
     * alone is not sent again: the plan would replace its seasons.
     */
    val lines: List<Pair<String, PlanItem>>
        get() = buildList {
            for (category in rows) {
                val edited = edits[category.categoryId]
                if (edited == null && category.envelopeCents != null) continue
                val cents = amountCents(edited ?: amountText(proposedCents(category)))
                if (cents <= 0) continue
                add(category.categoryName to PlanItem(if (yearly) "annual" else "monthly", cents, category.currency, categoryId = category.categoryId))
            }
            oneOffs.forEach { add(it.name to PlanItem("one_off", amountCents(it.amount), name = it.name)) }
        }

    val canAddOneOff: Boolean get() = draftName.isNotBlank() && amountCents(draftAmount) > 0
}

/** « 12,5 » or « 12.5 » to cents; 0 for anything that is not a positive amount. */
internal fun amountCents(text: String): Int {
    val value = text.replace(',', '.').trim().toDoubleOrNull() ?: return 0
    return if (value.isFinite() && value > 0) Math.round(value * 100).toInt() else 0
}

/** 12500 to « 125 », 1250 to « 12.5 »: the figure an amount field starts with. */
internal fun amountText(cents: Int): String = BigDecimal(cents).movePointLeft(2).stripTrailingZeros().toPlainString()

class PeriodReviewViewModel(
    private val reviewRepository: ReviewRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PeriodReviewUiState())
    val uiState: StateFlow<PeriodReviewUiState> = _uiState

    private var planKey: String? = null

    /** Reads the bilan of the period the review of [month] closes. */
    fun loadBalance(month: String) {
        _uiState.value = _uiState.value.copy(balanceError = null)
        viewModelScope.launch {
            reviewRepository.getPeriodBalance(month)
                .onSuccess { _uiState.value = _uiState.value.copy(balance = it) }
                .onFailure { _uiState.value = _uiState.value.copy(balanceError = it.message ?: "Le bilan n'a pas pu être lu.") }
        }
    }

    /** Reads the plan of [periodKey]; the same period read twice keeps what was typed. */
    fun loadPlan(periodKey: String) {
        if (planKey != periodKey) {
            planKey = periodKey
            _uiState.value = _uiState.value.copy(
                plan = null,
                edits = emptyMap(),
                oneOffs = emptyList(),
                applied = false,
                refusal = null,
            )
        }
        _uiState.value = _uiState.value.copy(planError = null)
        viewModelScope.launch {
            reviewRepository.getPeriodPlan(periodKey)
                .onSuccess { _uiState.value = _uiState.value.copy(plan = it) }
                .onFailure { _uiState.value = _uiState.value.copy(planError = it.message ?: "Le plan n'a pas pu être chargé.") }
        }
    }

    fun edit(categoryId: String, text: String) {
        _uiState.value = _uiState.value.copy(edits = _uiState.value.edits + (categoryId to text))
    }

    fun changeDraftName(text: String) {
        _uiState.value = _uiState.value.copy(draftName = text)
    }

    fun changeDraftAmount(text: String) {
        _uiState.value = _uiState.value.copy(draftAmount = text)
    }

    fun addOneOff() {
        val state = _uiState.value
        if (!state.canAddOneOff) return
        _uiState.value = state.copy(
            oneOffs = state.oneOffs + OneOffDraft(state.draftName.trim(), state.draftAmount),
            draftName = "",
            draftAmount = "",
        )
    }

    fun removeOneOff(index: Int) {
        val state = _uiState.value
        _uiState.value = state.copy(oneOffs = state.oneOffs.filterIndexed { at, _ -> at != index })
    }

    /** « Appliquer le plan »: every line written, or — one of them refused — none. */
    fun apply() {
        val state = _uiState.value
        val key = planKey ?: return
        if (state.applying || state.lines.isEmpty()) return
        val lines = state.lines
        _uiState.value = state.copy(applying = true, applied = false, refusal = null)

        viewModelScope.launch {
            reviewRepository.applyPeriodPlan(key, lines.map { it.second })
                .onSuccess {
                    _uiState.value = _uiState.value.copy(applying = false, applied = true, edits = emptyMap(), oneOffs = emptyList())
                    loadPlan(key)
                }
                .onFailure { failure ->
                    val line = (failure as? PlanRefusedException)?.index?.let { lines.getOrNull(it)?.first }
                    _uiState.value = _uiState.value.copy(
                        applying = false,
                        refusal = PlanRefusal(failure.message ?: "Le plan n'a pas pu être appliqué.", line),
                    )
                }
        }
    }
}
