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
import com.maggie.app.data.model.Contact
import com.maggie.app.data.model.MyShare
import com.maggie.app.data.model.MyShareRequest
import com.maggie.app.data.model.MyShareRequestShare
import com.maggie.app.data.model.PeerDebt
import com.maggie.app.data.model.TransactionAllocation
import com.maggie.app.data.model.TransactionDebt
import com.maggie.app.data.model.TransactionDebtRequest
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.splitEqually
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.RulePreview
import com.maggie.app.data.model.amountBoundsAround
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TransferInfo
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.ContactRepository
import com.maggie.app.data.repository.PeerDebtRepository
import com.maggie.app.data.repository.RecurringOperationRepository
import com.maggie.app.data.repository.TransactionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class TransactionUiState(
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val isLoading: Boolean = false,
    // The next page is being read; the list shows a discreet spinner at its foot.
    val isLoadingMore: Boolean = false,
    // The account holds more lines than the pages read so far.
    val canLoadMore: Boolean = false,
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
    // « Ajouter une personne » of the sheet's « Partage » open: the owner's contacts, read when it opens.
    val shareContacts: ContactChoice? = null,
    // « Envers qui »: the same full-screen picker, opened from a received transfer's « À rembourser » (MAG-452).
    val debtContacts: ContactChoice? = null,
    // « Envers qui » / « Qui te doit » of « Ce virement est » (MAG-453): the same picker.
    val peerContacts: ContactChoice? = null,
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

/** API Platform's default page size, which the API keeps for transactions. */
private const val PAGE_SIZE = 30
private const val LOAD_MORE_ERROR = "Impossible de charger la suite des transactions"

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
    // « Partage » (MAG-451): read with the line, null until then and for a line that is not ordinary.
    val share: ShareEditor? = null,
    // « Ce virement est » (MAG-452): read with a received line, null until then and for any other line.
    val debt: DebtEditor? = null,
    // « Ce virement est » of a transfer to or from a contact (MAG-453): read with any ordinary line, null until then.
    val peer: PeerTransferEditor? = null,
)

/** One person of « Qui te doit »: what they owe of the spend. */
data class ShareLine(
    val contactId: String,
    val name: String,
    val amountCents: Int,
)

/**
 * « Partage » of the open spend (MAG-451): the part the owner keeps — only it counts in the
 * envelopes — and the people who owe the rest. The rest is split equally, the last cent on the
 * last person, each time the part or the people change, until an amount is edited by hand: from
 * then on the amounts are the owner's, and all of them are sent.
 */
data class ShareEditor(
    /** The spend, in absolute cents. */
    val totalCents: Int,
    val canBeShared: Boolean,
    /** The part the server holds; null while the spend is not shared. */
    val savedMyShareCents: Int? = null,
    /** The switch « Dépense partagée ». */
    val enabled: Boolean = false,
    val myShareCents: Int = totalCents / 2,
    val people: List<ShareLine> = emptyList(),
    val editedByHand: Boolean = false,
    /** « Objet »: the label unless the owner names it otherwise. */
    val subject: String = "",
    val notes: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val isShared: Boolean get() = savedMyShareCents != null

    /** « On te doit »: what the others owe, the spend minus the owner's part. */
    val owedCents: Int get() = totalCents - myShareCents

    val allocatedCents: Int get() = people.sumOf { it.amountCents }

    /** Why « Enregistrer » cannot be sent yet, null when it can. */
    val problem: String? get() = when {
        !enabled -> null
        myShareCents < 0 || myShareCents >= totalCents -> "Ta part va de 0 au montant de la dépense, exclu."
        people.isEmpty() -> "Ajoute au moins une personne qui te doit sa part."
        owedCents < people.size -> "Il n'y a pas assez à partager entre ${people.size} personnes."
        editedByHand && people.any { it.amountCents <= 0 } -> "Chaque personne doit un montant positif."
        editedByHand && allocatedCents != owedCents ->
            "Les parts font ${formatCents(allocatedCents)}, " +
                "il en faut ${formatCents(owedCents)}."
        else -> null
    }

    val canSave: Boolean get() = enabled && problem == null && !isSaving

    /** The people's amounts recomputed equally, unless the owner wrote them by hand. */
    fun resplit(): ShareEditor {
        if (editedByHand) return this
        val amounts = splitEqually(owedCents, people.size)
        return copy(people = people.mapIndexed { index, line -> line.copy(amountCents = amounts[index]) })
    }

    fun toRequest(): MyShareRequest = MyShareRequest(
        myShareCents = myShareCents,
        `object` = subject.trim().ifEmpty { null },
        notes = notes.trim().ifEmpty { null },
        // No amount at all asks the server for the same equal split.
        shares = people.map { MyShareRequestShare(it.contactId, it.amountCents.takeIf { editedByHand }) },
    )

    companion object {
        /** The editor as the server's answer leaves it: switched on when the spend is shared. */
        fun of(share: MyShare, label: String, message: String? = null): ShareEditor {
            val total = kotlin.math.abs(share.amountCents)
            val debt = share.peerDebt
            val people = debt?.shares.orEmpty().map { ShareLine(it.contactId, it.contactName, it.amountCents) }
            val part = share.myShareCents ?: (total / 2)
            return ShareEditor(
                totalCents = total,
                canBeShared = share.canBeShared,
                savedMyShareCents = share.myShareCents,
                enabled = share.myShareCents != null,
                myShareCents = part,
                people = people,
                // Amounts the server holds that an equal split would not give were set by hand.
                editedByHand = people.isNotEmpty() && people.map { it.amountCents } != splitEqually(total - part, people.size),
                subject = debt?.`object` ?: label,
                notes = debt?.notes.orEmpty(),
                message = message,
            )
        }
    }
}

/**
 * « Ce virement est » of a received transfer (MAG-452): « Une recette », or « À rembourser » — a dette
 * (« Tu dois ») towards one person for [amountCents] of it, which then stops counting as income.
 * Every edit stays a draft until « Enregistrer »; going back to « Une recette » takes the dette back.
 */
data class DebtEditor(
    /** The transfer received, in cents. */
    val totalCents: Int,
    val canBeDebt: Boolean,
    /** The dette the server holds; null while the transfer is a recette. */
    val savedDebt: PeerDebt? = null,
    /** The choice « À rembourser ». */
    val enabled: Boolean = false,
    /** Envers qui: the dette's contact, or the one the label suggests; null until one is chosen. */
    val contactId: String? = null,
    val contactName: String? = null,
    /** Montant à rendre. */
    val amountCents: Int = totalCents,
    val subject: String = "",
    val notes: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val isRecorded: Boolean get() = savedDebt != null

    /** Why « Enregistrer » cannot be sent yet, null when it can. */
    val problem: String? get() = when {
        !enabled -> null
        contactId == null -> "Choisis la personne à qui tu dois cet argent."
        amountCents <= 0 -> "Indique le montant à rendre."
        amountCents > totalCents -> "Le montant à rendre ne peut pas dépasser le virement."
        else -> null
    }

    val canSave: Boolean get() = enabled && problem == null && !isSaving

    /** What « Enregistrer » creates, once the person is known. */
    fun summary(currency: String): String? = contactName?.let {
        "Crée une dette de ${formatCents(amountCents, currency)} envers $it, dans « Tu dois »"
    }

    fun toRequest(): TransactionDebtRequest = TransactionDebtRequest(
        contactId = contactId.orEmpty(),
        amountCents = amountCents,
        `object` = subject.trim().ifEmpty { null },
        notes = notes.trim().ifEmpty { null },
    )

    companion object {
        /**
         * The editor as the server's answer leaves it: « À rembourser » when a dette is held. Before
         * one is, the contact the label suggests ([suggestedName], read from the contacts) is proposed.
         */
        fun of(debt: TransactionDebt, label: String, suggestedName: String? = null, message: String? = null): DebtEditor {
            val saved = debt.peerDebt
            val share = saved?.shares?.firstOrNull()
            val suggested = debt.suggestedContactId?.takeIf { suggestedName != null }
            return DebtEditor(
                totalCents = kotlin.math.abs(debt.amountCents),
                canBeDebt = debt.canBeDebt,
                savedDebt = saved,
                enabled = saved != null,
                contactId = share?.contactId ?: suggested,
                contactName = share?.contactName ?: suggestedName.takeIf { suggested != null },
                amountCents = share?.amountCents ?: kotlin.math.abs(debt.amountCents),
                subject = saved?.`object` ?: label,
                notes = saved?.notes.orEmpty(),
                message = message,
            )
        }
    }
}

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
    private val peerDebtRepository: PeerDebtRepository,
    private val contactRepository: ContactRepository,
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

    // Pages read so far, the first included; a refresh reads that many again so a list scrolled down keeps its length.
    private var pagesLoaded = 0
    private var totalItems: Int? = null

    // A read started before a refresh must not land on the list the refresh rebuilt.
    private var listGeneration = 0

    fun refresh() {
        refreshIncidents()
        val generation = ++listGeneration
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, isLoadingMore = false, error = null)
            try {
                val pages = maxOf(1, pagesLoaded)
                var transactions = emptyList<Transaction>()
                var lastPageSize = 0
                var total: Int? = null
                for (page in 1..pages) {
                    val read = transactionRepository.getTransactions(accountId, page).getOrThrow()
                    transactions = (transactions + read.transactions).distinctBy { it.id }
                    lastPageSize = read.transactions.size
                    total = read.totalItems
                    if (read.transactions.isEmpty()) break
                }
                if (generation != listGeneration) return@launch
                pagesLoaded = pages
                totalItems = total
                _uiState.value = _uiState.value.copy(
                    transactions = transactions,
                    isLoading = false,
                    canLoadMore = hasMore(transactions.size, lastPageSize),
                    detail = _uiState.value.detail?.let { open ->
                        open.copy(transaction = transactions.firstOrNull { it.id == open.transaction.id } ?: open.transaction)
                    },
                )
            } catch (e: Exception) {
                if (generation != listGeneration) return@launch
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    /** The next page, appended when the list nears its end; a line already on screen is not listed twice. */
    fun loadMore() {
        val state = _uiState.value
        if (!state.canLoadMore || state.isLoadingMore || state.isLoading) return
        val generation = listGeneration
        _uiState.value = state.copy(isLoadingMore = true)
        viewModelScope.launch {
            transactionRepository.getTransactions(accountId, pagesLoaded + 1)
                .onSuccess { page ->
                    if (generation != listGeneration) return@onSuccess
                    pagesLoaded++
                    page.totalItems?.let { totalItems = it }
                    val transactions = (_uiState.value.transactions + page.transactions).distinctBy { it.id }
                    _uiState.value = _uiState.value.copy(
                        transactions = transactions,
                        isLoadingMore = false,
                        canLoadMore = page.transactions.isNotEmpty() && hasMore(transactions.size, page.transactions.size),
                    )
                }
                .onFailure { e ->
                    if (generation != listGeneration) return@onFailure
                    _uiState.value = _uiState.value.copy(isLoadingMore = false, error = e.message ?: LOAD_MORE_ERROR)
                }
        }
    }

    // Without a total, a full page says there may be another.
    private fun hasMore(loaded: Int, lastPageSize: Int): Boolean =
        totalItems?.let { loaded < it } ?: (lastPageSize >= PAGE_SIZE)

    // Only the latest read may land: one sent before a settlement must not put the line back.
    private var incidentsRead = 0

    fun refreshIncidents() {
        val read = ++incidentsRead
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingIncidents = true, incidentsError = null)
            transactionRepository.getIncidents(accountId)
                .onSuccess { incidents ->
                    if (read != incidentsRead) return@onSuccess
                    _uiState.value = _uiState.value.copy(incidents = incidents, isLoadingIncidents = false)
                }
                .onFailure { e ->
                    if (read != incidentsRead) return@onFailure
                    _uiState.value = _uiState.value.copy(
                        isLoadingIncidents = false,
                        incidentsError = e.message ?: "Impossible de lire les incidents",
                    )
                }
        }
        // The settled ones only fill the folded section: a failure leaves it as it was.
        viewModelScope.launch {
            transactionRepository.getSettledIncidents(accountId).onSuccess { settled ->
                if (read == incidentsRead) _uiState.value = _uiState.value.copy(settledIncidents = settled)
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
        incidentsRead++
        _uiState.value = before.copy(
            isLoadingIncidents = false,
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
                    // The read this gesture cancelled is owed.
                    refreshIncidents()
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
        incidentsRead++
        _uiState.value = before.copy(
            isLoadingIncidents = false,
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
                    // The read this gesture cancelled is owed.
                    refreshIncidents()
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
                if (_uiState.value.transactions.any { it.id == id }) totalItems = totalItems?.minus(1)
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
        // Only an ordinary line has a « Partage » section to fill: a spend is shared, a received transfer may be given back.
        if (transaction.transferKind == "none") {
            if (transaction.amountCents > 0) loadDebt(transaction) else loadShare(transaction)
            loadAllocation(transaction)
        }
    }

    // Only a transfer to or from a contact shows « Ce virement est »: a failed read leaves the sheet's
    // other sections as they are, the line simply is not asked.
    private fun loadAllocation(transaction: Transaction) {
        viewModelScope.launch {
            peerDebtRepository.getAllocation(transaction.id).onSuccess { allocation ->
                updateDetail(transaction.id) { it.copy(peer = PeerTransferEditor.of(allocation, transaction.label)) }
            }
        }
    }

    private fun updatePeer(change: (PeerTransferEditor) -> PeerTransferEditor) {
        val open = _uiState.value.detail ?: return
        val peer = open.peer ?: return
        _uiState.value = _uiState.value.copy(detail = open.copy(peer = change(peer)))
    }

    /** « Ce virement est »: a choice is a draft until « Enregistrer ». */
    fun choosePeerKind(kind: String) = updatePeer { it.withKind(kind) }

    /** « Il couvre »: a créance checked takes what it waits for, within what is left; unchecked, it is freed. */
    fun togglePeerShare(shareId: String) = updatePeer { it.toggle(shareId) }

    fun changePeerShareAmount(shareId: String, cents: Int) = updatePeer { it.withShareAmount(shareId, cents) }

    fun changePeerDebtAmount(cents: Int) = updatePeer { it.withDebtAmount(cents) }

    fun changePeerSubject(text: String) = updatePeer { it.withSubject(text) }

    fun changePeerNotes(text: String) = updatePeer { it.withNotes(text) }

    /** « Enregistrer »: `PUT …/allocation`; the server's answer becomes the editor. */
    fun savePeer() {
        val open = _uiState.value.detail ?: return
        val peer = open.peer ?: return
        if (!peer.canSave) return
        val request = peer.toRequest() ?: return
        val id = open.transaction.id
        updatePeer { it.copy(isSaving = true, error = null, message = null) }
        viewModelScope.launch {
            peerDebtRepository.setAllocation(id, request)
                .onSuccess { saved -> peerSaved(id, saved) }
                .onFailure { e -> updateDetail(id) { it.copy(peer = it.peer?.copy(isSaving = false, error = e.message ?: "Enregistrement impossible.")) } }
        }
    }

    private fun peerSaved(id: String, saved: TransactionAllocation) {
        updateDetail(id) {
            it.copy(
                transaction = it.transaction.copy(peerKind = saved.peerKind),
                peer = PeerTransferEditor.of(saved, it.transaction.label, peerSavedMessage(saved.peerKind)),
            )
        }
        // The income of the month and the « À trier » pile follow the answer.
        refresh()
    }

    fun clearPeerMessage() = updatePeer { it.copy(message = null) }

    /** « Envers qui » / « Qui te doit »: the full-screen picker, on the owner's contacts. */
    fun openPeerContactPicker() {
        if (_uiState.value.detail?.peer == null) return
        _uiState.value = _uiState.value.copy(peerContacts = ContactChoice())
        viewModelScope.launch {
            // No list is no reason to refuse: « + Créer » stays possible.
            val contacts = contactRepository.getContacts().getOrDefault(emptyList())
            _uiState.value.peerContacts?.let {
                _uiState.value = _uiState.value.copy(peerContacts = ContactChoice(isLoading = false, contacts = contacts))
            }
        }
    }

    fun closePeerContactPicker() {
        _uiState.value = _uiState.value.copy(peerContacts = null)
    }

    fun pickPeerContact(contact: Contact) {
        closePeerContactPicker()
        updatePeer { it.withPerson(contact) }
    }

    /** « + Créer « Inès » »: the contact is created, then becomes the person of the dette or créance. */
    fun createPeerContact(name: String) {
        if (name.isBlank()) return
        closePeerContactPicker()
        val id = _uiState.value.detail?.transaction?.id ?: return
        viewModelScope.launch {
            contactRepository.createContact(name.trim())
                .onSuccess { contact -> if (_uiState.value.detail?.transaction?.id == id) pickPeerContact(contact) }
                .onFailure { e -> updateDetail(id) { it.copy(peer = it.peer?.copy(error = e.message ?: "Création impossible.")) } }
        }
    }

    private fun loadDebt(transaction: Transaction) {
        viewModelScope.launch {
            peerDebtRepository.getDebt(transaction.id)
                .onSuccess { debt ->
                    val suggestedName = suggestedContactName(debt)
                    updateDetail(transaction.id) { it.copy(debt = DebtEditor.of(debt, transaction.label, suggestedName)) }
                }
                .onFailure { e -> updateDetail(transaction.id) { it.copy(error = e.message) } }
        }
    }

    // The contacts only name the suggestion: without them the person is simply left to choose.
    private suspend fun suggestedContactName(debt: TransactionDebt): String? {
        val suggested = debt.suggestedContactId ?: return null
        if (debt.peerDebt != null || !debt.canBeDebt) return null
        return contactRepository.getContacts().getOrNull()?.firstOrNull { it.id == suggested }?.name
    }

    private fun loadShare(transaction: Transaction) {
        viewModelScope.launch {
            peerDebtRepository.getMyShare(transaction.id)
                .onSuccess { share -> updateDetail(transaction.id) { it.copy(share = ShareEditor.of(share, transaction.label)) } }
                .onFailure { e -> updateDetail(transaction.id) { it.copy(error = e.message) } }
        }
    }

    private fun updateShare(change: (ShareEditor) -> ShareEditor) {
        val open = _uiState.value.detail ?: return
        val share = open.share ?: return
        _uiState.value = _uiState.value.copy(detail = open.copy(share = change(share)))
    }

    /**
     * The switch « Dépense partagée ». Off on a spend the server holds as shared takes the share
     * back (the whole amount is the owner's again); off on a draft only folds it.
     */
    fun setShareEnabled(enabled: Boolean) {
        val share = _uiState.value.detail?.share ?: return
        when {
            enabled -> updateShare { it.copy(enabled = true, error = null, message = null).resplit() }
            share.isShared -> removeShare()
            else -> updateShare { it.copy(enabled = false, error = null) }
        }
    }

    /** « Ta part », typed or stepped: kept between 0 and the spend, exclusive; the rest is split again. */
    fun changeMyShare(cents: Int) {
        updateShare { it.copy(myShareCents = cents.coerceIn(0, (it.totalCents - 1).coerceAtLeast(0)), error = null).resplit() }
    }

    /** A person picked (or created) in the full-screen picker joins « Qui te doit ». */
    fun addSharePerson(contact: Contact) {
        updateShare { share ->
            if (share.people.any { it.contactId == contact.id }) return@updateShare share
            // By hand, the newcomer gets what is not given yet; the split gives them their equal part.
            val rest = (share.owedCents - share.allocatedCents).coerceAtLeast(0)
            share.copy(people = share.people + ShareLine(contact.id, contact.name, rest), error = null).resplit()
        }
    }

    fun removeSharePerson(contactId: String) {
        updateShare { share -> share.copy(people = share.people.filter { it.contactId != contactId }, error = null).resplit() }
    }

    /** One person's amount written by hand: the split stops, every amount is sent as it is. */
    fun editShareAmount(contactId: String, cents: Int) {
        updateShare { share ->
            share.copy(
                editedByHand = true,
                people = share.people.map { if (it.contactId == contactId) it.copy(amountCents = cents) else it },
                error = null,
            )
        }
    }

    fun changeShareSubject(text: String) = updateShare { it.copy(subject = text) }

    fun changeShareNotes(text: String) = updateShare { it.copy(notes = text) }

    /** « Enregistrer »: the part and the créance, together. */
    fun saveShare() {
        val open = _uiState.value.detail ?: return
        val share = open.share ?: return
        if (!share.canSave) return
        val id = open.transaction.id
        updateShare { it.copy(isSaving = true, error = null, message = null) }
        viewModelScope.launch {
            peerDebtRepository.setMyShare(id, share.toRequest())
                .onSuccess { saved -> shareSaved(id, saved, "Partage enregistré") }
                .onFailure { e -> updateDetail(id) { it.copy(share = it.share?.copy(isSaving = false, error = e.message ?: "Enregistrement impossible.")) } }
        }
    }

    /** The share taken back: the whole spend counts in the envelopes again. */
    fun removeShare() {
        val open = _uiState.value.detail ?: return
        if (open.share?.isSaving == true) return
        val id = open.transaction.id
        updateShare { it.copy(isSaving = true, error = null, message = null) }
        viewModelScope.launch {
            peerDebtRepository.removeMyShare(id)
                .onSuccess { saved -> shareSaved(id, saved, "Partage retiré") }
                .onFailure { e -> updateDetail(id) { it.copy(share = it.share?.copy(isSaving = false, enabled = true, error = e.message ?: "Suppression impossible.")) } }
        }
    }

    private fun shareSaved(id: String, saved: MyShare, message: String) {
        updateDetail(id) {
            it.copy(
                transaction = it.transaction.copy(myShareCents = saved.myShareCents),
                share = ShareEditor.of(saved, it.transaction.label, message),
            )
        }
        // The list's pill and the envelopes follow the part kept.
        refresh()
    }

    fun clearShareMessage() = updateShare { it.copy(message = null) }

    /** « Ajouter une personne »: the full-screen picker, on the owner's contacts. */
    fun openShareContactPicker() {
        if (_uiState.value.detail?.share == null) return
        _uiState.value = _uiState.value.copy(shareContacts = ContactChoice())
        viewModelScope.launch {
            // No list is no reason to refuse: « + Créer » stays possible.
            val contacts = contactRepository.getContacts().getOrDefault(emptyList())
            _uiState.value.shareContacts?.let {
                _uiState.value = _uiState.value.copy(shareContacts = ContactChoice(isLoading = false, contacts = contacts))
            }
        }
    }

    fun closeShareContactPicker() {
        _uiState.value = _uiState.value.copy(shareContacts = null)
    }

    fun pickShareContact(contact: Contact) {
        closeShareContactPicker()
        addSharePerson(contact)
    }

    /** « + Créer « Inès » »: the contact is created, then joins « Qui te doit ». */
    fun createShareContact(name: String) {
        if (name.isBlank()) return
        closeShareContactPicker()
        val id = _uiState.value.detail?.transaction?.id ?: return
        viewModelScope.launch {
            contactRepository.createContact(name.trim())
                .onSuccess { contact -> if (_uiState.value.detail?.transaction?.id == id) addSharePerson(contact) }
                .onFailure { e -> updateDetail(id) { it.copy(share = it.share?.copy(error = e.message ?: "Création impossible.")) } }
        }
    }

    private fun updateDebt(change: (DebtEditor) -> DebtEditor) {
        val open = _uiState.value.detail ?: return
        val debt = open.debt ?: return
        _uiState.value = _uiState.value.copy(detail = open.copy(debt = change(debt)))
    }

    /**
     * The choice « Ce virement est »: « À rembourser » opens the draft; « Une recette » on a dette the
     * server holds takes it back (the whole transfer counts as income again), on a draft only folds it.
     */
    fun setDebtEnabled(enabled: Boolean) {
        val debt = _uiState.value.detail?.debt ?: return
        when {
            enabled -> updateDebt { it.copy(enabled = true, error = null, message = null) }
            debt.isRecorded -> removeDebt()
            else -> updateDebt { it.copy(enabled = false, error = null) }
        }
    }

    /** « Montant à rendre », typed or stepped: kept between 0 and the transfer. */
    fun changeDebtAmount(cents: Int) {
        updateDebt { it.copy(amountCents = cents.coerceIn(0, it.totalCents), error = null, message = null) }
    }

    fun changeDebtSubject(text: String) = updateDebt { it.copy(subject = text) }

    fun changeDebtNotes(text: String) = updateDebt { it.copy(notes = text) }

    /** « Enregistrer »: the dette is created (or updated) with its single share. */
    fun saveDebt() {
        val open = _uiState.value.detail ?: return
        val debt = open.debt ?: return
        if (!debt.canSave) return
        val id = open.transaction.id
        updateDebt { it.copy(isSaving = true, error = null, message = null) }
        viewModelScope.launch {
            peerDebtRepository.recordDebt(id, debt.toRequest())
                .onSuccess { saved -> debtSaved(id, saved, "Dette enregistrée") }
                .onFailure { e -> updateDetail(id) { it.copy(debt = it.debt?.copy(isSaving = false, error = e.message ?: "Enregistrement impossible.")) } }
        }
    }

    /** The dette taken back: the transfer is a recette again. */
    fun removeDebt() {
        val open = _uiState.value.detail ?: return
        if (open.debt?.isSaving == true) return
        val id = open.transaction.id
        updateDebt { it.copy(isSaving = true, error = null, message = null) }
        viewModelScope.launch {
            peerDebtRepository.removeDebt(id)
                .onSuccess { saved -> debtSaved(id, saved, "Dette retirée") }
                .onFailure { e -> updateDetail(id) { it.copy(debt = it.debt?.copy(isSaving = false, enabled = true, error = e.message ?: "Suppression impossible.")) } }
        }
    }

    private fun debtSaved(id: String, saved: TransactionDebt, message: String) {
        // A recette again keeps the person and amount the owner had in mind, for a second thought.
        updateDetail(id) {
            val before = it.debt
            val editor = DebtEditor.of(saved, it.transaction.label, message = message)
            it.copy(
                debt = if (saved.peerDebt == null && before != null) {
                    editor.copy(contactId = before.contactId, contactName = before.contactName, amountCents = before.amountCents, subject = before.subject, notes = before.notes)
                } else {
                    editor
                },
            )
        }
    }

    fun clearDebtMessage() = updateDebt { it.copy(message = null) }

    /** « Envers qui »: the full-screen picker, on the owner's contacts. */
    fun openDebtContactPicker() {
        if (_uiState.value.detail?.debt == null) return
        _uiState.value = _uiState.value.copy(debtContacts = ContactChoice())
        viewModelScope.launch {
            // No list is no reason to refuse: « + Créer » stays possible.
            val contacts = contactRepository.getContacts().getOrDefault(emptyList())
            _uiState.value.debtContacts?.let {
                _uiState.value = _uiState.value.copy(debtContacts = ContactChoice(isLoading = false, contacts = contacts))
            }
        }
    }

    fun closeDebtContactPicker() {
        _uiState.value = _uiState.value.copy(debtContacts = null)
    }

    fun pickDebtContact(contact: Contact) {
        closeDebtContactPicker()
        updateDebt { it.copy(contactId = contact.id, contactName = contact.name, error = null, message = null) }
    }

    /** « + Créer « Inès » »: the contact is created, then becomes the person owed. */
    fun createDebtContact(name: String) {
        if (name.isBlank()) return
        closeDebtContactPicker()
        val id = _uiState.value.detail?.transaction?.id ?: return
        viewModelScope.launch {
            contactRepository.createContact(name.trim())
                .onSuccess { contact -> if (_uiState.value.detail?.transaction?.id == id) pickDebtContact(contact) }
                .onFailure { e -> updateDetail(id) { it.copy(debt = it.debt?.copy(error = e.message ?: "Création impossible.")) } }
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
        _uiState.value = _uiState.value.copy(detail = null, shareContacts = null, debtContacts = null, peerContacts = null)
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
