package com.maggie.app.ui.screens.proactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.Proaction
import com.maggie.app.data.repository.ProactionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class ProactionUiState(
    val proactions: List<Proaction> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/** The list of Maggie's runs, read from the same source as the agenda and kept fresh by Mercure (MAG-328). */
class ProactionViewModel(
    private val proactionRepository: ProactionRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProactionUiState())
    val uiState: StateFlow<ProactionUiState> = _uiState

    init {
        refresh()
        subscribeToMercure()
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        try {
            val proactions = proactionRepository.getProactions().getOrThrow()
            _uiState.value = ProactionUiState(proactions = proactions, isLoading = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
        }
    }

    // Subscribed once the user is known, and again on each login. A burst of changes reloads once.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            authRepository.token
                .map { it != null }
                .distinctUntilChanged()
                .collectLatest { authenticated ->
                    if (!authenticated) return@collectLatest
                    val userId = authRepository.getUserId() ?: return@collectLatest
                    mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.PROACTIONS))
                        .catch { /* SSE reconnects automatically */ }
                        .coalescedAndRechecked()
                        .collect { load() }
                }
        }
    }
}
