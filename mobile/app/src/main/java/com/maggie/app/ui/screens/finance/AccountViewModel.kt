package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.AccountCreateRequest
import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.LinkCandidate
import com.maggie.app.data.model.LinkPreview
import com.maggie.app.data.model.AccountTimeline
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.TimelineAccount
import com.maggie.app.data.model.TimelineDay
import com.maggie.app.data.model.Category
import com.maggie.app.data.repository.AccountRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * What one account's timeline holds: the months read so far, the current month first. Going back
 * in time appends a month; a refresh reads the same months again.
 */
data class AccountTimelineState(
    val pages: List<AccountTimeline> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingPrevious: Boolean = false,
    // Why the first read failed: the page shows it with « Réessayer ». A failed older month only
    // raises the screen's [AccountUiState.error].
    val error: String? = null,
    // The bank's accounts this hand-kept account can be linked to; read only for a hand-kept one (MAG-823).
    val linkCandidates: List<LinkCandidate> = emptyList(),
) {
    /** Every day read, most recent first: the months are already in that order, and so are their days. */
    val days: List<TimelineDay> get() = pages.flatMap { it.days }

    /** The account as the newest read says it (balance, hand-kept), null until a month was read. */
    val account: TimelineAccount? get() = pages.firstOrNull()?.account

    /** The month to ask for to go further back; null when there is nothing older. */
    val previousMonth: String? get() = pages.lastOrNull()?.previousMonth

    val isLoaded: Boolean get() = pages.isNotEmpty()
}

data class AccountUiState(
    val accounts: List<Account> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    // The account on screen: the pager's page on a phone, the selected row on a tablet (MAG-422).
    val selectedAccountId: String? = null,
    val timelines: Map<String, AccountTimelineState> = emptyMap(),
    // Only feed the picker of « Ajouter une transaction »: the timelines work without them.
    val categories: List<Category> = emptyList(),
    // « Rattacher »: the candidate whose figures the dialog shows, the answer to say (« Compte rattaché. ») or the refusal.
    val link: LinkState = LinkState(),
) {
    val selectedIndex: Int get() = accounts.indexOfFirst { it.id == selectedAccountId }.coerceAtLeast(0)
}

/** The link dialog of one account: the candidate chosen, its preview once read, and what to tell the owner. */
data class LinkState(
    val accountId: String? = null,
    val candidate: LinkCandidate? = null,
    val preview: LinkPreview? = null,
    val isWorking: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

class AccountViewModel(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState

    init {
        refresh()
        loadCategories()
        subscribeToMercure()
    }

    private fun loadCategories() {
        viewModelScope.launch {
            categoryRepository.getCategories().onSuccess { categories ->
                _uiState.value = _uiState.value.copy(categories = categories)
            }
        }
    }

    // A change to a line moves the day lists and the balances: the account on screen is read again,
    // the others are dropped and read when they come back into view.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.TRANSACTIONS))
                .catch { /* SSE reconnects automatically */ }
                .coalesced()
                .collect {
                    val selected = _uiState.value.selectedAccountId ?: return@collect
                    _uiState.value = _uiState.value.copy(timelines = _uiState.value.timelines.filterKeys { it == selected })
                    reloadTimeline(selected)
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val accounts = accountRepository.getAccounts().getOrThrow().sortedBy { it.name }
                val selected = _uiState.value.selectedAccountId?.takeIf { id -> accounts.any { it.id == id } }
                    ?: accounts.firstOrNull()?.id
                _uiState.value = _uiState.value.copy(
                    accounts = accounts,
                    isLoading = false,
                    selectedAccountId = selected,
                )
                selected?.let(::ensureTimeline)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun createAccount(request: AccountCreateRequest) {
        viewModelScope.launch {
            try {
                accountRepository.createAccount(request).getOrThrow()
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun deleteAccount(id: String) {
        viewModelScope.launch {
            try {
                accountRepository.deleteAccount(id).getOrThrow()
                val remaining = _uiState.value.accounts.filter { it.id != id }
                val selected = _uiState.value.selectedAccountId?.takeIf { selected -> remaining.any { it.id == selected } }
                    ?: remaining.firstOrNull()?.id
                _uiState.value = _uiState.value.copy(
                    accounts = remaining,
                    selectedAccountId = selected,
                    timelines = _uiState.value.timelines - id,
                )
                selected?.let(::ensureTimeline)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun renameAccount(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            accountRepository.renameAccount(id, trimmed)
                .onSuccess { updated ->
                    _uiState.value = _uiState.value.copy(
                        accounts = _uiState.value.accounts.map { if (it.id == id) updated else it }.sortedBy { it.name },
                    )
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message ?: "Impossible de renommer le compte") }
        }
    }

    /** The pager settled on an account, or a row was touched: its timeline is read if it was not yet. */
    fun selectAccount(accountId: String) {
        if (_uiState.value.selectedAccountId != accountId) {
            _uiState.value = _uiState.value.copy(selectedAccountId = accountId)
        }
        ensureTimeline(accountId)
    }

    private fun ensureTimeline(accountId: String) {
        val state = _uiState.value.timelines[accountId]
        if (state == null || (!state.isLoaded && !state.isLoading)) loadTimeline(accountId)
    }

    /** Reads the current month of an account afresh; « Réessayer » after a failure comes here. */
    fun loadTimeline(accountId: String) {
        updateTimeline(accountId) { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            accountRepository.accountTimeline(accountId)
                .onSuccess { page ->
                    updateTimeline(accountId) { it.copy(pages = listOf(page), isLoading = false, error = null) }
                    if (page.account.isManual) loadLinkCandidates(accountId)
                }
                .onFailure { e ->
                    updateTimeline(accountId) { it.copy(isLoading = false, error = e.message ?: TIMELINE_ERROR) }
                }
        }
    }

    // The months already read are read again, in order; on a failure the old ones stay on screen.
    private fun reloadTimeline(accountId: String) {
        val months = _uiState.value.timelines[accountId]?.pages?.map { it.month }.orEmpty()
        if (months.isEmpty()) return loadTimeline(accountId)
        viewModelScope.launch {
            val pages = mutableListOf<AccountTimeline>()
            for ((index, month) in months.withIndex()) {
                val page = accountRepository.accountTimeline(accountId, month.takeIf { index > 0 })
                    .getOrElse { e ->
                        _uiState.value = _uiState.value.copy(error = e.message ?: TIMELINE_ERROR)
                        return@launch
                    }
                pages += page
            }
            updateTimeline(accountId) { it.copy(pages = pages) }
        }
    }

    /** « Mois précédent »: the month before the oldest one read is added at the end of the list. */
    fun loadPreviousMonth(accountId: String) {
        val state = _uiState.value.timelines[accountId] ?: return
        val month = state.previousMonth ?: return
        if (state.isLoadingPrevious) return
        updateTimeline(accountId) { it.copy(isLoadingPrevious = true) }
        viewModelScope.launch {
            accountRepository.accountTimeline(accountId, month)
                .onSuccess { page ->
                    updateTimeline(accountId) { it.copy(pages = it.pages + page, isLoadingPrevious = false) }
                }
                .onFailure { e ->
                    updateTimeline(accountId) { it.copy(isLoadingPrevious = false) }
                    _uiState.value = _uiState.value.copy(error = e.message ?: TIMELINE_ERROR)
                }
        }
    }

    /** « Corriger le solde » of a hand-kept account: the new balance, then the timeline and the header read again. */
    fun correctBalance(accountId: String, balanceCents: Int) {
        viewModelScope.launch {
            accountRepository.updateBalance(accountId, balanceCents)
                .onSuccess { updated ->
                    _uiState.value = _uiState.value.copy(
                        accounts = _uiState.value.accounts.map { if (it.id == accountId) updated else it },
                    )
                    reloadTimeline(accountId)
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message ?: "Impossible de corriger le solde") }
        }
    }

    private fun loadLinkCandidates(accountId: String) {
        viewModelScope.launch {
            accountRepository.linkCandidates(accountId)
                .onSuccess { candidates -> updateTimeline(accountId) { it.copy(linkCandidates = candidates) } }
        }
    }

    /** A candidate was chosen: its preview is read, nothing is written. A refusal is told as it is. */
    fun previewLink(accountId: String, candidate: LinkCandidate) {
        _uiState.value = _uiState.value.copy(link = LinkState(accountId = accountId, candidate = candidate, isWorking = true))
        viewModelScope.launch {
            accountRepository.link(accountId, candidate.id, confirm = false)
                .onSuccess { preview ->
                    _uiState.value = _uiState.value.copy(link = LinkState(accountId = accountId, candidate = candidate, preview = preview))
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(link = LinkState(error = e.message ?: LINK_ERROR))
                }
        }
    }

    /** « Rattacher »: the link is written, then the page and the list are read again. */
    fun confirmLink() {
        val link = _uiState.value.link
        val accountId = link.accountId ?: return
        val candidate = link.candidate ?: return
        _uiState.value = _uiState.value.copy(link = link.copy(isWorking = true))
        viewModelScope.launch {
            accountRepository.link(accountId, candidate.id, confirm = true)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(link = LinkState(notice = LINK_DONE))
                    updateTimeline(accountId) { it.copy(linkCandidates = emptyList()) }
                    refresh()
                    reloadTimeline(accountId)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(link = LinkState(error = e.message ?: LINK_ERROR))
                }
        }
    }

    /** « Annuler »: nothing changes. */
    fun cancelLink() {
        _uiState.value = _uiState.value.copy(link = LinkState())
    }

    /** The notice or the refusal was shown. */
    fun clearLinkFeedback() {
        _uiState.value = _uiState.value.copy(link = _uiState.value.link.copy(notice = null, error = null))
    }

    /** « Ajouter une transaction » on the account of the page; [status] defaults to spent, [bookedAt] (yyyy-MM-dd) to today. */
    fun addTransaction(
        accountId: String,
        amountCents: Int,
        label: String,
        status: String = "spent",
        categoryId: String? = null,
        bookedAt: String? = null,
        qualification: Qualification = Qualification.NONE,
    ) {
        viewModelScope.launch {
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
            )
                .onSuccess { reloadTimeline(accountId) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message ?: "Impossible d'ajouter la transaction") }
        }
    }

    private fun updateTimeline(accountId: String, change: (AccountTimelineState) -> AccountTimelineState) {
        val timelines = _uiState.value.timelines
        _uiState.value = _uiState.value.copy(
            timelines = timelines + (accountId to change(timelines[accountId] ?: AccountTimelineState())),
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

private const val LINK_DONE = "Compte rattaché."
private const val LINK_ERROR = "Impossible de rattacher le compte"
private const val TIMELINE_ERROR = "Impossible de lire les transactions de ce compte"
