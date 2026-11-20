package com.maggie.app.ui.screens.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.DailyEnergy
import com.maggie.app.data.model.HealthProfile
import com.maggie.app.data.model.Workout
import com.maggie.app.data.repository.DailyEnergyRepository
import com.maggie.app.data.repository.HealthProfileRepository
import com.maggie.app.data.repository.WorkoutRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ActivityUiState(
    val isLoading: Boolean = false,
    /** False until the first answer: the screen shows a spinner. */
    val hasLoaded: Boolean = false,
    val error: String? = null,
    val goal: Int = 8000,
    val steps: Int = 0,
    /** The steps at the window's end at this pace, to the hundred; `null` when too early to say. */
    val projection: Int? = null,
    /** What the projection leaves to the goal; `null` once it reaches it. */
    val gap: Int? = null,
    /** The window's end, `HH:MM`, for « à 20 h ». */
    val windowEnd: String = "20:00",
    /** The time the figures were read, for « aujourd’hui à 12 h 40 ». */
    val readAt: LocalTime = LocalTime.MIDNIGHT,
    /** Today's walks and rides, done or planned. */
    val sessions: List<Workout> = emptyList(),
    /** Today's planned sessions of Sport, cited with « Sport › ». */
    val cited: List<Workout> = emptyList(),
    val week: List<WeekBar> = emptyList(),
    val noStepSource: Boolean = false,
)

/**
 * « Santé › Activité » (MAG-898): today's steps against the goal with their projection, the active sessions of the day
 * and the last seven days. Read from the profile, the daily energy and today's sessions; Mercure reloads the day when
 * a session, the day's activity or the profile moves. The [clock] says today and the time of the projection.
 */
class ActivityViewModel(
    private val profileRepository: HealthProfileRepository,
    private val energyRepository: DailyEnergyRepository,
    private val workoutRepository: WorkoutRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ActivityUiState(isLoading = true))
    val uiState: StateFlow<ActivityUiState> = _uiState

    val zone: ZoneId get() = clock.zone

    val today: LocalDate get() = LocalDate.now(clock)

    init {
        refresh()
        subscribeToMercure()
    }

    private fun subscribeToMercure() {
        listOf(MercureTopics.WORKOUTS, MercureTopics.DAILY_ACTIVITIES, MercureTopics.HEALTH_PROFILE).forEach { topic ->
            viewModelScope.launch {
                val userId = authRepository.getUserId() ?: return@launch
                mercureService.subscribe(MercureTopics.userScoped(userId, topic))
                    .catch { /* MercureService reconnects by itself */ }
                    .collect { load(showLoading = false) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { load(showLoading = true) }
    }

    private suspend fun load(showLoading: Boolean) {
        if (showLoading) _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        val day = today
        val after = day.atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val before = day.plusDays(1).atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val answers = coroutineScope {
            val profile = async { profileRepository.getProfile() }
            val energy = async { energyRepository.getDailyEnergy(day.minusDays(ACTIVITY_WEEK_DAYS - 1L).toString(), day.toString()) }
            val workouts = async { workoutRepository.getWorkoutsBetween(after, before) }
            Triple(profile.await(), energy.await(), workouts.await())
        }
        val (profile, energy, workouts) = answers
        if (profile.isFailure || energy.isFailure || workouts.isFailure) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = MESSAGE_LOAD)
            return
        }
        _uiState.value = build(profile.getOrThrow(), energy.getOrThrow(), workouts.getOrThrow(), day)
    }

    private fun build(
        profile: HealthProfile,
        energy: List<DailyEnergy>,
        workouts: List<Workout>,
        day: LocalDate,
    ): ActivityUiState {
        val steps = energy.firstOrNull { it.date == day.toString() }?.steps
        val now = LocalTime.now(clock)
        val projection = stepProjection(steps ?: 0, now, profile.suggestionWindowStart, profile.suggestionWindowEnd)
        return ActivityUiState(
            isLoading = false,
            hasLoaded = true,
            goal = profile.stepGoal,
            steps = steps ?: 0,
            projection = projection,
            gap = gapToGoal(projection, profile.stepGoal),
            windowEnd = profile.suggestionWindowEnd,
            readAt = now,
            sessions = activeSessions(workouts, day, zone),
            cited = plannedSportSessions(workouts, day, zone),
            week = weekBars(energy, profile.stepGoal, day),
            noStepSource = hasNoStepSource(profile, steps),
        )
    }

    companion object {
        const val MESSAGE_LOAD = "Impossible de charger l’activité."
    }
}
