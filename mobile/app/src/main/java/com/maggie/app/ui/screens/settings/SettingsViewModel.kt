package com.maggie.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.fcm.PushTokenRegistrar
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.TtsVoice
import com.maggie.app.data.model.User
import com.maggie.app.data.model.UserPreference
import com.maggie.app.voice.VoiceManager
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
    val ttsVoices: List<TtsVoice> = emptyList(),
    val selectedTtsVoice: String = "fr-FR-DeniseNeural",
    val isLoading: Boolean = false,
    val error: String? = null,
)

class SettingsViewModel(
    private val apiService: MaggieApiService,
    private val authRepository: AuthRepository,
    private val userPreferenceRepository: UserPreferenceRepository,
    private val agendaRepository: AgendaRepository,
    private val mercureService: MercureService,
    private val voiceManager: VoiceManager,
    private val pushTokenRegistrar: PushTokenRegistrar,
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
                val ttsVoices = try { apiService.getTtsVoices() } catch (_: Exception) { emptyList() }
                val selectedVoice = try { apiService.getTtsVoice() } catch (_: Exception) { "fr-FR-DeniseNeural" }
                _uiState.value = _uiState.value.copy(
                    user = user,
                    preferences = prefResult.getOrNull(),
                    agendas = agendas,
                    ttsVoices = ttsVoices,
                    selectedTtsVoice = selectedVoice,
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

    fun updateDefaultAgenda(agendaId: String) {
        viewModelScope.launch {
            agendaRepository.setDefaultAgenda(agendaId)
                .onSuccess { _uiState.value = _uiState.value.copy(agendas = it) }
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

    fun updateTtsVoice(voice: String) {
        viewModelScope.launch {
            try {
                apiService.setTtsVoice(voice)
                voiceManager.setVoice(voice)
                _uiState.value = _uiState.value.copy(selectedTtsVoice = voice)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun previewVoice(voiceId: String) {
        voiceManager.stopSpeaking()
        viewModelScope.launch {
            try {
                val audioBytes = apiService.synthesizeSpeech("Bonjour, je suis Maggie, votre assistante personnelle.", voiceId)
                val tempFile = java.io.File.createTempFile("tts_preview", ".mp3")
                tempFile.writeBytes(audioBytes)
                val player = android.media.MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    prepare()
                    setOnCompletionListener {
                        it.release()
                        tempFile.delete()
                    }
                    start()
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            // While the credentials still exist: the server takes the removal from the signed-in user only.
            pushTokenRegistrar.unregister()
            authRepository.clear()
        }
    }

    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            launch {
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.USER_PREFERENCES))
                    .catch { /* SSE reconnects automatically */ }
                    .collect {
                        userPreferenceRepository.refresh()
                        _uiState.value = _uiState.value.copy(preferences = userPreferenceRepository.preference.value)
                    }
            }
            launch {
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.AGENDAS))
                    .catch { /* SSE reconnects automatically */ }
                    .collect {
                        agendaRepository.refreshAgendas()
                            .onSuccess { _uiState.value = _uiState.value.copy(agendas = it) }
                    }
            }
        }
    }
}
