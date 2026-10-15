package com.maggie.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.fcm.PushTokenRegistrar
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.AlertRule
import com.maggie.app.data.model.TaskSettings
import com.maggie.app.data.model.MealSettingsValues
import com.maggie.app.data.model.TtsVoice
import com.maggie.app.data.model.User
import com.maggie.app.data.model.UserPreference
import com.maggie.app.voice.VoiceManager
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

/** An alert rule as the API reads it, `overdueEveryDays: null` included. */
internal fun AlertRule.toJson(): JsonObject = buildJsonObject {
    put("daysBefore", JsonArray(daysBefore.map { JsonPrimitive(it) }))
    put("dueDay", dueDay)
    put("overdueEveryDays", overdueEveryDays?.let { JsonPrimitive(it) } ?: JsonNull)
}

const val DEFAULT_AGENT_NAME = "Maggie"

private const val TASK_SETTINGS_UNREADABLE = "Impossible de lire les réglages des tâches."
private const val TASK_SETTINGS_UNSAVED = "Impossible d'enregistrer les réglages des tâches"
private const val MEAL_SETTINGS_UNREADABLE = "Impossible de lire les réglages des repas."
private const val MEAL_SETTINGS_UNSAVED = "Les réglages des repas n'ont pas été enregistrés."

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
    /** Réglages › Repas (MAG-483), loaded when the page opens and failing on its own. */
    val meals: MealSettingsUiState = MealSettingsUiState(),
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

    /** What the server last answered; the screen goes back to it when a save fails. */
    private var confirmedTaskSettings: TaskSettings? = null

    /** Saves sent and not answered yet: until the last one, the screen keeps showing the user's changes. */
    private var pendingTaskSaves = 0

    /** One save at a time, in the order the user made the changes. */
    private val taskSaveLock = Mutex()

    fun loadTaskSettings() {
        viewModelScope.launch {
            updateTasks { it.copy(isLoading = true, error = null) }
            val settings = try {
                apiService.getTaskSettings()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateTasks { it.copy(isLoading = false, error = e.message ?: TASK_SETTINGS_UNREADABLE) }
                return@launch
            }
            confirmedTaskSettings = settings
            updateTasks { it.copy(settings = settings, isLoading = false) }
            refreshTaskOrderPreview()
        }
    }

    /** « Heure du matin », as the time picker gives it. */
    fun setMorningTime(hour: Int, minute: Int) {
        val time = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
        val current = _uiState.value.tasks.settings ?: return
        if (current.morningTime == time) return
        saveTaskSettings(current.copy(morningTime = time), buildJsonObject { put("morningTime", time) })
    }

    /** A « J-n » chip of [urgency]: the day on or off. */
    fun toggleAlertDay(urgency: String, days: Int) = changeAlertRule(urgency) { rule ->
        val next = TaskSettingsChoices.toggleDay(rule, days)
        rule.copy(daysBefore = next) to buildJsonObject { put("daysBefore", JsonArray(next.map { JsonPrimitive(it) })) }
    }

    /** The « Jour J » chip of [urgency]. */
    fun toggleDueDay(urgency: String) = changeAlertRule(urgency) { rule ->
        rule.copy(dueDay = !rule.dueDay) to buildJsonObject { put("dueDay", !rule.dueDay) }
    }

    /** « En retard » of [urgency]: every [every] days, or never (null — sent as `null`, not left out). */
    fun setOverdueEvery(urgency: String, every: Int?) = changeAlertRule(urgency) { rule ->
        rule.copy(overdueEveryDays = every) to buildJsonObject { put("overdueEveryDays", every?.let { JsonPrimitive(it) } ?: JsonNull) }
    }

    /** A slider of « Ce qui passe en premier »: saved, then the preview read again in the new order. */
    fun setOrderLevel(group: String, level: Int) {
        val current = _uiState.value.tasks.settings ?: return
        if (current.orderLevels[group] == level) return
        saveTaskSettings(
            current.copy(orderLevels = current.orderLevels + (group to level)),
            buildJsonObject { putJsonObject("orderLevels") { put(group, level) } },
            refreshesPreview = true,
        )
    }

    /** « Valeurs par défaut » of Réglages › Tâches: the alerts and the morning, not the order. */
    fun resetAlertsToDefaults() {
        val current = _uiState.value.tasks.settings ?: return
        val defaults = current.defaults
        saveTaskSettings(
            current.copy(alertSchedule = defaults.alertSchedule, morningTime = defaults.morningTime),
            buildJsonObject {
                put("alertSchedule", JsonObject(defaults.alertSchedule.mapValues { (_, rule) -> rule.toJson() }))
                put("morningTime", defaults.morningTime)
            },
        )
    }

    /** « Valeurs par défaut » of « Ce qui passe en premier »: the levels only. */
    fun resetOrderToDefaults() {
        val current = _uiState.value.tasks.settings ?: return
        val levels = current.defaults.orderLevels
        saveTaskSettings(
            current.copy(orderLevels = levels),
            buildJsonObject { put("orderLevels", JsonObject(levels.mapValues { JsonPrimitive(it.value) })) },
            refreshesPreview = true,
        )
    }

    private inline fun changeAlertRule(urgency: String, change: (AlertRule) -> Pair<AlertRule, JsonObject>) {
        val current = _uiState.value.tasks.settings ?: return
        val rule = current.alertSchedule[urgency] ?: return
        val (next, body) = change(rule)
        saveTaskSettings(
            current.copy(alertSchedule = current.alertSchedule + (urgency to next)),
            buildJsonObject { putJsonObject("alertSchedule") { put(urgency, body) } },
        )
    }

    /**
     * Shows [shown] at once and sends [changes]. The server's answer replaces it once no other
     * save is on its way; a failure says why and goes back to what the server holds.
     */
    private fun saveTaskSettings(shown: TaskSettings, changes: JsonObject, refreshesPreview: Boolean = false) {
        updateTasks { it.copy(settings = shown, saved = false, error = null) }
        pendingTaskSaves++
        viewModelScope.launch {
            val settings = try {
                taskSaveLock.withLock { apiService.updateTaskSettings(changes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingTaskSaves--
                val reason = if (e is ApiRefusalException && e.status == 400) e.message else null
                updateTasks {
                    it.copy(settings = confirmedTaskSettings ?: it.settings, saved = false, error = reason ?: TASK_SETTINGS_UNSAVED)
                }
                return@launch
            }
            pendingTaskSaves--
            confirmedTaskSettings = settings
            if (pendingTaskSaves == 0) updateTasks { it.copy(settings = settings, saved = it.error == null) }
            if (refreshesPreview) refreshTaskOrderPreview()
        }
    }

    /**
     * The five tasks read again; the arrows say how each moved against the preview shown before.
     * A preview in the order already shown leaves the arrows as they are: the save's own refresh
     * and the Mercure echo of that save read the same order, whichever lands first.
     */
    private suspend fun refreshTaskOrderPreview() {
        val next = try {
            apiService.getTaskOrderPreview()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        updateTasks { tasks ->
            val sameOrder = next.map { it.id } == tasks.preview.map { it.id }
            val moves = if (sameOrder) tasks.moves else TaskSettingsChoices.moves(tasks.preview, next)
            tasks.copy(preview = next, moves = moves)
        }
    }

    /**
     * A change published by the server (the admin, Maggie, this very save): read again and
     * shown, unless a save of this screen is still on its way — its answer comes next.
     */
    private suspend fun rereadTaskSettings() {
        val settings = try {
            apiService.getTaskSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        // While a save is on its way, its answer is newer than this read: it alone becomes what a failure goes back to.
        if (pendingTaskSaves == 0) {
            confirmedTaskSettings = settings
            updateTasks { it.copy(settings = settings, isLoading = false, error = if (it.settings == null) null else it.error) }
        }
        refreshTaskOrderPreview()
    }

    fun loadMealSettings() {
        viewModelScope.launch {
            updateMeals { it.copy(isLoading = true, error = null) }
            try {
                val settings = apiService.getMealSettings()
                updateMeals { it.copy(settings = settings, guests = settings.defaultGuests, isLoading = false) }
            } catch (e: Exception) {
                updateMeals { it.copy(isLoading = false, error = e.message ?: MEAL_SETTINGS_UNREADABLE) }
            }
        }
    }

    /** « − » and « + » of the default number of guests: never under one; nothing is sent before [saveMealSettings]. */
    fun stepDefaultGuests(delta: Int) {
        updateMeals { meals ->
            val guests = meals.guests ?: return@updateMeals meals
            meals.copy(guests = (guests + delta).coerceAtLeast(1), saved = false, error = null)
        }
    }

    fun saveMealSettings() {
        val meals = _uiState.value.meals
        val guests = meals.guests ?: return
        if (meals.isSaving) return
        viewModelScope.launch {
            updateMeals { it.copy(isSaving = true, error = null, saved = false) }
            try {
                val settings = apiService.updateMealSettings(MealSettingsValues(guests))
                updateMeals { it.copy(settings = settings, guests = settings.defaultGuests, isSaving = false, saved = true) }
            } catch (e: Exception) {
                updateMeals { it.copy(isSaving = false, error = e.message ?: MEAL_SETTINGS_UNSAVED) }
            }
        }
    }

    // The number follows only when the user holds no edit of their own.
    private suspend fun rereadMealSettings() {
        val settings = try { apiService.getMealSettings() } catch (_: Exception) { return }
        updateMeals { current ->
            current.copy(
                settings = settings,
                guests = if (current.isDirty) current.guests else settings.defaultGuests,
                isLoading = false,
            )
        }
    }

    private inline fun updateMeals(change: (MealSettingsUiState) -> MealSettingsUiState) {
        _uiState.value = _uiState.value.copy(meals = change(_uiState.value.meals))
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
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.MEAL_SETTINGS))
                    .catch { /* SSE reconnects automatically */ }
                    .coalesced()
                    .collect { rereadMealSettings() }
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
