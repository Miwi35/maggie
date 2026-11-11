package com.maggie.app.ui.screens.health.sport

import androidx.lifecycle.SavedStateHandle
import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import com.maggie.app.data.model.Workout
import com.maggie.app.data.model.WorkoutExercise
import com.maggie.app.data.model.WorkoutSetDone
import com.maggie.app.data.model.WorkoutSource
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.data.repository.WorkoutRepository
import com.maggie.app.ui.navigation.DeepLinks
import com.maggie.app.ui.navigation.Screen
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class SportActivityTest {

    private val dispatcher = StandardTestDispatcher()
    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 11, 11)
    private lateinit var workouts: WorkoutRepository
    private lateinit var status: ActivityStatusRepository

    private fun exercise(slug: String, name: String, step: Int = 0, load: Double? = null, done: List<WorkoutSetDone> = emptyList()) =
        WorkoutExercise(exercise = slug, name = name, step = step, sets = 3, target = 4, loadKg = load, done = done)

    private fun session(
        id: String = "w1",
        startedAt: String = "2026-11-11T18:30:00+01:00",
        type: String = "strength",
        source: String = WorkoutSource.PLANNED,
        exercises: List<WorkoutExercise> = listOf(
            exercise("tractions-negatives", "Tractions négatives", step = 4),
            exercise("pompes", "Pompes", load = 20.0),
            exercise("gainage", "Gainage"),
        ),
    ) = Workout(id = id, type = type, title = "Haut du corps", startedAt = startedAt, durationMinutes = 30, source = source, exercises = exercises)

    private fun saved(workoutId: String, exerciseIndex: Int, count: Int = 3) = ActivityStatus(
        activity = SPORT_ACTIVITY,
        origin = ActivityOrigin(SPORT_ORIGIN_KIND, workoutId),
        state = JsonObject(
            mapOf(
                "exerciseIndex" to kotlinx.serialization.json.JsonPrimitive(exerciseIndex),
                "done" to kotlinx.serialization.json.JsonArray(List(count) { kotlinx.serialization.json.JsonArray(emptyList()) }),
            ),
        ),
        startedAt = "2026-11-11T17:00:00+00:00",
        updatedAt = "2026-11-11T17:00:00+00:00",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        workouts = mockk()
        status = mockk()
        every { status.put(any(), any(), any()) } returns Unit
        coEvery { status.get(SPORT_ACTIVITY) } returns Result.success(null)
        coEvery { workouts.getWorkoutsWithExercise(any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(workoutId: String = "w1", event: String? = null) = SportActivityViewModel(
        SavedStateHandle(mapOf(DeepLinks.ARG_WORKOUT to workoutId, DeepLinks.ARG_EVENT to event)),
        workouts,
        status,
    )

    // --- the activity ------------------------------------------------------------------------------------------

    @Test
    fun `launching a session writes the starting status and opens its first exercise`() = runTest(dispatcher) {
        coEvery { workouts.getWorkout("w1") } returns Result.success(session())
        val origin = slot<ActivityOrigin>()
        val state = slot<JsonObject>()
        every { status.put(SPORT_ACTIVITY, capture(origin), capture(state)) } returns Unit

        val vm = viewModel()
        advanceUntilIdle()

        val ui = vm.uiState.value
        assertEquals(SportLoad.READY, ui.load)
        assertEquals(0, ui.exerciseIndex)
        assertEquals("Tractions négatives", ui.current?.name)
        assertEquals("1 / 3", exerciseCounter(ui.exerciseIndex, ui.count))
        assertEquals("Échelon 4", stepLabel(ui.current!!))
        assertEquals(ActivityOrigin("workout", "w1"), origin.captured)
        assertEquals(0, state.captured["exerciseIndex"]!!.jsonPrimitive.int)
        assertEquals("work", state.captured["phase"]!!.jsonPrimitive.content)
        assertEquals(3, state.captured["done"]!!.jsonArray.size)
        assertEquals(listOf("null", "20.0", "null"), state.captured["loadKg"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a status on this session resumes at its exercise without being rewritten`() = runTest(dispatcher) {
        coEvery { workouts.getWorkout("w1") } returns Result.success(session())
        coEvery { status.get(SPORT_ACTIVITY) } returns Result.success(saved("w1", exerciseIndex = 1))

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.exerciseIndex)
        assertEquals("Pompes", vm.uiState.value.current?.name)
        verify(exactly = 0) { status.put(any(), any(), any()) }
    }

    @Test
    fun `a status on another session is replaced by the starting state`() = runTest(dispatcher) {
        coEvery { workouts.getWorkout("w1") } returns Result.success(session())
        coEvery { status.get(SPORT_ACTIVITY) } returns Result.success(saved("other", exerciseIndex = 2))

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.exerciseIndex)
        verify(exactly = 1) { status.put(SPORT_ACTIVITY, ActivityOrigin("workout", "w1"), any()) }
    }

    @Test
    fun `no session or no exercise opens nothing and writes nothing`() = runTest(dispatcher) {
        coEvery { workouts.getWorkout("w1") } returns Result.success(null)
        val missing = viewModel()
        coEvery { workouts.getWorkout("w2") } returns Result.success(session(id = "w2", exercises = emptyList()))
        val empty = viewModel("w2")
        advanceUntilIdle()

        assertEquals(SportLoad.NOT_FOUND, missing.uiState.value.load)
        assertEquals(SportLoad.NOT_FOUND, empty.uiState.value.load)
        verify(exactly = 0) { status.put(any(), any(), any()) }
    }

    @Test
    fun `an unreachable API shows the error and retrying loads the session`() = runTest(dispatcher) {
        coEvery { workouts.getWorkout("w1") } returns Result.failure(RuntimeException("offline"))
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(SportLoad.ERROR, vm.uiState.value.load)
        verify(exactly = 0) { status.put(any(), any(), any()) }

        coEvery { workouts.getWorkout("w1") } returns Result.success(session())
        vm.retry()
        advanceUntilIdle()
        assertEquals(SportLoad.READY, vm.uiState.value.load)
    }

    @Test
    fun `a status that cannot be read never overwrites the progress`() = runTest(dispatcher) {
        coEvery { workouts.getWorkout("w1") } returns Result.success(session())
        coEvery { status.get(SPORT_ACTIVITY) } returns Result.failure(RuntimeException("offline"))

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(SportLoad.ERROR, vm.uiState.value.load)
        verify(exactly = 0) { status.put(any(), any(), any()) }
    }

    @Test
    fun `launching by event opens the session filed under it`() = runTest(dispatcher) {
        coEvery { workouts.getWorkoutOfEvent("e1") } returns Result.success(session())

        val vm = viewModel(workoutId = SportActivityViewModel.NO_WORKOUT, event = "e1")
        advanceUntilIdle()

        assertEquals("Tractions négatives", vm.uiState.value.current?.name)
    }

    @Test
    fun `the last time of an exercise comes from the latest session done that held it`() = runTest(dispatcher) {
        coEvery { workouts.getWorkout("w1") } returns Result.success(session())
        val sets = List(3) { WorkoutSetDone(reps = 4) }
        coEvery { workouts.getWorkoutsWithExercise("tractions-negatives") } returns Result.success(
            listOf(
                session(id = "w1"),
                session(id = "planned-later", startedAt = "2026-11-18T18:30:00+01:00"),
                session(
                    id = "done",
                    source = WorkoutSource.MANUAL,
                    exercises = listOf(exercise("tractions-negatives", "Tractions négatives", done = sets)),
                ),
            ),
        )

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("3 × 4", vm.uiState.value.lastTimes["tractions-negatives"])
        assertNull(vm.uiState.value.lastTimes["pompes"])
    }

    @Test
    fun `quitting asks first and the status stays`() = runTest(dispatcher) {
        coEvery { workouts.getWorkout("w1") } returns Result.success(session())
        val vm = viewModel()
        advanceUntilIdle()

        vm.askQuit()
        assertTrue(vm.uiState.value.quitAsked)
        vm.dismissQuit()
        assertFalse(vm.uiState.value.quitAsked)
        verify(exactly = 0) { status.delete(any()) }
    }

    // --- the launch card ---------------------------------------------------------------------------------------

    private val now = Instant.parse("2026-11-11T17:09:00Z")

    @Test
    fun `a session of today with exercises is launched from a card that tells its moment`() {
        val launch = sportLaunchOf(listOf(session()), today, zone, null, now)!!

        assertEquals("w1", launch.workoutId)
        assertEquals("Séance de ce soir", launch.title)
        assertEquals("Ce soir · 18 h 30 · 30 min · 3 exercices", launch.subtitle)
        assertEquals("Lancer", launch.buttonLabel)
        assertFalse(launch.resuming)
    }

    @Test
    fun `a session in progress says where it is and offers to resume`() {
        val resume = saved("w1", exerciseIndex = 1, count = 6).toSportResume()
        val launch = sportLaunchOf(listOf(session()), today, zone, resume, now)!!

        assertEquals("En cours · exercice 2 / 6 · 9 min", launch.subtitle)
        assertEquals("▶ Reprendre · 2/6", launch.buttonLabel)
        assertTrue(launch.resuming)
    }

    @Test
    fun `a status on another session leaves the card at Lancer`() {
        val launch = sportLaunchOf(listOf(session()), today, zone, saved("other", 1).toSportResume(), now)!!

        assertEquals("Lancer", launch.buttonLabel)
    }

    @Test
    fun `tomorrow, a session without exercises, a done one or another type draw no card`() {
        assertNull(sportLaunchOf(listOf(session(startedAt = "2026-11-12T18:30:00+01:00")), today, zone, null, now))
        assertNull(sportLaunchOf(listOf(session(exercises = emptyList())), today, zone, null, now))
        assertNull(sportLaunchOf(listOf(session(source = WorkoutSource.MANUAL)), today, zone, null, now))
        assertNull(sportLaunchOf(listOf(session(type = "running")), today, zone, null, now))
        assertNull(sportLaunchOf(emptyList(), today, zone, null, now))
    }

    // --- labels and routes -------------------------------------------------------------------------------------

    @Test
    fun `the moment of the day follows the hour`() {
        assertEquals("Ce matin", momentLabel(LocalTime.of(7, 0)))
        assertEquals("Cet après-midi", momentLabel(LocalTime.of(12, 0)))
        assertEquals("Cet après-midi", momentLabel(LocalTime.of(17, 59)))
        assertEquals("Ce soir", momentLabel(LocalTime.of(18, 0)))
        assertEquals("1 exercice", exerciseCountLabel(1))
        assertEquals("6 exercices", exerciseCountLabel(6))
        assertEquals("9 h", clockLabel(9, 0))
        assertEquals("18 h 05", clockLabel(18, 5))
    }

    @Test
    fun `a status is a resume only for a session it can read`() {
        assertEquals("2/6", saved("w1", 1, 6).toSportResume()?.progress)
        assertNull(saved("w1", exerciseIndex = 9, count = 3).toSportResume())
        assertNull(saved("w1", 0).copy(origin = ActivityOrigin("list", "w1")).toSportResume())
        assertNotNull(saved("w1", 0).toSportResume())
    }

    @Test
    fun `the activity has its route and its deep link`() {
        assertEquals("health/sport/activity/w1", Screen.SportActivity.of("w1"))
        assertEquals("health/sport/activity/-?event=e1", Screen.SportActivity.ofEvent("e1"))
        assertTrue(DeepLinks.forRoute(Screen.SportActivity.route).isNotEmpty())
    }
}
