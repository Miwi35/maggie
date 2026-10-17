package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.ContactBalance
import com.maggie.app.data.model.ContactDebt
import com.maggie.app.data.model.PeerDebt
import com.maggie.app.data.model.PeerDebtSummary
import com.maggie.app.data.model.openDebtsOf
import com.maggie.app.data.repository.PeerDebtRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class PeerDebtsTab { BY_PERSON, BY_OBJECT }

data class PeerDebtsUiState(
    val isLoading: Boolean = true,
    /** True once a read has succeeded: before it, an empty list means nothing. */
    val loaded: Boolean = false,
    val summary: PeerDebtSummary = PeerDebtSummary(),
    /** The créances and dettes still open, the biggest left to give back first. */
    val debts: List<PeerDebt> = emptyList(),
    val tab: PeerDebtsTab = PeerDebtsTab.BY_PERSON,
    /** The person opened: expanded on a phone, in the detail pane on a tablet. */
    val selectedContactId: String? = null,
    /** The object opened on a tablet. */
    val selectedDebtId: String? = null,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = loaded && summary.byContact.isEmpty() && debts.isEmpty()

    val selectedBalance: ContactBalance? get() = summary.byContact.firstOrNull { it.contactId == selectedContactId }

    val selectedDebt: PeerDebt? get() = debts.firstOrNull { it.id == selectedDebtId }

    /** What still waits on [contactId], one line per object. */
    fun linesOf(contactId: String): List<ContactDebt> = openDebtsOf(debts, contactId)
}

/**
 * « Créances et dettes » (MAG-455): the two totals, then who owes what — by person (the net of each,
 * the lines per object under it) or by object (the shares left on each). Both come from the server:
 * the net and the compensation of a person are its figures, not the app's.
 */
class PeerDebtsViewModel(
    private val peerDebtRepository: PeerDebtRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PeerDebtsUiState())
    val uiState: StateFlow<PeerDebtsUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            val summary = async { peerDebtRepository.getSummary() }
            val overview = async { peerDebtRepository.getOverview(status = PeerDebt.STATUS_OPEN) }
            val read = summary.await()
            val debts = overview.await()
            val failure = read.exceptionOrNull() ?: debts.exceptionOrNull()

            if (failure != null) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = failure.message ?: "Chargement impossible.")
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                loaded = true,
                summary = read.getOrThrow(),
                debts = debts.getOrThrow()
                    .filter { it.isOpen && it.remainingCents > 0 }
                    .sortedByDescending { it.remainingCents },
            )
        }
    }

    fun selectTab(tab: PeerDebtsTab) {
        _uiState.value = _uiState.value.copy(tab = tab)
    }

    /** Opens a person, or closes them when they already are. */
    fun toggleContact(contactId: String) {
        val state = _uiState.value
        _uiState.value = state.copy(selectedContactId = contactId.takeUnless { it == state.selectedContactId })
    }

    fun toggleDebt(debtId: String) {
        val state = _uiState.value
        _uiState.value = state.copy(selectedDebtId = debtId.takeUnless { it == state.selectedDebtId })
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
