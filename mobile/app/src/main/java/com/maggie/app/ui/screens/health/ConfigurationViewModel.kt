package com.maggie.app.ui.screens.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.ActivityLevel
import com.maggie.app.data.model.HealthProfile
import com.maggie.app.data.repository.HealthProfileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Clock
import java.time.LocalDate

/** The pause after the last tap of a stepper before its one `PATCH` leaves (MAG-707). */
internal const val PROFILE_WRITE_DEBOUNCE_MS = 600L

data class ConfigurationUiState(
    /** What the screen shows: the API's profile with the edits not yet sent laid over it. */
    val profile: HealthProfile? = null,
    val isLoading: Boolean = false,
    /** False until the first answer: the screen shows a spinner, not an empty card. */
    val hasLoaded: Boolean = false,
    val error: String? = null,
)

/**
 * « Santé › Configuration » (MAG-707): the profile, the day's need and the activity settings. A stepper shows its
 * value at once and writes after [PROFILE_WRITE_DEBOUNCE_MS] — one `PATCH` of its field for a run of taps; a chip, a
 * toggle or a time writes at once. A refused write puts the API's value back. The `health-profile` topic carries the
 * whole profile (a weighing made elsewhere moves it): it replaces the state, the edits still to send laid over it.
 * The [clock] says the current year an age is turned into a birth year with.
 */
class ConfigurationViewModel(
    private val repository: HealthProfileRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConfigurationUiState(isLoading = true))
    val uiState: StateFlow<ConfigurationUiState> = _uiState

    /** The last profile the API sent. */
    private var confirmed: HealthProfile? = null

    /** The steppers' edits waiting for their pause, by field. */
    private val unsent = LinkedHashMap<String, Edit>()
    private val pendingJobs = HashMap<String, Job>()

    private class Edit(val body: JsonObject, val apply: (HealthProfile) -> HealthProfile)

    init {
        refresh()
        subscribeToMercure()
    }

    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.HEALTH_PROFILE))
                .catch { /* MercureService reconnects by itself */ }
                .collect { event ->
                    val pushed = runCatching { json.decodeFromString(HealthProfile.serializer(), event.data) }.getOrNull()
                        ?: return@collect
                    accept(pushed)
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.getProfile()
                .onSuccess { profile ->
                    _uiState.value = _uiState.value.copy(isLoading = false, hasLoaded = true)
                    accept(profile)
                }
                .onFailure { _uiState.value = _uiState.value.copy(isLoading = false, error = MESSAGE_LOAD) }
        }
    }

    private fun accept(profile: HealthProfile) {
        confirmed = profile
        _uiState.value = _uiState.value.copy(profile = withUnsent(profile))
    }

    private fun withUnsent(profile: HealthProfile): HealthProfile =
        if (unsent.isEmpty()) profile else unsent.values.fold(profile) { p, edit -> edit.apply(p) }.recomputed()

    // — The steppers: shown now, written after the pause.

    fun stepAge(delta: Int) {
        val profile = _uiState.value.profile ?: return
        val age = ((profile.age ?: START_AGE) + delta).coerceIn(AGE_RANGE)
        val birthYear = LocalDate.now(clock).year - age
        schedule("birthYear", body("birthYear", JsonPrimitive(birthYear))) { it.copy(age = age, birthYear = birthYear) }
    }

    fun stepHeight(delta: Int) {
        val profile = _uiState.value.profile ?: return
        val height = ((profile.heightCm ?: START_HEIGHT_CM) + delta).coerceIn(HEIGHT_RANGE)
        schedule("heightCm", body("heightCm", JsonPrimitive(height))) { it.copy(heightCm = height) }
    }

    /** ±1 kg from the weight shown (72,4 → 73,4): the API records it as a manual weighing. */
    fun stepWeight(delta: Int) {
        val profile = _uiState.value.profile ?: return
        val weight = (Math.round(((profile.weightKg ?: START_WEIGHT_KG) + delta) * 10) / 10.0).coerceIn(WEIGHT_RANGE)
        schedule("weightKg", body("weightKg", JsonPrimitive(weight))) { it.copy(weightKg = weight) }
    }

    fun stepEnteredNeed(delta: Int) {
        val profile = _uiState.value.profile ?: return
        val need = ((profile.enteredNeedKcal ?: profile.needKcal ?: DEFAULT_ENTERED_NEED) + delta * NEED_STEP).coerceIn(NEED_RANGE)
        schedule("enteredNeedKcal", body("enteredNeedKcal", JsonPrimitive(need))) { it.copy(enteredNeedKcal = need) }
    }

    fun stepStepGoal(delta: Int) {
        val profile = _uiState.value.profile ?: return
        val goal = (profile.stepGoal + delta * STEP_GOAL_STEP).coerceIn(STEP_GOAL_RANGE)
        schedule("stepGoal", body("stepGoal", JsonPrimitive(goal))) { it.copy(stepGoal = goal) }
    }

    // — The choices: written at once.

    fun setSex(sex: String) = writeNow(body("sex", JsonPrimitive(sex))) { it.copy(sex = sex) }

    fun setActivityLevel(level: ActivityLevel) =
        writeNow(body("activityLevel", JsonPrimitive(level.key))) { it.copy(activityLevel = level.key) }

    /**
     * « Calculé · Saisi ». Into Saisi, the need shown becomes the typed one, rounded to 50 kcal within 1 000..5 000
     * (2 000 without one); back to Calculé, the typed need stays on the API's side.
     */
    fun setEntered(entered: Boolean) {
        val profile = _uiState.value.profile ?: return
        if (profile.isEntered == entered) return
        if (entered) {
            val need = profile.needKcal?.let { (Math.round(it / NEED_STEP.toDouble()) * NEED_STEP).toInt().coerceIn(NEED_RANGE) }
                ?: DEFAULT_ENTERED_NEED
            val changes = buildJsonObject {
                put("mode", JsonPrimitive(HealthProfile.MODE_ENTERED))
                put("enteredNeedKcal", JsonPrimitive(need))
            }
            writeNow(changes) { it.copy(mode = HealthProfile.MODE_ENTERED, enteredNeedKcal = need) }
        } else {
            writeNow(body("mode", JsonPrimitive(HealthProfile.MODE_COMPUTED))) { it.copy(mode = HealthProfile.MODE_COMPUTED) }
        }
    }

    fun setWindowStart(hour: Int, minute: Int) {
        val time = hhmm(hour, minute)
        writeNow(body("suggestionWindowStart", JsonPrimitive(time))) { it.copy(suggestionWindowStart = time) }
    }

    fun setWindowEnd(hour: Int, minute: Int) {
        val time = hhmm(hour, minute)
        writeNow(body("suggestionWindowEnd", JsonPrimitive(time))) { it.copy(suggestionWindowEnd = time) }
    }

    fun setSessionMinutes(minutes: Int) =
        writeNow(body("sessionMinutes", JsonPrimitive(minutes))) { it.copy(sessionMinutes = minutes) }

    fun setStepSource(source: String) =
        writeNow(body("stepSource", JsonPrimitive(source))) { it.copy(stepSource = source) }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun schedule(field: String, changes: JsonObject, apply: (HealthProfile) -> HealthProfile) {
        val profile = _uiState.value.profile ?: return
        unsent[field] = Edit(changes, apply)
        _uiState.value = _uiState.value.copy(profile = apply(profile).recomputed(), error = null)
        pendingJobs[field]?.cancel()
        pendingJobs[field] = viewModelScope.launch {
            delay(PROFILE_WRITE_DEBOUNCE_MS)
            val edit = unsent.remove(field) ?: return@launch
            pendingJobs.remove(field)
            send(edit.body)
        }
    }

    private fun writeNow(changes: JsonObject, apply: (HealthProfile) -> HealthProfile) {
        val profile = _uiState.value.profile ?: return
        _uiState.value = _uiState.value.copy(profile = apply(profile).recomputed(), error = null)
        viewModelScope.launch { send(changes) }
    }

    private suspend fun send(changes: JsonObject) {
        repository.update(changes)
            .onSuccess(::accept)
            .onFailure {
                val back = confirmed
                _uiState.value = _uiState.value.copy(profile = back?.let(::withUnsent) ?: _uiState.value.profile, error = MESSAGE_SAVE)
            }
    }

    private fun body(field: String, value: JsonPrimitive) = buildJsonObject { put(field, value) }

    private fun hhmm(hour: Int, minute: Int) = "%02d:%02d".format(java.util.Locale.ROOT, hour, minute)

    companion object {
        const val MESSAGE_LOAD = "Le profil n'a pas pu être chargé."
        const val MESSAGE_SAVE = "La modification n'a pas été enregistrée."

        const val START_AGE = 30
        const val START_HEIGHT_CM = 170
        const val START_WEIGHT_KG = 70.0
        const val DEFAULT_ENTERED_NEED = 2000
        const val NEED_STEP = 50
        const val STEP_GOAL_STEP = 500
        val AGE_RANGE = 14..99
        val HEIGHT_RANGE = 120..220
        val WEIGHT_RANGE = 35.0..200.0
        val NEED_RANGE = 1000..5000
        val STEP_GOAL_RANGE = 500..50000

        private val json = Json { ignoreUnknownKeys = true }
    }
}
