package com.maggie.app.ui.screens.contexts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Context
import com.maggie.app.data.repository.ContextRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class ContextUiState(
    val contexts: List<Context> = emptyList(),
    val activeCount: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class ContextViewModel(
    private val contextRepository: ContextRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContextUiState())
    val uiState: StateFlow<ContextUiState> = _uiState

    init {
        refresh()
        subscribeToMercure()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val contexts = contextRepository.getContexts().getOrThrow()
                _uiState.value = ContextUiState(
                    contexts = contexts,
                    activeCount = contexts.count { it.status == "active" },
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message,
                    isLoading = false,
                )
            }
        }
    }

    fun handleStreamUpdate(context: Context) {
        val current = _uiState.value.contexts.toMutableList()
        val index = current.indexOfFirst { it.id == context.id }
        if (index >= 0) {
            current[index] = context
        } else {
            current.add(0, context)
        }
        _uiState.value = _uiState.value.copy(
            contexts = current,
            activeCount = current.count { it.status == "active" },
        )
    }

    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe("/contexts/$userId")
                .catch { /* SSE reconnects automatically */ }
                .collect { refresh() }
        }
    }
}
