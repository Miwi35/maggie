package com.maggie.app.ui.screens.health

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.DailyEnergy
import com.maggie.app.data.model.HealthProfile
import com.maggie.app.data.model.Workout
import com.maggie.app.data.repository.DailyEnergyRepository
import com.maggie.app.data.repository.HealthProfileRepository
import com.maggie.app.data.repository.WorkoutRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** « Santé › Activité » (MAG-898): one test per transition — load, failure and retry, Mercure, the goal, no source. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val profileRepository = mockk<HealthProfileRepository>()
    private val energyRepository = mockk<DailyEnergyRepository>()
    private val workoutRepository = mockk<WorkoutRepository>()
    private val authRepository = mockk<AuthRepository>()
    private val mercureService = mockk<MercureService>()
    private val pushes = MutableSharedFlow<MercureEvent>()

    // Saturday 7 November 2026, 12:40 in Paris.
    private val clock = Clock.fixed(Instant.parse("2026-11-07T11:40:00Z"), ZoneId.of("Europe/Paris"))

    private val profile = HealthProfile(stepGoal = 8000, stepSource = HealthProfile.STEP_SOURCE_HEALTH_CONNECT)
    private val energy = listOf(DailyEnergy("2026-11-06", steps = 5000), DailyEnergy("2026-11-07", steps = 2100))
    private val walk = Workout("w-1", "walk", "Marche", "2026-11-07T08:30:00+01:00", 25, kcal = 110, source = "activity")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { profileRepository.getProfile() } returns Result.success(profile)
        coEvery { energyRepository.getDailyEnergy(any(), any()) } returns Result.success(energy)
        coEvery { workoutRepository.getWorkoutsBetween(any(), any()) } returns Result.success(listOf(walk))
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns pushes
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ActivityViewModel(profileRepository, energyRepository, workoutRepository, mercureService, authRepository, clock)

    @Test
    fun `loading then ready`() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasLoaded)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.hasLoaded)
        assertEquals(2100, state.steps)
        assertEquals(5400, state.projection)
        assertEquals(2600, state.gap)
        assertEquals(listOf(walk), state.sessions)
        assertEquals(7, state.week.size)
        assertFalse(state.noStepSource)
        assertNull(state.error)
    }

    @Test
    fun `error then retry`() = runTest {
        coEvery { energyRepository.getDailyEnergy(any(), any()) } returns Result.failure(RuntimeException("offline"))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.hasLoaded)
        assertEquals(ActivityViewModel.MESSAGE_LOAD, viewModel.uiState.value.error)

        coEvery { energyRepository.getDailyEnergy(any(), any()) } returns Result.success(energy)
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasLoaded)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a workout pushed by Mercure reloads the day`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        val bike = Workout("w-2", "bike", "Vélo", "2026-11-07T17:00:00+01:00", 40, kcal = 200, source = "planned")
        coEvery { workoutRepository.getWorkoutsBetween(any(), any()) } returns Result.success(listOf(walk, bike))

        pushes.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals(listOf(walk, bike), viewModel.uiState.value.sessions)
        verify { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.WORKOUTS)) }
        verify { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.DAILY_ACTIVITIES)) }
    }

    @Test
    fun `a profile change moves the goal`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(8000, viewModel.uiState.value.goal)
        coEvery { profileRepository.getProfile() } returns Result.success(profile.copy(stepGoal = 10000))

        pushes.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals(10000, viewModel.uiState.value.goal)
        assertEquals(4600, viewModel.uiState.value.gap)
        verify { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.HEALTH_PROFILE)) }
    }

    @Test
    fun `no step source state`() = runTest {
        coEvery { profileRepository.getProfile() } returns Result.success(HealthProfile(stepSource = HealthProfile.STEP_SOURCE_NONE))
        coEvery { energyRepository.getDailyEnergy(any(), any()) } returns Result.success(emptyList())
        val viewModel = viewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.noStepSource)

        coEvery { energyRepository.getDailyEnergy(any(), any()) } returns Result.success(energy)
        pushes.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.noStepSource)
        assertEquals(2100, viewModel.uiState.value.steps)
    }
}
