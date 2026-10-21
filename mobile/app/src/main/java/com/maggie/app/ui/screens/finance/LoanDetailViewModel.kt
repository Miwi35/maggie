package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.EarlyRepayment
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.LoanCurve
import com.maggie.app.data.repository.LoanRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LoanDetailUiState(
    val loanId: String? = null,
    val isLoading: Boolean = false,
    val loan: Loan? = null,
    val curve: LoanCurve? = null,
    /** The IRA of repaying the whole capital; [totalError] says why there is none (the loan forbids it). */
    val total: EarlyRepayment? = null,
    val totalError: String? = null,
    /** The tablet's draft of the conditions, null until the loan is loaded. */
    val draft: LoanTermsDraft? = null,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val saveError: String? = null,
    val isSimulating: Boolean = false,
    val simulation: EarlyRepayment? = null,
    val simulationError: String? = null,
    /** The loan could not be read. */
    val error: String? = null,
)

/**
 * The sheet of one loan (MAG-523): what it is, the IRA of repaying it whole, the capital still owed month
 * by month; on the tablet the conditions draft, its save and the IRA simulation of an amount.
 */
class LoanDetailViewModel(
    private val repository: LoanRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoanDetailUiState())
    val uiState: StateFlow<LoanDetailUiState> = _uiState

    fun load(loanId: String) {
        _uiState.value = LoanDetailUiState(loanId = loanId, isLoading = true)
        viewModelScope.launch {
            repository.getLoan(loanId)
                .onSuccess { loan ->
                    _uiState.value = _uiState.value.copy(loan = loan, draft = LoanTermsDraft.of(loan), isLoading = false)
                    loadDerived(loanId)
                }
                .onFailure { _uiState.value = _uiState.value.copy(isLoading = false, error = it.message ?: MESSAGE_LOAD) }
        }
    }

    // The curve and the IRA come from the conditions: read again after a save. Losing one costs the rest nothing.
    private suspend fun loadDerived(loanId: String) {
        repository.getCurve(loanId).onSuccess { _uiState.value = _uiState.value.copy(curve = it) }
        repository.getEarlyRepayment(loanId)
            .onSuccess { _uiState.value = _uiState.value.copy(total = it, totalError = null) }
            .onFailure { _uiState.value = _uiState.value.copy(total = null, totalError = it.message) }
    }

    fun editDraft(draft: LoanTermsDraft) {
        _uiState.value = _uiState.value.copy(draft = draft, saved = false, saveError = null)
    }

    fun save() {
        val state = _uiState.value
        val id = state.loanId ?: return
        if (state.isSaving) return
        val request = state.draft?.toRequest() ?: return

        _uiState.value = state.copy(isSaving = true, saved = false, saveError = null)
        viewModelScope.launch {
            repository.updateLoan(id, request)
                .onSuccess { loan ->
                    _uiState.value = _uiState.value.copy(
                        loan = loan,
                        draft = LoanTermsDraft.of(loan),
                        isSaving = false,
                        saved = true,
                        simulation = null,
                        simulationError = null,
                    )
                    loadDerived(id)
                }
                .onFailure { _uiState.value = _uiState.value.copy(isSaving = false, saveError = it.message ?: MESSAGE_SAVE) }
        }
    }

    /** The IRA of repaying [amountCents]; the server's own sentence when it refuses. */
    fun simulate(amountCents: Int?) {
        val id = _uiState.value.loanId ?: return
        _uiState.value = _uiState.value.copy(isSimulating = true, simulationError = null)
        viewModelScope.launch {
            repository.getEarlyRepayment(id, amountCents)
                .onSuccess { _uiState.value = _uiState.value.copy(isSimulating = false, simulation = it) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isSimulating = false,
                        simulation = null,
                        simulationError = it.message ?: MESSAGE_SIMULATE,
                    )
                }
        }
    }

    private companion object {
        const val MESSAGE_LOAD = "Impossible de lire ce prêt."
        const val MESSAGE_SAVE = "Les conditions n'ont pas été enregistrées."
        const val MESSAGE_SIMULATE = "La simulation a échoué."
    }
}
