package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.BankConnection
import com.maggie.app.data.model.bankSyncSummary
import com.maggie.app.data.repository.BankConnectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class BankConnectionUiState(
    val connections: List<BankConnection> = emptyList(),
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val reconnectingId: String? = null,
    /** Set once the bank answered with a consent URL; the screen opens it and clears it. */
    val authorizationUrl: String? = null,
    val message: String? = null,
    val error: String? = null,
)

/**
 * The bank links, what they brought and what they still need.
 *
 * The consent itself happens at the bank, in a browser, and the provider sends
 * the user back to the web admin: the only thing this screen can do is open the
 * URL and let the list be read again afterwards. Hence [reconnect] handing back
 * a URL instead of a result.
 */
class BankConnectionViewModel(
    private val repository: BankConnectionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BankConnectionUiState())
    val uiState: StateFlow<BankConnectionUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.getConnections()
                .onSuccess { connections ->
                    _uiState.value = _uiState.value.copy(
                        connections = connections.sortedBy { it.bankName },
                        isLoading = false,
                    )
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = failure.message)
                }
        }
    }

    /**
     * Asks the banks for what is new. Every fetch spends part of the bank's
     * daily allowance, so it is a button and never a timer.
     */
    fun sync() {
        if (_uiState.value.isSyncing) {
            return
        }

        // Marked before the coroutine starts, not inside it: two taps land in the
        // same frame, and the second one must find the first already running.
        _uiState.value = _uiState.value.copy(isSyncing = true, message = null, error = null)

        viewModelScope.launch {
            repository.sync()
                .onSuccess { result ->
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        message = bankSyncSummary(result),
                    )
                    // The dates and the statuses moved with the fetch.
                    refresh()
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(isSyncing = false, error = failure.message)
                }
        }
    }

    fun reconnect(id: String) {
        if (_uiState.value.reconnectingId != null) {
            return
        }

        // Marked before the coroutine starts, like the fetch: each journey opened
        // is a pending connection stored server-side, so two taps must not open two.
        _uiState.value = _uiState.value.copy(reconnectingId = id, error = null)

        viewModelScope.launch {
            repository.reconnect(id)
                .onSuccess { authorization ->
                    _uiState.value = _uiState.value.copy(
                        reconnectingId = null,
                        authorizationUrl = authorization.authorizationUrl,
                        message = "Autorisez l'accès chez votre banque, puis revenez et actualisez.",
                    )
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(
                        reconnectingId = null,
                        error = failure.message,
                    )
                }
        }
    }

    /** The URL is a one-shot order to open a browser, not a piece of state to keep. */
    fun consumeAuthorizationUrl() {
        _uiState.value = _uiState.value.copy(authorizationUrl = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
