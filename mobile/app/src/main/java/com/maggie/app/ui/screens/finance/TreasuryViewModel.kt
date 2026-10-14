package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.TreasurySettingsRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.TreasuryOutlook
import com.maggie.app.data.repository.TreasuryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

const val NEGATIVE_LIMIT_MESSAGE = "La limite ne peut pas être négative"
const val INVALID_LIMIT_MESSAGE = "Saisissez un montant en euros"
const val INVALID_DAY_MESSAGE = "Le jour doit être compris entre 1 et 31"

data class TreasuryUiState(
    val outlook: TreasuryOutlook? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val savedMessage: String? = null,
    val error: String? = null,
    // The error is a sentence written for the user (a refused input), not a failed load.
    val errorIsMessage: Boolean = false,
)

class TreasuryViewModel(
    private val treasuryRepository: TreasuryRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TreasuryUiState())
    val uiState: StateFlow<TreasuryUiState> = _uiState

    init {
        refresh()
        subscribeToMercure()
    }

    // The advance prepared on the phone is a transaction: the outlook follows it.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.TRANSACTIONS))
                .catch { /* SSE reconnects automatically */ }
                .coalesced()
                .collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, errorIsMessage = false)
            try {
                val outlook = treasuryRepository.getOutlook().getOrThrow()
                _uiState.value = _uiState.value.copy(outlook = outlook, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "", isLoading = false)
            }
        }
    }

    fun saveOverdraftLimit(euros: String) {
        val amount = euros.trim().replace(',', '.').toDoubleOrNull()
        if (amount == null || !amount.isFinite()) return refuse(INVALID_LIMIT_MESSAGE)
        if (amount < 0) return refuse(NEGATIVE_LIMIT_MESSAGE)

        save(TreasurySettingsRequest(overdraftLimitCents = (amount * 100).roundToInt()), "Découvert autorisé enregistré")
    }

    fun saveSalaryDay(day: String) {
        val value = day.trim().toIntOrNull()
        if (value == null || value !in 1..31) return refuse(INVALID_DAY_MESSAGE)

        save(TreasurySettingsRequest(salaryExpectedDay = value), "Jour du salaire enregistré")
    }

    private fun refuse(message: String) {
        _uiState.value = _uiState.value.copy(error = message, errorIsMessage = true)
    }

    private fun save(request: TreasurySettingsRequest, message: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, savedMessage = null)
            try {
                treasuryRepository.updateSettings(request).getOrThrow()
                _uiState.value = _uiState.value.copy(isSaving = false, savedMessage = message)
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = e.message ?: "",
                    errorIsMessage = e is ApiRefusalException,
                )
            }
        }
    }

    fun clearSavedMessage() {
        _uiState.value = _uiState.value.copy(savedMessage = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null, errorIsMessage = false)
    }
}
