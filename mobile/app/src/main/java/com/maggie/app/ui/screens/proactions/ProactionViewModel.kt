package com.maggie.app.ui.screens.proactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Proaction
import com.maggie.app.data.repository.ProactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ProactionUiState(
    val proactions: List<Proaction> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class ProactionViewModel(
    private val proactionRepository: ProactionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProactionUiState())
    val uiState: StateFlow<ProactionUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val proactions = proactionRepository.getProactions().getOrThrow()
                _uiState.value = ProactionUiState(
                    proactions = proactions,
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
}
