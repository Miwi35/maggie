package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.repository.AccountRepository
import com.maggie.app.data.repository.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class TransactionUiState(
    val transactions: List<Transaction> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class TransactionViewModel(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionUiState())
    val uiState: StateFlow<TransactionUiState> = _uiState

    init {
        refresh()
        loadAccounts()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val transactions = transactionRepository.getTransactions().getOrThrow()
                _uiState.value = _uiState.value.copy(transactions = transactions, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    private fun loadAccounts() {
        viewModelScope.launch {
            accountRepository.getAccounts().getOrNull()?.let { accounts ->
                _uiState.value = _uiState.value.copy(accounts = accounts)
            }
        }
    }

    fun createTransaction(request: TransactionCreateRequest) {
        viewModelScope.launch {
            try {
                transactionRepository.createTransaction(request).getOrThrow()
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
}
