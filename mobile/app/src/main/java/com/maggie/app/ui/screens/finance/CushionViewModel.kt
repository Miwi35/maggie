package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.CushionConfigRequest
import com.maggie.app.data.model.CushionStatus
import com.maggie.app.data.repository.CushionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CushionUiState(
    val status: CushionStatus? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val savedMessage: String? = null,
    val error: String? = null,
)

class CushionViewModel(
    private val cushionRepository: CushionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CushionUiState())
    val uiState: StateFlow<CushionUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val status = cushionRepository.getStatus().getOrThrow()
                _uiState.value = _uiState.value.copy(status = status, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun configure(request: CushionConfigRequest) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, savedMessage = null)
            try {
                val status = cushionRepository.configure(request).getOrThrow()
                _uiState.value = _uiState.value.copy(
                    status = status,
                    isSaving = false,
                    savedMessage = "Matelas mis à jour",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    fun clearSavedMessage() {
        _uiState.value = _uiState.value.copy(savedMessage = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
