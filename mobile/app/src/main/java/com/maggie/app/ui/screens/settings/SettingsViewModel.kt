package com.maggie.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.fcm.PushTokenRegistrar
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
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

const val DEFAULT_AGENT_NAME = "Maggie"

private const val TASK_SETTINGS_UNREADABLE = "Impossible de lire les réglages des tâches."
private const val TASK_SETTINGS_UNSAVED = "Les réglages des tâches n'ont pas été enregistrés."

data class SettingsUiState(
    val user: User? = null,
    val preferences: UserPreference? = null,
    val agendas: List<Agenda> = emptyList(),
    val ttsVoices: List<TtsVoice> = emptyList(),
    val selectedTtsVoice: String = "fr-FR-DeniseNeural",
    /** What the agent's settings are called: the name the user gave her, « Maggie » by default (MAG-329). */
    val agentName: String = DEFAULT_AGENT_NAME,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Réglages › Tâches (MAG-407), loaded and failing on its own. */
    val tasks: TaskSettingsUiState = TaskSettingsUiState(),
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
        loadTaskSettings()
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
                val agentName = try { apiService.getAgentName().trim() } catch (_: Exception) { "" }
                _uiState.value = _uiState.value.copy(
                    user = user,
                    preferences = prefResult.getOrNull(),
                    agendas = agendas,
                    ttsVoices = ttsVoices,
                    selectedTtsVoice = selectedVoice,
                    agentName = agentName.ifEmpty { DEFAULT_AGENT_NAME },
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun loadTaskSettings() {
        viewModelScope.launch {
            updateTasks { it.copy(isLoading = true, error = null) }
            try {
                val settings = apiService.getTaskSettings()
                updateTasks {
                    it.copy(settings = settings, form = TaskSettingsForm.from(settings.values), isLoading = false)
                }
            } catch (e: Exception) {
                updateTasks { it.copy(isLoading = false, error = e.message ?: TASK_SETTINGS_UNREADABLE) }
            }
        }
    }

    /**
     * The form as the user edits it, [change] applied to the form as it is now (not as the
     * screen last drew it, so no keystroke is lost); nothing is sent before [saveTaskSettings].
     */
    fun editTaskSettings(change: (TaskSettingsForm) -> TaskSettingsForm) {
        updateTasks { tasks ->
            val form = tasks.form ?: return@updateTasks tasks
            tasks.copy(form = change(form), saved = false, error = null)
        }
    }

    /** « Valeurs par défaut »: the form takes the server's defaults, unsaved. */
    fun resetTaskSettingsToDefaults() {
        val defaults = _uiState.value.tasks.settings?.defaults ?: return
        editTaskSettings { TaskSettingsForm.from(defaults) }
    }

    /** « Enregistrer »: the whole form, replaced by what the server kept (it sorts the days, for one). */
    fun saveTaskSettings() {
        val tasks = _uiState.value.tasks
        val form = tasks.form ?: return
        if (tasks.isSaving) return
        val values = form.toValues().getOrElse { invalid ->
            updateTasks { it.copy(error = invalid.message, saved = false) }
            return
        }
        viewModelScope.launch {
            updateTasks { it.copy(isSaving = true, error = null, saved = false) }
            try {
                val settings = apiService.updateTaskSettings(values)
                updateTasks {
                    it.copy(
                        settings = settings,
                        form = TaskSettingsForm.from(settings.values),
                        isSaving = false,
                        saved = true,
                    )
                }
            } catch (e: Exception) {
                updateTasks { it.copy(isSaving = false, error = e.message ?: TASK_SETTINGS_UNSAVED) }
            }
        }
    }

    /**
     * A change published by the server (the admin, Maggie, this very save): read again. The
     * form follows only when it holds no edit of its own — a change elsewhere must not wipe
     * what the user is typing.
     */
    private suspend fun rereadTaskSettings() {
        val settings = try { apiService.getTaskSettings() } catch (_: Exception) { return }
        updateTasks { current ->
            val untouched = current.form == null ||
                current.settings?.let { current.form == TaskSettingsForm.from(it.values) } == true
            current.copy(
                settings = settings,
                form = if (untouched) TaskSettingsForm.from(settings.values) else current.form,
                isLoading = false,
            )
        }
    }

    private inline fun updateTasks(change: (TaskSettingsUiState) -> TaskSettingsUiState) {
        _uiState.value = _uiState.value.copy(tasks = change(_uiState.value.tasks))
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
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.TASK_SETTINGS))
                    .catch { /* SSE reconnects automatically */ }
                    .coalesced()
                    .collect { rereadTaskSettings() }
            }
            launch {
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.AGENDAS))
                    .catch { /* SSE reconnects automatically */ }
                    .coalesced()
                    .collect {
                        agendaRepository.refreshAgendas()
                            .onSuccess { _uiState.value = _uiState.value.copy(agendas = it) }
                    }
            }
        }
    }
}
