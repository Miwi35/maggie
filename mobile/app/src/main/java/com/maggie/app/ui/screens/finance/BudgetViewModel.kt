package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.EnvelopeCreateRequest
import com.maggie.app.data.api.EnvelopeSplitChild
import com.maggie.app.data.api.EnvelopeSplitRequest
import com.maggie.app.data.api.EnvelopeUpdateRequest
import com.maggie.app.data.api.OneOffEnvelopeCreateRequest
import com.maggie.app.data.api.RollOverRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.BudgetLine
import com.maggie.app.data.model.BudgetStatus
import com.maggie.app.data.model.SplitShares
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.CategoryRow
import com.maggie.app.data.model.CategoryTree
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.visibleBudgetLines
import com.maggie.app.data.model.NestedBudgetLine
import com.maggie.app.data.model.ONE_OFF_MODE
import com.maggie.app.data.model.daughtersOf
import com.maggie.app.data.model.monthLabel
import com.maggie.app.data.model.moveBoundary
import com.maggie.app.data.model.nestBudgetLines
import com.maggie.app.data.model.overlappingMonth
import com.maggie.app.data.model.splitSharesOf
import com.maggie.app.data.repository.BudgetRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.TransactionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A mother's ruler being edited: nothing is written until it is saved. */
data class SplitEditor(
    val motherId: String,
    val shares: SplitShares,
    val isSaving: Boolean = false,
)

/** What the tablet's right pane is editing (MAG-419). */
sealed interface EditorTarget {
    /** A category with no envelope in the period shown: the editor creates one. */
    data class NewForCategory(val categoryId: String) : EditorTarget

    /** An envelope that exists: the monthly one of a category, an annual one, a one-off one. */
    data class Existing(val envelopeId: String) : EditorTarget

    /** A one-off envelope (Hellfest 2026) not yet created. */
    data object NewOneOff : EditorTarget
}

/**
 * The editor open on the tablet. [stored] is the envelope as the API keeps it — its base amount,
 * its seasons, its threshold — because a budget-status line carries the amount of the *season* of
 * the month, which must not be written back as the base one.
 */
data class EnvelopeEditor(
    val target: EditorTarget,
    val stored: Envelope? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
)

/** A category of the tree and its envelope of the period shown, if it has one. */
data class BudgetTreeRow(val row: CategoryRow, val line: BudgetLine?)

/**
 * The lines an envelope weighs on, open on the phone: its category and the sub-categories
 * it takes along, over its month (the year, for an annual envelope).
 */
data class EnvelopeDetail(
    val envelopeId: String,
    val title: String,
    val categoryIds: List<String>,
    val year: Int,
    val month: Int?,
    val transactions: List<Transaction> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

data class BudgetUiState(
    val status: BudgetStatus? = null,
    val categories: List<Category> = emptyList(),
    val year: Int = LocalDate.now().year,
    val month: Int = LocalDate.now().monthValue,
    val isLoading: Boolean = false,
    val isRollingOver: Boolean = false,
    val rollOverMessage: String? = null,
    val splitEditor: SplitEditor? = null,
    val editor: EnvelopeEditor? = null,
    // Which envelopes are unfolded, by NestedBudgetLine.expansionKey: all are folded at first.
    val expanded: Set<String> = emptySet(),
    val detail: EnvelopeDetail? = null,
    val error: String? = null,
) {
    /**
     * Every category, each with its monthly envelope of the month shown: the tablet's tree, three
     * levels deep, every node open. A category with no envelope is a row all the same.
     */
    val treeRows: List<BudgetTreeRow>
        get() {
            val lines = status?.budgets.orEmpty().associateBy { it.categoryId }
            return CategoryTree(categories).rows(categories.map { it.id }.toSet())
                .map { BudgetTreeRow(it, lines[it.category.id]) }
        }

    /** The category the editor is about, to highlight its row in the tree. */
    val selectedCategoryId: String?
        get() = when (val target = editor?.target) {
            is EditorTarget.NewForCategory -> target.categoryId
            is EditorTarget.Existing -> editor?.stored?.category?.substringAfterLast('/')
                ?: status?.budgets?.firstOrNull { it.id == target.envelopeId }?.categoryId
            else -> null
        }

    /** The envelope the editor is about, to highlight its row in the « Hors budget mensuel » group. */
    val selectedEnvelopeId: String?
        get() = (editor?.target as? EditorTarget.Existing)?.envelopeId

    /** The envelopes directly below a mother: what her ruler shares. */
    fun daughtersOf(mother: BudgetLine): List<BudgetLine> =
        daughtersOf(mother, status?.budgets.orEmpty())

    /** The envelopes in tree order, each nested under its mother. */
    val nestedBudgets: List<NestedBudgetLine>
        get() = nestBudgetLines(status?.budgets.orEmpty())

    /** The annual envelopes, apart from the month's budget, nested the same way. */
    val nestedAnnualBudgets: List<NestedBudgetLine>
        get() = nestBudgetLines(status?.outsideMonthlyBudget?.annual.orEmpty())

    /** The month's envelopes as drawn: the ones nested in a folded envelope are left out. */
    val visibleBudgets: List<NestedBudgetLine>
        get() = visibleBudgetLines(nestedBudgets, expanded)

    val visibleAnnualBudgets: List<NestedBudgetLine>
        get() = visibleBudgetLines(nestedAnnualBudgets, expanded)
}

class BudgetViewModel(
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: TransactionRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        BudgetUiState(year = today.year, month = today.monthValue),
    )
    val uiState: StateFlow<BudgetUiState> = _uiState

    init {
        refresh()
        loadCategories()
        subscribeToMercure()
    }

    // An envelope or a spend changing anywhere moves the sums: reload them, and the open envelope's lines.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            listOf(MercureTopics.ENVELOPES, MercureTopics.TRANSACTIONS).forEach { collection ->
                launch {
                    mercureService.subscribe(MercureTopics.userScoped(userId, collection))
                        .catch { /* SSE reconnects automatically */ }
                        .coalesced()
                        .collect {
                            refresh()
                            _uiState.value.detail?.let { loadDetail(it) }
                        }
                }
            }
        }
    }

    /** Unfolds or folds the envelopes nested in [line]'s category. */
    fun toggleExpanded(key: String) {
        val state = _uiState.value
        _uiState.value = state.copy(
            expanded = if (key in state.expanded) state.expanded - key else state.expanded + key,
        )
    }

    /** Opens the lines [line] weighs on this period, sub-categories included. */
    fun openDetail(line: BudgetLine, title: String) {
        val state = _uiState.value
        val categoryIds = listOf(line.categoryId) + CategoryTree(state.categories).descendants(line.categoryId)
        val detail = EnvelopeDetail(
            envelopeId = line.id,
            title = title,
            categoryIds = categoryIds,
            year = line.year,
            month = if (line.mode == "annual") null else line.month,
        )
        _uiState.value = state.copy(detail = detail)
        loadDetail(detail)
    }

    fun closeDetail() {
        detailJob?.cancel()
        _uiState.value = _uiState.value.copy(detail = null)
    }

    private var detailJob: Job? = null
    private var refreshJob: Job? = null

    private fun loadDetail(detail: EnvelopeDetail) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            transactionRepository.getEnvelopeSpends(detail.categoryIds, detail.year, detail.month)
                .onSuccess { lines ->
                    updateDetail(detail.envelopeId) { it.copy(transactions = lines, isLoading = false, error = null) }
                }
                .onFailure { e ->
                    updateDetail(detail.envelopeId) { it.copy(isLoading = false, error = e.message ?: DETAIL_FAILED) }
                }
        }
    }

    // The envelope may have been closed, or another opened, while the lines were being read.
    private fun updateDetail(envelopeId: String, change: (EnvelopeDetail) -> EnvelopeDetail) {
        val current = _uiState.value.detail?.takeIf { it.envelopeId == envelopeId } ?: return
        _uiState.value = _uiState.value.copy(detail = change(current))
    }

    private fun loadCategories() {
        viewModelScope.launch {
            categoryRepository.getCategories()
                .onSuccess { categories ->
                    _uiState.value = _uiState.value.copy(categories = categories.sortedBy { it.name })
                }
        }
    }

    // A newer read replaces the one in flight: a slow answer for an earlier period must not overwrite it.
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val state = _uiState.value
            try {
                val status = budgetRepository.getBudgetStatus(state.year, state.month).getOrThrow()
                _uiState.value = _uiState.value.copy(status = status, isLoading = false)
                reconcileEditor()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    /**
     * Move the shown period by a number of months, then reload it. The editor closes: it edits the
     * envelope of the period shown, and what was typed for one month must not land on another.
     */
    fun shiftPeriod(months: Long) {
        val state = _uiState.value
        val shifted = LocalDate.of(state.year, state.month, 1).plusMonths(months)
        _uiState.value = state.copy(year = shifted.year, month = shifted.monthValue, editor = null, splitEditor = null)
        refresh()
    }

    /**
     * Touching a category of the tree: its envelope of the period if it has one (loaded as the API
     * stores it), else the editor that creates one.
     */
    fun selectCategory(categoryId: String) {
        val line = _uiState.value.status?.budgets?.firstOrNull { it.categoryId == categoryId }
        if (line != null) {
            selectEnvelope(line.id)
        } else {
            _uiState.value = _uiState.value.copy(
                editor = EnvelopeEditor(EditorTarget.NewForCategory(categoryId)),
                splitEditor = null,
            )
        }
    }

    /** Touching an annual or a one-off envelope: the editor opens on the envelope as the API stores it. */
    fun selectEnvelope(id: String) {
        val target = EditorTarget.Existing(id)
        _uiState.value = _uiState.value.copy(
            editor = EnvelopeEditor(target, isLoading = true),
            splitEditor = null,
        )
        viewModelScope.launch {
            budgetRepository.getEnvelope(id)
                .onSuccess { stored ->
                    val editor = _uiState.value.editor
                    if (editor?.target == target) {
                        _uiState.value = _uiState.value.copy(editor = editor.copy(stored = stored, isLoading = false))
                    }
                }
                .onFailure { e ->
                    if (_uiState.value.editor?.target == target) {
                        _uiState.value = _uiState.value.copy(editor = null, error = e.message)
                    }
                }
        }
    }

    fun startNewOneOff() {
        _uiState.value = _uiState.value.copy(editor = EnvelopeEditor(EditorTarget.NewOneOff), splitEditor = null)
    }

    fun closeEditor() {
        _uiState.value = _uiState.value.copy(editor = null, splitEditor = null)
    }

    /**
     * A creation editor left open while the envelope appeared (a roll-over, another device) would
     * create a duplicate the API refuses: it becomes the editor of that envelope.
     */
    private fun reconcileEditor() {
        val state = _uiState.value
        val target = state.editor?.target as? EditorTarget.NewForCategory ?: return
        if (state.status?.budgets?.any { it.categoryId == target.categoryId } == true) selectCategory(target.categoryId)
    }

    /** Copies the previous month's envelopes onto the period being shown. */
    fun rollOverPreviousPeriod() {
        val state = _uiState.value
        val previous = LocalDate.of(state.year, state.month, 1).minusMonths(1)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRollingOver = true, rollOverMessage = null)
            try {
                val result = budgetRepository.rollOverEnvelopes(
                    RollOverRequest(
                        fromYear = previous.year,
                        fromMonth = previous.monthValue,
                        year = state.year,
                        month = state.month,
                    ),
                ).getOrThrow()

                _uiState.value = _uiState.value.copy(
                    isRollingOver = false,
                    rollOverMessage = if (result.created > 0) {
                        "${result.created} enveloppe(s) reconduite(s)"
                    } else {
                        "Rien à reconduire : les enveloppes sont déjà en place"
                    },
                )
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isRollingOver = false, error = e.message)
            }
        }
    }

    /** Opens a mother's ruler for editing (the tablet; the phone only reads it). */
    fun startSplit(motherId: String) {
        val state = _uiState.value
        val mother = state.status?.budgets?.firstOrNull { it.id == motherId } ?: return
        val daughters = state.daughtersOf(mother)
        if (daughters.isEmpty()) return

        _uiState.value = state.copy(
            splitEditor = SplitEditor(motherId, splitSharesOf(daughters.map { it.amountCents }, mother.amountCents)),
        )
    }

    /** Moves the boundary after daughter [index]; the euros it trades are bounded by its neighbours. */
    fun moveSplitBoundary(index: Int, deltaCents: Int) {
        val editor = _uiState.value.splitEditor ?: return
        if (editor.isSaving) return
        _uiState.value = _uiState.value.copy(
            splitEditor = editor.copy(shares = editor.shares.moveBoundary(index, deltaCents)),
        )
    }

    fun cancelSplit() {
        _uiState.value = _uiState.value.copy(splitEditor = null)
    }

    /** Saves every daughter at once; a refusal (400) changes nothing. */
    fun saveSplit() {
        val state = _uiState.value
        val editor = state.splitEditor ?: return
        if (editor.isSaving) return
        val mother = state.status?.budgets?.firstOrNull { it.id == editor.motherId } ?: return
        val children = state.daughtersOf(mother).mapIndexed { i, daughter ->
            EnvelopeSplitChild(daughter.id, editor.shares.amounts[i])
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(splitEditor = editor.copy(isSaving = true))
            budgetRepository.splitEnvelope(editor.motherId, EnvelopeSplitRequest(children))
                .onSuccess {
                    _uiState.value = _uiState.value.copy(splitEditor = null)
                    refresh()
                }
                .onFailure { e ->
                    // Nothing was written, so the amounts on screen are still the stored ones.
                    val refused = e is ApiRefusalException && e.status == 400
                    _uiState.value = _uiState.value.copy(
                        splitEditor = null,
                        error = if (refused) SPLIT_REFUSED else e.message,
                    )
                }
        }
    }

    fun clearRollOverMessage() {
        _uiState.value = _uiState.value.copy(rollOverMessage = null)
    }

    fun createEnvelope(request: EnvelopeCreateRequest) {
        // The API refuses two seasons sharing a month: say which before sending (MAG-415).
        overlappingMonth(request.seasonalAmounts)?.let { month ->
            _uiState.value = _uiState.value.copy(error = overlapMessage(month))
            return
        }
        save { budgetRepository.createEnvelope(request) }
    }

    /** A one-off envelope: a name and an amount. */
    fun createOneOffEnvelope(name: String, amountCents: Int) {
        save { budgetRepository.createOneOffEnvelope(OneOffEnvelopeCreateRequest(name.trim(), amountCents, ONE_OFF_MODE)) }
    }

    /** Writes the editor's fields onto the envelope [id]; a refusal leaves the editor open on what was typed. */
    fun updateEnvelope(id: String, request: EnvelopeUpdateRequest) {
        overlappingMonth(request.seasonalAmounts)?.let { month ->
            _uiState.value = _uiState.value.copy(error = overlapMessage(month))
            return
        }
        save { budgetRepository.updateEnvelope(id, request) }
    }

    /**
     * Runs a write, then reloads the period. With the editor open, a success leaves it on the
     * envelope the API answered with (a creation becomes an edition); a failure keeps it as it was.
     */
    private fun save(write: suspend () -> Result<Envelope>) {
        viewModelScope.launch {
            val target = _uiState.value.editor?.target
            _uiState.value.editor?.let { _uiState.value = _uiState.value.copy(editor = it.copy(isSaving = true)) }
            try {
                val saved = write().getOrThrow()
                if (target != null && _uiState.value.editor?.target == target) {
                    _uiState.value = _uiState.value.copy(
                        editor = EnvelopeEditor(EditorTarget.Existing(saved.id), stored = saved),
                        splitEditor = null,
                    )
                }
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    editor = _uiState.value.editor?.copy(isSaving = false),
                    error = e.message,
                )
            }
        }
    }

    private fun overlapMessage(month: Int) =
        "Deux saisons couvrent ${monthLabel(month).lowercase()} : une saison ne peut pas en chevaucher une autre."

    /** Sets the threshold past which a sync makes Maggie interrupt; null is back to 80 %. */
    fun setAlertThreshold(id: String, percent: Int?) {
        viewModelScope.launch {
            try {
                budgetRepository.setAlertThreshold(id, percent).getOrThrow()
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun deleteEnvelope(id: String) {
        viewModelScope.launch {
            try {
                budgetRepository.deleteEnvelope(id).getOrThrow()
                val editor = _uiState.value.editor
                if ((editor?.target as? EditorTarget.Existing)?.envelopeId == id) {
                    _uiState.value = _uiState.value.copy(editor = null, splitEditor = null)
                }
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

private const val DETAIL_FAILED = "Impossible de lire les transactions de cette enveloppe."
private const val SPLIT_REFUSED = "La répartition dépasse le montant de l'enveloppe : rien n'a été modifié."
