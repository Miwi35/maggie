package com.maggie.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.User
import com.maggie.app.data.model.UserPreference
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class SettingsUiState(
    val user: User? = null,
    val preferences: UserPreference? = null,
    val agendas: List<Agenda> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class SettingsViewModel(
    private val apiService: MaggieApiService,
    private val authRepository: AuthRepository,
    private val userPreferenceRepository: UserPreferenceRepository,
    private val agendaRepository: AgendaRepository,
    private val mercureService: MercureService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    val themePreference: StateFlow<UserPreference?> = userPreferenceRepository.preference

    init {
        loadProfile()
        subscribeToMercure()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val user = apiService.getMe()
                val prefResult = userPreferenceRepository.refresh()
                val agendas = agendaRepository.getAgendas()
                _uiState.value = _uiState.value.copy(
                    user = user,
                    preferences = prefResult.getOrNull(),
                    agendas = agendas,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun updateTheme(theme: String) {
        viewModelScope.launch {
            userPreferenceRepository.update(buildJsonObject { put("theme", theme) })
                .onSuccess { _uiState.value = _uiState.value.copy(preferences = it) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun updateTimezone(timezone: String) {
        viewModelScope.launch {
            userPreferenceRepository.update(buildJsonObject { put("timezone", timezone) })
                .onSuccess { _uiState.value = _uiState.value.copy(preferences = it) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun updateDefaultCalendarView(view: String) {
        viewModelScope.launch {
            userPreferenceRepository.update(buildJsonObject { put("defaultCalendarView", view) })
                .onSuccess { _uiState.value = _uiState.value.copy(preferences = it) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun toggleAgenda(agendaId: String) {
        val current = _uiState.value.preferences?.enabledAgendaIds ?: return
        val next = if (agendaId in current) current - agendaId else current + agendaId
        viewModelScope.launch {
            userPreferenceRepository.update(buildJsonObject {
                put("enabledAgendaIds", JsonArray(next.map { JsonPrimitive(it) }))
            })
                .onSuccess { _uiState.value = _uiState.value.copy(preferences = it) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun toggleNotifications() {
        val current = _uiState.value.preferences?.notificationsEnabled ?: return
        viewModelScope.launch {
            userPreferenceRepository.update(buildJsonObject { put("notificationsEnabled", !current) })
                .onSuccess { _uiState.value = _uiState.value.copy(preferences = it) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun updateName(name: String) {
        val userId = _uiState.value.user?.id ?: return
        viewModelScope.launch {
            try {
                val updated = apiService.updateUser(userId, buildJsonObject { put("name", name) })
                _uiState.value = _uiState.value.copy(user = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.clear()
        }
    }

    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe("/users/$userId/api/user_preferences/{id}")
                .catch { /* SSE reconnects automatically */ }
                .collect {
                    userPreferenceRepository.refresh()
                    _uiState.value = _uiState.value.copy(preferences = userPreferenceRepository.preference.value)
                }
        }
    }
}
