package com.maggie.app.ui.screens.login

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
)

class LoginViewModel(private val authManager: AuthManager) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    fun signIn(context: Context) {
        viewModelScope.launch {
            _uiState.value = LoginUiState(isLoading = true)
            authManager.signIn(context)
                .onSuccess {
                    _uiState.value = LoginUiState()
                }
                .onFailure { e ->
                    _uiState.value = LoginUiState(error = e.message ?: "Erreur de connexion")
                }
        }
    }
}
