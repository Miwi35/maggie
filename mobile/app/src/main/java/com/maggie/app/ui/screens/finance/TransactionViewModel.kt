package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class TransactionUiState(
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val isLoading: Boolean = false,
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionUiState())
    val uiState: StateFlow<TransactionUiState> = _uiState

    init {
        refresh()
        loadCategories()
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
                _uiState.value = _uiState.value.copy(transactions = transactions, isLoading = false)
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

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
