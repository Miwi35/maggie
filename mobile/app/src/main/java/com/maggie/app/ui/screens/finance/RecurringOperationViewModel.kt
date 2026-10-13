package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.RecurringOperationWriteRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.ProposalClaim
import com.maggie.app.data.model.RecurringAttachReport
import com.maggie.app.data.model.RecurringOperationRecord
import com.maggie.app.data.model.RecurringOperationsOverview
import com.maggie.app.data.model.RecurringProposal
import com.maggie.app.data.model.RecurringSeries
import com.maggie.app.data.model.RecurringSuggestion
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.acceptedRecurringSummary
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.repository.AccountRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.RecurringOperationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** The series on the editor: a stored one, or a new one, possibly drawn from [transaction]. */
data class RecurringEditing(
    val record: RecurringOperationRecord? = null,
    val transaction: Transaction? = null,
)

/**
 * What the history would attach to a series just created, read before it writes. Only the
 * lines of [seriesId] are shown: the pass covers the whole history.
 */
data class RecurringPreview(
    val seriesId: String,
    val seriesLabel: String,
    val report: RecurringAttachReport,
    val fromTransaction: Boolean,
) {
    val lines: List<RecurringProposal> get() = report.attachments.filter { it.recurringOperationId == seriesId }
}

data class RecurringOperationUiState(
    /** Null until the first load answers: the screen shows a spinner, not an empty state. */
    val overview: RecurringOperationsOverview? = null,
    val suggestions: List<RecurringSuggestion> = emptyList(),
    /** The stored series by id: what the overview does not carry (payee, anchor day, tolerances). */
    val records: Map<String, RecurringOperationRecord> = emptyMap(),
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    /** The heading chosen for a suggestion the API could not file, by [suggestionKey]. */
    val suggestionCategories: Map<String, String> = emptyMap(),
    /** Refused during this visit; the API holds no « refused » list. */
    val dismissedProposals: Set<String> = emptySet(),
    val dismissedSuggestions: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    /** The transaction id or suggestion key being accepted. */
    val actingOn: String? = null,
    val editing: RecurringEditing? = null,
    val preview: RecurringPreview? = null,
    /** The lines the stored series on the editor recognised, newest first; null until they are read. */
    val seriesLines: List<Transaction>? = null,
    val seriesLinesError: String? = null,
    /** The line being detached from the series. */
    val detachingId: String? = null,
    /** Set when an editor opened from a transaction closes: the screen goes back to it. */
    val returnToTransaction: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val operations: List<RecurringSeries> get() = overview?.operations.orEmpty()

    val proposals: List<RecurringProposal> get() =
        overview?.proposals.orEmpty().filter { it.transactionId !in dismissedProposals }

    val visibleSuggestions: List<RecurringSuggestion> get() =
        suggestions.filter { suggestionKey(it) !in dismissedSuggestions }

    val isEmpty: Boolean get() = overview != null && operations.isEmpty() && proposals.isEmpty() && visibleSuggestions.isEmpty()

    fun categoryOf(suggestion: RecurringSuggestion): String? =
        suggestion.categoryId ?: suggestionCategories[suggestionKey(suggestion)]

    fun canAccept(suggestion: RecurringSuggestion): Boolean =
        actingOn == null && categoryOf(suggestion) != null
}

/** What tells two suggestions apart: the payee on its account. */
fun suggestionKey(suggestion: RecurringSuggestion): String =
    listOfNotNull(
        suggestion.counterpartyKey ?: suggestion.labelPattern ?: suggestion.label,
        suggestion.accountId,
    ).joinToString("|")

/**
 * The recurring operations: what they cost, what is late, what the history proposes — and the
 * editor of one series. One ViewModel for both, as the category screen does: the editor is only
 * what is in front of the list.
 */
class RecurringOperationViewModel(
    private val repository: RecurringOperationRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecurringOperationUiState())
    val uiState: StateFlow<RecurringOperationUiState> = _uiState

    init {
        refresh()
        subscribeToMercure()
    }

    // A line attached or detached elsewhere reaches the open series without a pull to refresh.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.TRANSACTIONS))
                .catch { /* SSE reconnects automatically */ }
                .coalescedAndRechecked()
                .collect { reloadSeriesLines() }
        }
    }

    // Read once: the headings and the accounts do not move while the screen is open, and a
    // failure must be shown rather than wiped by the next load's `error = null`.
    private suspend fun loadLookups() {
        if (_uiState.value.categories.isEmpty()) {
            categoryRepository.getCategories()
                .onSuccess { categories -> _uiState.value = _uiState.value.copy(categories = categories.sortedBy { it.name }) }
                .onFailure { failure -> _uiState.value = _uiState.value.copy(error = failure.message) }
        }
        if (_uiState.value.accounts.isEmpty()) {
            accountRepository.getAccounts()
                .onSuccess { accounts -> _uiState.value = _uiState.value.copy(accounts = accounts.sortedBy { it.name }) }
                .onFailure { failure -> _uiState.value = _uiState.value.copy(error = failure.message) }
        }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        loadLookups()
        repository.getOverview()
            .onSuccess { overview -> _uiState.value = _uiState.value.copy(overview = overview) }
            .onFailure { failure -> _uiState.value = _uiState.value.copy(error = failure.message) }
        repository.getRecords()
            .onSuccess { records -> _uiState.value = _uiState.value.copy(records = records.associateBy { it.id }) }
        // The detection is a bonus: without it the series still read.
        repository.getSuggestions()
            .onSuccess { suggestions -> _uiState.value = _uiState.value.copy(suggestions = suggestions) }
        _uiState.value = _uiState.value.copy(isLoading = false)
    }

    /** Attaches the proposed line to the due date the pass saw; the proposal leaves the list. */
    fun acceptProposal(proposal: RecurringProposal, claim: ProposalClaim) {
        val seriesId = claim.recurringOperationId ?: return
        val occurrenceOn = claim.occurrenceOn ?: return
        if (_uiState.value.actingOn != null) return

        // Marked before the coroutine starts: two taps land in the same frame, and one yes must attach once.
        _uiState.value = _uiState.value.copy(actingOn = proposal.transactionId, message = null, error = null)

        viewModelScope.launch {
            repository.attachTransaction(proposal.transactionId, seriesId, occurrenceOn.take(10))
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        actingOn = null,
                        message = "Transaction rattachée à « ${claim.recurringOperationLabel ?: "l'opération récurrente"} »",
                    )
                    load()
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(actingOn = null, error = failure.message)
                }
        }
    }

    /** Drops the proposal for this visit. Nothing is written: a no is not an attachment. */
    fun dismissProposal(transactionId: String) {
        _uiState.value = _uiState.value.copy(dismissedProposals = _uiState.value.dismissedProposals + transactionId)
    }

    fun chooseSuggestionCategory(key: String, categoryId: String) {
        _uiState.value = _uiState.value.copy(
            suggestionCategories = _uiState.value.suggestionCategories + (key to categoryId),
        )
    }

    /** Creates the series and lets the API run its first pass over the history. */
    fun acceptSuggestion(suggestion: RecurringSuggestion) {
        val state = _uiState.value
        val categoryId = state.categoryOf(suggestion) ?: return
        if (state.actingOn != null) return
        val key = suggestionKey(suggestion)
        val categoryName = state.categories.firstOrNull { it.id == categoryId }?.name

        _uiState.value = state.copy(actingOn = key, message = null, error = null)

        viewModelScope.launch {
            repository.acceptSuggestions(listOf(suggestion.copy(categoryId = categoryId, categoryName = categoryName)))
                .onSuccess { result ->
                    _uiState.value = _uiState.value.copy(actingOn = null, message = acceptedRecurringSummary(result))
                    load()
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(actingOn = null, error = failure.message)
                }
        }
    }

    fun dismissSuggestion(key: String) {
        _uiState.value = _uiState.value.copy(dismissedSuggestions = _uiState.value.dismissedSuggestions + key)
    }

    fun startCreate() {
        _uiState.value = _uiState.value.copy(editing = RecurringEditing(), error = null)
    }

    fun startFromTransaction(transaction: Transaction) {
        _uiState.value = _uiState.value.copy(editing = RecurringEditing(transaction = transaction), error = null)
    }

    fun startEdit(seriesId: String) {
        val record = _uiState.value.records[seriesId]
        if (record == null) {
            _uiState.value = _uiState.value.copy(error = "Cette opération récurrente est introuvable. Tirez pour actualiser.")
            return
        }
        _uiState.value = _uiState.value.copy(
            editing = RecurringEditing(record = record),
            seriesLines = null,
            seriesLinesError = null,
            detachingId = null,
            error = null,
        )
        loadSeriesLines(seriesId)
    }

    fun reloadSeriesLines() {
        _uiState.value.editing?.record?.id?.let(::loadSeriesLines)
    }

    private fun loadSeriesLines(seriesId: String) {
        viewModelScope.launch {
            val result = repository.getSeriesTransactions(seriesId)
            // The editor may have closed, or moved to another series, while the lines were on their way.
            if (_uiState.value.editing?.record?.id != seriesId) return@launch
            result
                .onSuccess { lines -> _uiState.value = _uiState.value.copy(seriesLines = lines, seriesLinesError = null) }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(seriesLinesError = failure.message ?: "Lecture impossible")
                }
        }
    }

    /** Takes the line out of the series: the transaction stays, it is only no longer recognised. */
    fun detachSeriesLine(transactionId: String) {
        val seriesId = _uiState.value.editing?.record?.id ?: return
        if (_uiState.value.detachingId != null) return

        _uiState.value = _uiState.value.copy(detachingId = transactionId, seriesLinesError = null)

        viewModelScope.launch {
            repository.detachTransaction(transactionId)
                .onSuccess {
                    val state = _uiState.value
                    _uiState.value = state.copy(
                        detachingId = null,
                        seriesLines = if (state.editing?.record?.id == seriesId) state.seriesLines?.filter { it.id != transactionId } else state.seriesLines,
                    )
                    load()
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(
                        detachingId = null,
                        seriesLinesError = failure.message ?: "Détachement impossible",
                    )
                }
        }
    }

    fun closeEditor() {
        val editing = _uiState.value.editing
        _uiState.value = _uiState.value.copy(
            editing = null,
            returnToTransaction = editing?.transaction != null,
            error = null,
        )
    }

    fun save(request: RecurringOperationWriteRequest) {
        val editing = _uiState.value.editing ?: return
        if (_uiState.value.isSaving) return

        _uiState.value = _uiState.value.copy(isSaving = true, message = null, error = null)

        viewModelScope.launch {
            val record = editing.record
            if (record == null) {
                repository.create(request)
                    .onSuccess { created -> afterCreate(created, editing) }
                    .onFailure { failure -> _uiState.value = _uiState.value.copy(isSaving = false, error = failure.message) }
            } else {
                repository.update(record.id, request)
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(isSaving = false, editing = null, message = "Opération récurrente enregistrée")
                        load()
                    }
                    .onFailure { failure -> _uiState.value = _uiState.value.copy(isSaving = false, error = failure.message) }
            }
        }
    }

    // The history is rehearsed first: nothing is attached until the owner has read the lines.
    private suspend fun afterCreate(created: RecurringOperationRecord, editing: RecurringEditing) {
        val fromTransaction = editing.transaction != null
        val report = repository.attachHistory(dryRun = true).getOrNull()
        val preview = report?.let { RecurringPreview(created.id, created.label, it, fromTransaction) }
            ?.takeIf { it.lines.isNotEmpty() }

        _uiState.value = _uiState.value.copy(
            isSaving = false,
            editing = null,
            preview = preview,
            returnToTransaction = fromTransaction && preview == null,
            message = if (preview == null) "Opération récurrente créée" else null,
        )
        if (preview == null) load()
    }

    /** Writes what the preview showed. */
    fun confirmPreview() {
        val preview = _uiState.value.preview ?: return
        if (_uiState.value.isSaving) return

        _uiState.value = _uiState.value.copy(isSaving = true, error = null)
        viewModelScope.launch {
            repository.attachHistory(dryRun = false)
                .onSuccess { report ->
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        preview = null,
                        returnToTransaction = preview.fromTransaction,
                        message = "Opération récurrente créée, ${report.attached} transaction(s) rattachée(s)",
                    )
                    load()
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(isSaving = false, error = failure.message)
                }
        }
    }

    /** « Plus tard »: the series stays, its history is attached by the next pass. */
    fun dismissPreview() {
        val preview = _uiState.value.preview ?: return
        _uiState.value = _uiState.value.copy(
            preview = null,
            returnToTransaction = preview.fromTransaction,
            message = "Opération récurrente créée",
        )
        refresh()
    }

    fun delete(seriesId: String) {
        if (_uiState.value.isSaving) return
        _uiState.value = _uiState.value.copy(isSaving = true, message = null, error = null)

        viewModelScope.launch {
            repository.delete(seriesId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isSaving = false, editing = null, message = "Opération récurrente supprimée")
                    load()
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(isSaving = false, error = failure.message)
                }
        }
    }

    fun consumeReturnToTransaction() {
        _uiState.value = _uiState.value.copy(returnToTransaction = false)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
