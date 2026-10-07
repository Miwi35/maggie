package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.Category
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TransferInfo
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.repository.CategoryRepository
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
    // The line whose detail screen is open; null while the list is showing.
    val detail: TransferDetailState? = null,
)

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
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionUiState())
    val uiState: StateFlow<TransactionUiState> = _uiState

    init {
        refresh()
        loadCategories()
        subscribeToMercure()
    }

    // Marking one line republishes the other leg too: either update refreshes the list and the open detail.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.TRANSACTIONS))
                .catch { /* SSE reconnects automatically */ }
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

    fun refresh() {
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
    ) {
        viewModelScope.launch {
            try {
                transactionRepository.createTransaction(
                    TransactionCreateRequest(
                        account = "/api/accounts/$accountId",
                        amountCents = amountCents,
                        label = label,
                        category = categoryId?.let { "/api/categories/$it" },
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
    }

    fun closeDetail() {
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

    /** « Ce n'est pas un virement interne »: the API frees both legs. */
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

    private fun updateDetail(transactionId: String, change: (TransferDetailState) -> TransferDetailState) {
        val open = _uiState.value.detail ?: return
        if (open.transaction.id != transactionId) return
        _uiState.value = _uiState.value.copy(detail = change(open))
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
