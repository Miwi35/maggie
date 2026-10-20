package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.REVIEW_STEP_DONE
import com.maggie.app.data.model.RETROSPECT_UNRATED
import com.maggie.app.data.model.ReviewQueueItem
import com.maggie.app.data.model.ReviewStep
import com.maggie.app.data.model.UpcomingProjection
import com.maggie.app.data.repository.BudgetRepository
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.ReviewRepository
import com.maggie.app.data.repository.TransactionRepository
import com.maggie.app.data.repository.UpcomingRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class ReviewActivityUiState(
    /** The month reviewed, `YYYY-MM`. */
    val month: String,
    val isLoading: Boolean = true,
    /** A start that could not read the queue or the progress: nothing to show but « Réessayer ». */
    val loadError: String? = null,
    val step: ReviewStep = ReviewStep.REVIEW,
    /** The steps with nothing to do this month: never landed on, dimmed in the segment bar. */
    val skipped: Set<ReviewStep> = emptySet(),
    /** The lines of the Revue step, those already handled left out when the review started. */
    val queue: List<ReviewQueueItem> = emptyList(),
    /** The line on screen in [queue]. */
    val index: Int = 0,
    /** The lines left with « Valider » this month, as the API keeps them. */
    val handled: Set<String> = emptySet(),
    /** The month's review is over: the end screen. */
    val done: Boolean = false,
    val categories: List<Category> = emptyList(),
    val choosingCategory: Boolean = false,
    /** « Créer un filtre »: the rule draft written from the line on screen. */
    val ruleDraft: RuleDraftState? = null,
    /** A write in flight: the buttons wait, so one tap never sends twice. */
    val isBusy: Boolean = false,
    val isJudging: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val current: ReviewQueueItem? get() = if (step == ReviewStep.REVIEW && !done) queue.getOrNull(index) else null

    /** The footer validates something — a recognised line, a suggestion — or only passes the line. */
    val footerValidates: Boolean get() = current?.let { it.recognised || it.suggestion != null } ?: false

    /** « 3 / 12 ». */
    val counter: String get() = "${index + 1} / ${queue.size}"

    /** The step « Ensuite… » names: the next one with something to do, or null at the last. */
    val nextStep: ReviewStep? get() = stepAfter(step, skipped)

    val canGoBack: Boolean
        get() = !done && ((step == ReviewStep.REVIEW && index > 0) || stepBefore(step, skipped) != null)
}

internal fun stepAfter(step: ReviewStep, skipped: Set<ReviewStep>): ReviewStep? =
    generateSequence(step.next) { it.next }.firstOrNull { it !in skipped }

internal fun stepBefore(step: ReviewStep, skipped: Set<ReviewStep>): ReviewStep? =
    generateSequence(step.previous) { it.previous }.firstOrNull { it !in skipped }

/** The first step from [step] on (itself included) with something to do. */
private fun landingFrom(step: ReviewStep, skipped: Set<ReviewStep>): ReviewStep? =
    if (step !in skipped) step else stepAfter(step, skipped)

/** Nothing comes in the week nor in the month after. */
private fun UpcomingProjection.isEmpty(): Boolean =
    week.incomes.isEmpty() && week.expenses.isEmpty() && month.incomes.isEmpty() && month.expenses.isEmpty() && month.envelopes.isEmpty()

/**
 * Activité Comptes · la revue (MAG-456): the month's review as a full-screen wizard in five steps —
 * Revue (one line at a time), Bilan, Enveloppes, À venir, Projets.
 *
 * Where it stands lives on the API, not here: each line left with « Valider » is written as handled at
 * once and each change of step is written too, so quitting at the 3rd line and relaunching — here or on
 * the web — starts at the 4th. A step with nothing to do (no line left, no envelope, nothing to come) is
 * skipped without a tap. Bilan and Projets always have something to show.
 */
class ReviewActivityViewModel(
    private val reviewRepository: ReviewRepository,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    ruleRepository: CategorizationRuleRepository,
    private val budgetRepository: BudgetRepository,
    private val upcomingRepository: UpcomingRepository,
    today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewActivityUiState(month = YearMonth.from(today).toString()))
    val uiState: StateFlow<ReviewActivityUiState> = _uiState

    private val ruleDraftSession = RuleDraftSession(
        scope = viewModelScope,
        ruleRepository = ruleRepository,
        read = { _uiState.value.ruleDraft },
        write = { _uiState.value = _uiState.value.copy(ruleDraft = it) },
        applyToExisting = true,
        onSaved = {
            _uiState.value = _uiState.value.copy(message = "Filtre créé")
            _uiState.value.current?.let { leave(it.id) }
        },
    )

    init {
        start()
    }

    /** Starts, or resumes: the stored step, the lines not handled yet, the empty steps skipped. */
    fun start() {
        val month = _uiState.value.month
        _uiState.value = _uiState.value.copy(isLoading = true, loadError = null, error = null)

        viewModelScope.launch {
            coroutineScope {
                val progress = async { reviewRepository.getProgress(month) }
                val queue = async { reviewRepository.getQueue(month) }
                val adjustments = async { budgetRepository.getEnvelopeAdjustments(month) }
                val upcoming = async { upcomingRepository.getUpcoming() }
                val categories = async { categoryRepository.getCategories() }

                val stored = progress.await().getOrElse { return@coroutineScope failedStart(it) }
                val items = queue.await().getOrElse { return@coroutineScope failedStart(it) }.items
                val handled = stored.handledTransactionIds.toSet()
                val remaining = items.filter { it.id !in handled }

                // A step whose content could not be read is shown all the same: it says so itself.
                val skipped = buildSet {
                    if (remaining.isEmpty()) add(ReviewStep.REVIEW)
                    if (adjustments.await().getOrNull()?.envelopes?.isEmpty() == true) add(ReviewStep.ENVELOPES)
                    if (upcoming.await().getOrNull()?.isEmpty() == true) add(ReviewStep.UPCOMING)
                }

                val target = ReviewStep.of(stored.step) ?: ReviewStep.REVIEW
                val done = stored.step == REVIEW_STEP_DONE
                val landing = if (done) target else landingFrom(target, skipped)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    queue = remaining,
                    index = 0,
                    handled = handled,
                    skipped = skipped,
                    categories = categories.await().getOrDefault(emptyList()),
                    done = done,
                    step = landing ?: ReviewStep.PROJECTS,
                )

                // A stored step with nothing to do any more: the one it skips to is written too.
                when {
                    done -> Unit
                    landing == null -> writeDone()
                    landing != target -> moveTo(landing)
                }
            }
        }
    }

    private fun failedStart(failure: Throwable) {
        _uiState.value = _uiState.value.copy(isLoading = false, loadError = failure.message ?: "La revue n'a pas pu être lue.")
    }

    /**
     * The footer, then the next line: « Valider » checks a recognised line or files one under Maggie's suggestion;
     * « Passer » leaves a line with nothing to validate as it is (it stays in « À qualifier »). On the other steps,
     * « Suivant » moves to the next one, or ends the review after the last.
     */
    fun next() {
        val state = _uiState.value
        if (state.isBusy || state.isLoading || state.done) return
        val line = state.current
        when {
            state.step == ReviewStep.REVIEW && line != null && line.recognised ->
                actThenLeave(line.id) { transactionRepository.controlRecognised(listOf(line.id)).map { } }
            state.step == ReviewStep.REVIEW && line != null && line.suggestion != null -> sort(line, line.suggestion.categoryId)
            state.step == ReviewStep.REVIEW && line != null -> leave(line.id)
            else -> {
                _uiState.value = state.copy(isBusy = true, error = null)
                viewModelScope.launch { advanceStep() }
            }
        }
    }

    /** « Précédent »: the line before, or the step before (its last line for Revue). Nothing is written but the step. */
    fun previous() {
        val state = _uiState.value
        if (state.isBusy || !state.canGoBack) return
        if (state.step == ReviewStep.REVIEW && state.index > 0) {
            _uiState.value = state.copy(index = state.index - 1)
            return
        }
        val before = stepBefore(state.step, state.skipped) ?: return
        viewModelScope.launch { moveTo(before) }
    }

    /** « Recommencer depuis le Bilan », on the end screen of a month already reviewed. */
    fun restart() {
        val state = _uiState.value
        if (!state.done || state.isBusy) return
        viewModelScope.launch {
            if (moveTo(ReviewStep.BALANCE)) {
                _uiState.value = _uiState.value.copy(done = false)
            }
        }
    }

    /** « Accepter »: Maggie's suggestion, as it is. */
    fun accept() {
        val line = _uiState.value.current ?: return
        val suggestion = line.suggestion ?: return
        sort(line, suggestion.categoryId)
    }

    /** « Changer » on a line to qualify, « Corriger » on a recognised one: the full-screen category search. */
    fun openCategorySearch() {
        if (_uiState.value.current == null) return
        _uiState.value = _uiState.value.copy(choosingCategory = true)
    }

    fun closeCategorySearch() {
        _uiState.value = _uiState.value.copy(choosingCategory = false)
    }

    /** The category picked: a line to qualify is filed under it, a recognised one is corrected to it. Then the next line. */
    fun chooseCategory(categoryId: String) {
        _uiState.value = _uiState.value.copy(choosingCategory = false)
        val line = _uiState.value.current ?: return
        if (line.recognised) correct(line, categoryId) else sort(line, categoryId)
    }

    private fun sort(line: ReviewQueueItem, categoryId: String) =
        actThenLeave(line.id) { transactionRepository.sortToCategory(line.id, categoryId, alwaysForMerchant = false).map { } }

    // Corrected by hand, keeping what the operation had set: read first, as « Reconnues à contrôler » does.
    private fun correct(line: ReviewQueueItem, categoryId: String) = actThenLeave(line.id) {
        transactionRepository.getTransaction(line.id).mapCatching { transaction ->
            transactionRepository.setQualification(line.id, categoryId, transaction.qualification).getOrThrow()
            Unit
        }
    }

    /** « À conserver » / « Superflue »: the line stays on screen; tapping its own verdict takes it back. */
    fun judge(verdict: String) {
        val state = _uiState.value
        val line = state.current ?: return
        if (!line.judgeable || state.isJudging) return
        val next = if (line.retrospect == verdict) RETROSPECT_UNRATED else verdict
        _uiState.value = state.copy(isJudging = true, error = null)

        viewModelScope.launch {
            transactionRepository.setRetrospect(line.id, next)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isJudging = false,
                        queue = _uiState.value.queue.map { if (it.id == line.id) it.copy(retrospect = next) else it },
                    )
                }
                .onFailure { _uiState.value = _uiState.value.copy(isJudging = false, error = it.message ?: "Jugement impossible.") }
        }
    }

    /** « Créer un filtre »: the rule draft, filled from the line on screen. */
    fun openRuleDraft() {
        val line = _uiState.value.current ?: return
        ruleDraftSession.open(line.label, line.amountCents, line.suggestion?.categoryId ?: line.categoryId, Qualification.NONE)
    }

    fun closeRuleDraft() = ruleDraftSession.close()

    fun changeRulePattern(text: String) = ruleDraftSession.changePattern(text)

    fun changeRuleAmount(amount: String, tolerance: String) = ruleDraftSession.changeAmount(amount, tolerance)

    fun changeRuleCategory(categoryId: String?) = ruleDraftSession.changeCategory(categoryId)

    fun changeRuleQualification(qualification: Qualification) = ruleDraftSession.changeQualification(qualification)

    fun saveRule() = ruleDraftSession.save()

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    // One write at a time, marked before the coroutine starts: two taps land in the same frame.
    private fun actThenLeave(lineId: String, action: suspend () -> Result<Unit>) {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(isBusy = true, error = null)
        viewModelScope.launch {
            action()
                .onSuccess { handle(lineId) }
                .onFailure { _uiState.value = _uiState.value.copy(isBusy = false, error = it.message ?: "Action impossible.") }
        }
    }

    /** The line is left as it is (after a rule that filed it): handled, then the next. */
    private fun leave(lineId: String) = actThenLeave(lineId) { Result.success(Unit) }

    // Written as handled before the next line comes: a relaunch must never show it again.
    private suspend fun handle(lineId: String) {
        val month = _uiState.value.month
        reviewRepository.markHandled(month, lineId)
            .onSuccess {
                val now = _uiState.value
                if (now.index + 1 < now.queue.size) {
                    _uiState.value = now.copy(isBusy = false, handled = now.handled + lineId, index = now.index + 1)
                } else {
                    _uiState.value = now.copy(handled = now.handled + lineId)
                    advanceStep()
                }
            }
            .onFailure { _uiState.value = _uiState.value.copy(isBusy = false, error = it.message ?: "Enregistrement impossible.") }
    }

    private suspend fun advanceStep() {
        val state = _uiState.value
        val after = stepAfter(state.step, state.skipped)
        if (after == null) writeDone() else moveTo(after)
    }

    /** Writes [step], then shows it; a refused write keeps the step on screen. */
    private suspend fun moveTo(step: ReviewStep): Boolean {
        val state = _uiState.value
        _uiState.value = state.copy(isBusy = true, error = null)
        return reviewRepository.moveTo(state.month, step.value).fold(
            onSuccess = {
                val now = _uiState.value
                val index = when {
                    step != ReviewStep.REVIEW -> now.index
                    // Back into Revue from the step after: its last line.
                    now.step != ReviewStep.REVIEW -> (now.queue.size - 1).coerceAtLeast(0)
                    else -> now.index
                }
                _uiState.value = now.copy(isBusy = false, step = step, index = index)
                true
            },
            onFailure = {
                _uiState.value = _uiState.value.copy(isBusy = false, error = it.message ?: "Enregistrement impossible.")
                false
            },
        )
    }

    private suspend fun writeDone() {
        val state = _uiState.value
        _uiState.value = state.copy(isBusy = true, error = null)
        reviewRepository.moveTo(state.month, REVIEW_STEP_DONE)
            .onSuccess { _uiState.value = _uiState.value.copy(isBusy = false, done = true) }
            .onFailure { _uiState.value = _uiState.value.copy(isBusy = false, error = it.message ?: "Enregistrement impossible.") }
    }
}
