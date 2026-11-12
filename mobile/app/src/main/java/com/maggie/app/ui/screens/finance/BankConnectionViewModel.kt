package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Bank
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
    /** A first journey is being started: a second tap on a bank must not open a second one. */
    val connecting: Boolean = false,
    val banks: List<Bank> = emptyList(),
    val banksCountry: String = "FR",
    val banksLoading: Boolean = false,
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
 * URL and let the list be read again afterwards. Hence [reconnect] and [connect]
 * handing back a URL instead of a result.
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

    private companion object {
        const val CONSENT_MESSAGE = "Autorisez l'accès chez votre banque, puis revenez et actualisez."
    }

    /** The banks the provider offers in [country], for the search. */
    fun loadBanks(country: String) {
        // A stale answer must not overwrite the list of the country chosen since.
        _uiState.value = _uiState.value.copy(banksCountry = country, banksLoading = true, banks = emptyList())

        viewModelScope.launch {
            repository.banks(country)
                .onSuccess { banks ->
                    if (_uiState.value.banksCountry == country) {
                        _uiState.value = _uiState.value.copy(banks = banks, banksLoading = false)
                    }
                }
                .onFailure { failure ->
                    if (_uiState.value.banksCountry == country) {
                        _uiState.value = _uiState.value.copy(banksLoading = false, error = failure.message)
                    }
                }
        }
    }

    /** A first link with [bankName]: the same hand-over to the browser as [reconnect]. */
    fun connect(bankName: String, country: String) {
        if (_uiState.value.connecting) {
            return
        }

        // Marked before the coroutine starts, like [reconnect]: each journey is a pending connection.
        _uiState.value = _uiState.value.copy(connecting = true, error = null)

        viewModelScope.launch {
            repository.start(bankName, country)
                .onSuccess { authorization ->
                    _uiState.value = _uiState.value.copy(
                        connecting = false,
                        authorizationUrl = authorization.authorizationUrl,
                        message = CONSENT_MESSAGE,
                    )
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(connecting = false, error = failure.message)
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
                        message = CONSENT_MESSAGE,
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

    /** The accounts and their transactions stay; only the link goes, and the list is read again. */
    fun forget(id: String) {
        viewModelScope.launch {
            repository.forget(id)
                .onSuccess { refresh() }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(error = failure.message ?: "Impossible d'oublier la banque")
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
