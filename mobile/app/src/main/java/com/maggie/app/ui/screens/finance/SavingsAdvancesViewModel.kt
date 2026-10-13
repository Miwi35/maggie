package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.AdvanceRepaymentProposal
import com.maggie.app.data.model.SavingsAdvances
import com.maggie.app.data.repository.SavingsAdvancesRepository
import com.maggie.app.data.repository.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class SavingsAdvancesUiState(
    val advances: SavingsAdvances? = null,
    val isLoading: Boolean = false,
    val loadError: String? = null,
    val isSavingDay: Boolean = false,
    /** The advance whose counter-transfer is being recorded: one at a time, a double tap must not book it twice. */
    val recordingAdvanceId: String? = null,
    val message: String? = null,
    val actionError: String? = null,
)

class SavingsAdvancesViewModel(
    private val advancesRepository: SavingsAdvancesRepository,
    private val transactionRepository: TransactionRepository,
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(SavingsAdvancesUiState())
    val uiState: StateFlow<SavingsAdvancesUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _uiState.value = _uiState.value.copy(isLoading = true, loadError = null)
        advancesRepository.getSavingsAdvances()
            .onSuccess { _uiState.value = _uiState.value.copy(advances = it, isLoading = false) }
            .onFailure { _uiState.value = _uiState.value.copy(isLoading = false, loadError = it.message ?: "Erreur") }
    }

    /** [input] blank forgets the day; otherwise 1 to 31. */
    fun saveSalaryDay(input: String) {
        val text = input.trim()
        val day = text.toIntOrNull()
        if (text.isNotEmpty() && (day == null || day !in 1..31)) {
            _uiState.value = _uiState.value.copy(actionError = "Indiquez un jour entre 1 et 31.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSavingDay = true, actionError = null, message = null)
            advancesRepository.setSalaryExpectedDay(day)
                .onSuccess { delay ->
                    _uiState.value = _uiState.value.copy(
                        advances = _uiState.value.advances?.copy(salaryDelay = delay),
                        isSavingDay = false,
                        message = if (day == null) "Jour de salaire oublié" else "Jour de salaire enregistré",
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isSavingDay = false, actionError = it.message ?: "Erreur")
                }
        }
    }

    /**
     * Books the two legs of the counter-transfer, debit then credit — as a bank sync would —
     * and lets the server pair them and settle the advance. Nothing moves at the bank.
     */
    fun recordCounterTransfer(proposal: AdvanceRepaymentProposal) {
        if (_uiState.value.recordingAdvanceId != null) return

        _uiState.value = _uiState.value.copy(
            recordingAdvanceId = proposal.advanceId,
            actionError = null,
            message = null,
        )
        viewModelScope.launch {
            val bookedAt = today().toString()
            val debit = transactionRepository.createTransaction(
                leg(proposal, proposal.fromAccountId, -proposal.amountCents, bookedAt),
            )
            val credit = if (debit.isSuccess) {
                transactionRepository.createTransaction(leg(proposal, proposal.toAccountId, proposal.amountCents, bookedAt))
            } else {
                null
            }

            _uiState.value = _uiState.value.copy(
                recordingAdvanceId = null,
                message = if (credit?.isSuccess == true) "Transaction enregistrée" else null,
                actionError = when {
                    debit.isFailure -> debit.exceptionOrNull()?.message ?: "Erreur"
                    credit?.isFailure == true ->
                        "Le débit est enregistré, pas le crédit : ${credit.exceptionOrNull()?.message ?: "erreur"}"
                    else -> null
                },
            )
            // A first leg that went through changes the server's view even when the second failed.
            load()
        }
    }

    private fun leg(proposal: AdvanceRepaymentProposal, accountId: String, amountCents: Int, bookedAt: String) =
        TransactionCreateRequest(
            account = "/api/accounts/$accountId",
            amountCents = amountCents,
            label = proposal.label,
            currency = proposal.currency,
            status = "spent",
            bookedAt = bookedAt,
        )

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun clearActionError() {
        _uiState.value = _uiState.value.copy(actionError = null)
    }
}
