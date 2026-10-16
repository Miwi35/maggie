package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.Category
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.AccountIncident
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.RulePreview
import com.maggie.app.data.model.amountBoundsAround
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TransferInfo
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.RecurringOperationRepository
import com.maggie.app.data.repository.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class TransactionUiState(
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    // The account's rejected payments (MAG-375), kept out of [transactions]; they have their own tab.
    val incidents: List<AccountIncident> = emptyList(),
    val isLoadingIncidents: Boolean = false,
    val incidentsError: String? = null,
    // The rejections the owner settled (« Régularisé », MAG-424), out of the pile and listed apart.
    val settledIncidents: List<AccountIncident> = emptyList(),
    // The rejection « Annuler » would put back, while the offer lasts.
    val undoableSettlement: AccountIncident? = null,
    // The key of the rejection being settled or put back; its buttons wait meanwhile.
    val settlingIncident: String? = null,
    // A settlement the API refused; the rejection stays where it was.
    val settlementError: String? = null,
    // The line whose detail screen is open; null while the list is showing.
    val detail: TransferDetailState? = null,
    // Names of the series the lines are attached to, by series id; empty while unread.
    val seriesNames: Map<String, String> = emptyMap(),
    // Patterns of the rules, by rule id; read when a line set by a rule is opened.
    val ruleNames: Map<String, String> = emptyMap(),
    // The « Créer un filtre » screen, opened from the sheet of the open line.
    val ruleDraft: RuleDraftState? = null,
    // The events a spend can be attributed to (MAG-414), read when the sheet needs them.
    val oneOffEnvelopes: List<Envelope> = emptyList(),
    val isLoadingOneOffEnvelopes: Boolean = false,
) {
    /** The name of the event the open line is attributed to, once the envelopes are read. */
    fun oneOffEnvelopeNameOf(transaction: Transaction): String? =
        transaction.oneOffEnvelopeId?.let { id -> oneOffEnvelopes.firstOrNull { it.id == id }?.name }
}

/** A rule being written from a line: its criteria, and the lines it would catch as they change. */
data class RuleDraftState(
    val labelPattern: String,
    val direction: String,
    val categoryId: String?,
    val qualification: Qualification = Qualification.NONE,
    /** « 4 750 » in euros, without sign: with [tolerance] it bounds the amounts the rule catches. */
    val amount: String = "",
    val tolerance: String = DEFAULT_RULE_TOLERANCE,
    val preview: RulePreview? = null,
    val isPreviewing: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    /** Absolute cents, as the rule stores them; null while no amount is given or it cannot be read. */
    val amountBounds: Pair<Int, Int>? get() {
        val cents = parseCents(amount) ?: return null
        val percent = tolerance.trim().toIntOrNull() ?: return null
        return amountBoundsAround(cents, percent)
    }

    /** Why the rule cannot be saved yet, null when it can: a label, or a direction with an amount bound, is required. */
    val problem: String? get() = when {
        amount.isNotBlank() && amountBounds == null -> "Montant ou écart invalide : un montant différent de zéro, un écart de 0 à 100 %."
        labelPattern.isNotBlank() -> null
        direction in setOf("debit", "credit") && amountBounds != null -> null
        else -> "Indiquez un libellé, ou un montant pour reconnaître la transaction par son seul montant."
    }
}

const val DEFAULT_RULE_TOLERANCE = "10"

/** How long « Annuler » stays on offer after « Régularisé » (MAG-424). */
const val UNDO_SETTLEMENT_MS = 5_000L
private const val SETTLE_ERROR = "Impossible de régulariser ce rejet"
private const val UNSETTLE_ERROR = "Impossible de remettre ce rejet dans la pile"

/** The detail screen of one line: its transfer state, and the candidates once the search opens. */
data class TransferDetailState(
    val transaction: Transaction,
    val info: TransferInfo? = null,
    val candidates: List<TransferLeg> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
)

/**
 * Account-scoped transaction list. Transactions are accessed primarily through
 * their account (banking-app pattern), so this view model is bound to one account.
 */
class TransactionViewModel(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val accountId: String,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val recurringOperationRepository: RecurringOperationRepository,
    private val ruleRepository: CategorizationRuleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionUiState())
    val uiState: StateFlow<TransactionUiState> = _uiState

    init {
        refresh()
        loadCategories()
        loadSeriesNames()
        subscribeToMercure()
    }

    // Marking one line republishes the other leg too: either update refreshes the list and the open detail.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.TRANSACTIONS))
                .catch { /* SSE reconnects automatically */ }
                .coalesced()
                .collect {
                    refresh()
                    _uiState.value.detail?.let { loadDetailInfo(it.transaction.id) }
                }
        }
    }

    // Categories only feed the optional picker: the list works without them
    private fun loadCategories() {
        viewModelScope.launch {
            categoryRepository.getCategories().onSuccess { categories ->
                _uiState.value = _uiState.value.copy(categories = categories)
            }
        }
    }

    // Series names only decorate pills and the sheet: a failure leaves them as « Série ».
    private fun loadSeriesNames() {
        viewModelScope.launch {
            recurringOperationRepository.getRecords().onSuccess { records ->
                _uiState.value = _uiState.value.copy(seriesNames = records.associate { it.id to it.label })
            }
        }
    }

    fun refresh() {
        refreshIncidents()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val transactions = transactionRepository.getTransactions(accountId).getOrThrow()
                _uiState.value = _uiState.value.copy(
                    transactions = transactions,
                    isLoading = false,
                    detail = _uiState.value.detail?.let { open ->
                        open.copy(transaction = transactions.firstOrNull { it.id == open.transaction.id } ?: open.transaction)
                    },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun refreshIncidents() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingIncidents = true, incidentsError = null)
            transactionRepository.getIncidents(accountId)
                .onSuccess { incidents ->
                    _uiState.value = _uiState.value.copy(incidents = incidents, isLoadingIncidents = false)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoadingIncidents = false,
                        incidentsError = e.message ?: "Impossible de lire les incidents",
                    )
                }
        }
        // The settled ones only fill the folded section: a failure leaves it as it was.
        viewModelScope.launch {
            transactionRepository.getSettledIncidents(accountId).onSuccess { settled ->
                _uiState.value = _uiState.value.copy(settledIncidents = settled)
            }
        }
    }

    private var undoJob: Job? = null

    /**
     * « Régularisé »: the rejection leaves the pile at once for « Régularisés », and « Annuler »
     * is on offer for [UNDO_SETTLEMENT_MS]. A refusal puts it back where it was.
     */
    fun settleIncident(incident: AccountIncident) {
        val id = incident.openId ?: return
        if (_uiState.value.settlingIncident != null) return
        val before = _uiState.value
        undoJob?.cancel()
        _uiState.value = before.copy(
            incidents = before.incidents.filterNot { it.key == incident.key },
            settledIncidents = listOf(incident) + before.settledIncidents.filterNot { it.key == incident.key },
            settlingIncident = incident.key,
            undoableSettlement = null,
            settlementError = null,
        )
        viewModelScope.launch {
            transactionRepository.settleRejection(id)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(settlingIncident = null, undoableSettlement = incident)
                    undoJob = viewModelScope.launch {
                        delay(UNDO_SETTLEMENT_MS)
                        _uiState.value = _uiState.value.copy(undoableSettlement = null)
                    }
                    refreshIncidents()
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        incidents = before.incidents,
                        settledIncidents = before.settledIncidents,
                        settlingIncident = null,
                        settlementError = e.message ?: SETTLE_ERROR,
                    )
                }
        }
    }

    /** « Annuler » on the bar: the rejection just settled goes back to the pile. */
    fun undoSettlement() {
        val incident = _uiState.value.undoableSettlement ?: return
        undoJob?.cancel()
        _uiState.value = _uiState.value.copy(undoableSettlement = null)
        unsettleIncident(incident)
    }

    /** Puts a settled rejection back in the pile, from the bar or from « Régularisés ». */
    fun unsettleIncident(incident: AccountIncident) {
        val id = incident.openId ?: return
        if (_uiState.value.settlingIncident != null) return
        val before = _uiState.value
        _uiState.value = before.copy(
            incidents = (before.incidents + incident.copy(settledAt = null)).sortedByDescending { it.bookedAt },
            settledIncidents = before.settledIncidents.filterNot { it.key == incident.key },
            settlingIncident = incident.key,
            settlementError = null,
        )
        viewModelScope.launch {
            transactionRepository.unsettleRejection(id)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(settlingIncident = null)
                    refreshIncidents()
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        incidents = before.incidents,
                        settledIncidents = before.settledIncidents,
                        settlingIncident = null,
                        settlementError = e.message ?: UNSETTLE_ERROR,
                    )
                }
        }
    }

    fun clearSettlementError() {
        _uiState.value = _uiState.value.copy(settlementError = null)
    }

    /**
     * Create a transaction on this account. amountCents is signed (negative = expense).
     * Without a [bookedAt] (yyyy-MM-dd) the API books it today; the default status is "spent".
     */
    fun createTransaction(
        amountCents: Int,
        label: String,
        status: String = "spent",
        categoryId: String? = null,
        bookedAt: String? = null,
        qualification: Qualification = Qualification.NONE,
    ) {
        viewModelScope.launch {
            try {
                transactionRepository.createTransaction(
                    TransactionCreateRequest(
                        account = "/api/accounts/$accountId",
                        amountCents = amountCents,
                        label = label,
                        category = categoryId?.let { "/api/categories/$it" },
                        qualification = qualification.takeIf { it != Qualification.NONE },
                        status = status,
                        bookedAt = bookedAt,
                    ),
                ).getOrThrow()
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun deleteTransaction(id: String) {
        viewModelScope.launch {
            try {
                transactionRepository.deleteTransaction(id).getOrThrow()
                _uiState.value = _uiState.value.copy(
                    transactions = _uiState.value.transactions.filter { it.id != id },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun openDetail(transactionId: String) {
        val transaction = _uiState.value.transactions.firstOrNull { it.id == transactionId } ?: return
        _uiState.value = _uiState.value.copy(detail = TransferDetailState(transaction = transaction, isLoading = true))
        loadDetailInfo(transactionId)
        // The sheet names the event an attributed line belongs to.
        if (transaction.oneOffEnvelope != null && _uiState.value.oneOffEnvelopes.isEmpty()) {
            loadOneOffEnvelopes()
        }
        if (transaction.qualifiedByRule != null && _uiState.value.ruleNames.isEmpty()) {
            loadRuleNames()
        }
    }

    // The rule's name only completes « Posée par la règle … »: a failure leaves the sentence without it.
    private fun loadRuleNames() {
        viewModelScope.launch {
            ruleRepository.getRules().onSuccess { rules ->
                _uiState.value = _uiState.value.copy(ruleNames = rules.mapNotNull { rule -> rule.labelPattern?.let { rule.id to it } }.toMap())
            }
        }
    }

    /** Reads the one-off envelopes, for the full-screen choice and the sheet's « Événement » field. */
    fun loadOneOffEnvelopes() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingOneOffEnvelopes = true)
            transactionRepository.getOneOffEnvelopes()
                .onSuccess { envelopes ->
                    _uiState.value = _uiState.value.copy(oneOffEnvelopes = envelopes, isLoadingOneOffEnvelopes = false)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isLoadingOneOffEnvelopes = false)
                    _uiState.value.detail?.let { open -> updateDetail(open.transaction.id) { it.copy(error = e.message) } }
                }
        }
    }

    /**
     * Attributes the open line to the one-off envelope [envelopeId]: it keeps its category but
     * leaves the monthly and annual envelopes. Null takes the attribution back.
     */
    fun setOneOffEnvelope(envelopeId: String?) {
        val open = _uiState.value.detail ?: return
        val id = open.transaction.id
        viewModelScope.launch {
            updateDetail(id) { it.copy(isSaving = true, error = null) }
            transactionRepository.setOneOffEnvelope(id, envelopeId)
                .onSuccess { updated ->
                    updateDetail(id) {
                        it.copy(isSaving = false, transaction = it.transaction.copy(oneOffEnvelope = updated.oneOffEnvelope))
                    }
                    refresh()
                }
                .onFailure { e -> updateDetail(id) { it.copy(isSaving = false, error = e.message) } }
        }
    }

    /** An incident opens the detail of its payment, which names the credit that gave it back. */
    fun openIncident(incident: AccountIncident) {
        val line = incident.asTransaction() ?: return
        _uiState.value = _uiState.value.copy(detail = TransferDetailState(transaction = line, isLoading = true))
        loadDetailInfo(line.id)
    }

    fun closeDetail() {
        ruleDraftSession.close()
        _uiState.value = _uiState.value.copy(detail = null)
    }

    private fun loadDetailInfo(transactionId: String) {
        viewModelScope.launch {
            transactionRepository.getTransfer(transactionId)
                .onSuccess { info -> updateDetail(transactionId) { it.copy(info = info, isLoading = false, error = null) } }
                .onFailure { e -> updateDetail(transactionId) { it.copy(isLoading = false, error = e.message) } }
        }
    }

    /** The counterparts a line can be paired with by hand; asked when the search opens. */
    fun loadCandidates() {
        val open = _uiState.value.detail ?: return
        updateDetail(open.transaction.id) { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            transactionRepository.getTransferCandidates(open.transaction.id)
                .onSuccess { candidates ->
                    updateDetail(open.transaction.id) { it.copy(candidates = candidates, isLoading = false) }
                }
                .onFailure { e -> updateDetail(open.transaction.id) { it.copy(isLoading = false, error = e.message) } }
        }
    }

    /** « C'est un virement interne »: [counterpartId] is the line picked in the search, null for none. */
    fun markAsTransfer(counterpartId: String?) = saveTransfer(internal = true, counterpartId = counterpartId)

    /** « Ce n'est pas un virement interne » or « Ce n'est pas un rejet »: the API frees both legs. */
    fun releaseTransfer() = saveTransfer(internal = false, counterpartId = null)

    private fun saveTransfer(internal: Boolean, counterpartId: String?) {
        val open = _uiState.value.detail ?: return
        val id = open.transaction.id
        viewModelScope.launch {
            updateDetail(id) { it.copy(isSaving = true, error = null) }
            transactionRepository.setTransfer(id, internal, counterpartId)
                .onSuccess { info ->
                    updateDetail(id) {
                        it.copy(
                            info = info,
                            isSaving = false,
                            transaction = it.transaction.copy(
                                transferKind = info.transferKind,
                                transferSource = info.transferSource,
                            ),
                        )
                    }
                    refresh()
                }
                .onFailure { e -> updateDetail(id) { it.copy(isSaving = false, error = e.message) } }
        }
    }

    /**
     * Qualifies the open line by hand: under [categoryId] (null takes the category away) with
     * [qualification], always together. The line becomes « À la main »: no series or rule touches it again.
     */
    fun setQualification(categoryId: String?, qualification: Qualification) {
        val open = _uiState.value.detail ?: return
        val id = open.transaction.id
        viewModelScope.launch {
            updateDetail(id) { it.copy(isSaving = true, error = null) }
            transactionRepository.setQualification(id, categoryId, qualification)
                .onSuccess { updated ->
                    updateDetail(id) {
                        it.copy(
                            isSaving = false,
                            transaction = it.transaction.copy(
                                category = updated.category,
                                qualification = updated.qualification,
                                qualifiedBy = updated.qualifiedBy,
                                qualifiedBySeries = updated.qualifiedBySeries,
                                qualifiedByRule = updated.qualifiedByRule,
                            ),
                        )
                    }
                    refresh()
                }
                .onFailure { e -> updateDetail(id) { it.copy(isSaving = false, error = e.message) } }
        }
    }

    /** Writes the open line's note; a blank text erases it. */
    fun setNote(text: String) {
        val open = _uiState.value.detail ?: return
        val id = open.transaction.id
        viewModelScope.launch {
            updateDetail(id) { it.copy(isSaving = true, error = null) }
            transactionRepository.setNote(id, text)
                .onSuccess { updated ->
                    updateDetail(id) { it.copy(isSaving = false, transaction = it.transaction.copy(note = updated.note)) }
                }
                .onFailure { e -> updateDetail(id) { it.copy(isSaving = false, error = e.message) } }
        }
    }

    /** « Détacher de la série »: the open line stops counting as an occurrence. */
    fun detachFromSeries() {
        val open = _uiState.value.detail ?: return
        val id = open.transaction.id
        viewModelScope.launch {
            updateDetail(id) { it.copy(isSaving = true, error = null) }
            recurringOperationRepository.detachTransaction(id)
                .onSuccess {
                    updateDetail(id) {
                        it.copy(
                            isSaving = false,
                            transaction = it.transaction.copy(recurringOperation = null, recurringOccurrenceOn = null),
                        )
                    }
                    refresh()
                }
                .onFailure { e -> updateDetail(id) { it.copy(isSaving = false, error = e.message) } }
        }
    }

    private val ruleDraftSession = RuleDraftSession(
        scope = viewModelScope,
        ruleRepository = ruleRepository,
        read = { _uiState.value.ruleDraft },
        write = { _uiState.value = _uiState.value.copy(ruleDraft = it) },
        // The lines the rule files are no longer « À qualifier »: the list is read again.
        onSaved = ::refresh,
    )

    /** Opens « Créer un filtre » filled from the open line: its label, the direction of its amount, its category. */
    fun openRuleDraft() {
        val line = _uiState.value.detail?.transaction ?: return
        ruleDraftSession.open(line.label, line.amountCents, line.categoryId, line.qualification)
    }

    fun closeRuleDraft() = ruleDraftSession.close()

    fun changeRulePattern(text: String) = ruleDraftSession.changePattern(text)

    fun changeRuleAmount(amount: String, tolerance: String) = ruleDraftSession.changeAmount(amount, tolerance)

    fun changeRuleCategory(categoryId: String?) = ruleDraftSession.changeCategory(categoryId)

    fun changeRuleQualification(qualification: Qualification) = ruleDraftSession.changeQualification(qualification)

    fun saveRule() = ruleDraftSession.save()

    private fun updateDetail(transactionId: String, change: (TransferDetailState) -> TransferDetailState) {
        val open = _uiState.value.detail ?: return
        if (open.transaction.id != transactionId) return
        _uiState.value = _uiState.value.copy(detail = change(open))
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
