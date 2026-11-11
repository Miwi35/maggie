package com.maggie.app.ui.screens.health.sport

import com.maggie.app.data.api.SportWorkoutCreate
import com.maggie.app.data.api.SportWorkoutFinish
import com.maggie.app.data.health.HealthConnectSync
import com.maggie.app.data.health.SyncOutcome
import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import com.maggie.app.data.model.Workout
import com.maggie.app.data.model.WorkoutExercise
import com.maggie.app.data.model.WorkoutSetDone
import com.maggie.app.data.model.WorkoutSource
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.data.repository.WorkoutRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SportEndViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var workouts: WorkoutRepository
    private lateinit var status: ActivityStatusRepository
    private lateinit var sync: HealthConnectSync

    private val startedAt = "2026-11-11T17:00:00+00:00"
    private val now = Instant.parse("2026-11-11T17:32:00Z")

    private fun planned(variant: String? = null) = Workout(
        id = "w1", type = "strength", title = "Haut du corps", startedAt = "2026-11-11T18:30:00+01:00", durationMinutes = 40,
        source = WorkoutSource.PLANNED, variant = variant,
        exercises = listOf(
            WorkoutExercise(exercise = "tractions", name = "Tractions", step = 4, sets = 2, target = 5, targetMin = 4, targetMax = 6, restSeconds = 90),
            WorkoutExercise(exercise = "pompes", name = "Pompes inclinées", step = 2, sets = 2, target = 7, loadKg = 10.0, restSeconds = 60),
        ),
    )

    private fun state(doneWorkoutId: String? = null) = SportState(
        exerciseIndex = 1, setIndex = 1,
        loadKg = listOf(null, 10.0),
        done = listOf(
            listOf(SportSetDone(reps = 5, loadKg = null), SportSetDone(reps = 4)),
            listOf(SportSetDone(reps = 8, loadKg = 10.0), SportSetDone(reps = 7, loadKg = 10.0)),
        ),
        doneWorkoutId = doneWorkoutId,
    )

    private fun statusOf(state: SportState) = ActivityStatus(
        activity = SPORT_ACTIVITY, origin = ActivityOrigin(SPORT_ORIGIN_KIND, "w1"), state = state.toJson(), startedAt = startedAt, updatedAt = startedAt,
    )

    private fun done(id: String = "d1", streak: Int? = 7, difficulty: Int? = null, feeling: String? = null) = Workout(
        id = id, type = "strength", title = "Haut du corps", startedAt = startedAt, durationMinutes = 32, source = WorkoutSource.ACTIVITY,
        difficulty = difficulty, streak = streak,
        exercises = planned().exercises.mapIndexed { i, e -> e.copy(feeling = feeling.takeIf { i == 0 }, done = state().done[i].map { WorkoutSetDone(reps = it.reps, loadKg = it.loadKg) }) },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        workouts = mockk()
        status = mockk()
        sync = mockk()
        every { status.put(any(), any(), any()) } returns Unit
        every { status.delete(any()) } returns Unit
        coEvery { status.get(SPORT_ACTIVITY) } returns Result.success(statusOf(state()))
        coEvery { workouts.getWorkout("w1") } returns Result.success(planned())
        coEvery { workouts.createSportWorkout(any()) } returns Result.success(done(streak = null))
        coEvery { workouts.getWorkout("d1") } returns Result.success(done())
        coEvery { workouts.getWorkoutsWithExercise(any()) } returns Result.success(emptyList())
        coEvery { workouts.finishSportWorkout(any(), any()) } returns Result.success(done())
        coEvery { sync.run() } returns SyncOutcome.SENT
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel() = SportEndViewModel(status, workouts, sync, { now }, this)

    @Test
    fun `arriving creates the session done with every set, then reads its streak`() = runTest(dispatcher) {
        coEvery { workouts.getWorkout("d1") } returns Result.success(done(streak = 7))
        val request = slot<SportWorkoutCreate>()
        coEvery { workouts.createSportWorkout(capture(request)) } returns Result.success(done(streak = null))
        val written = slot<JsonObject>()
        every { status.put(SPORT_ACTIVITY, any(), capture(written)) } returns Unit

        val vm = viewModel()
        advanceUntilIdle()

        val ui = vm.uiState.value
        assertEquals(SportEndLoad.READY, ui.load)
        assertEquals(7, ui.streak)
        assertEquals(32, ui.minutes)
        assertEquals(4, ui.setCount)
        with(request.captured) {
            assertEquals("strength", type)
            assertEquals("Haut du corps", title)
            assertEquals(startedAt, this@SportEndViewModelTest.startedAt)
            assertEquals(32, durationMinutes)
            assertEquals("activity", source)
            assertEquals(2, exercises.size)
            assertEquals(listOf(5, 4), exercises[0].done.map { it.reps })
            assertEquals(10.0, exercises[1].loadKg)
            assertEquals(listOf(8, 7), exercises[1].done.map { it.reps })
            assertEquals(4, exercises[0].targetMin)
            assertNull(exercises[0].feeling)
        }
        assertEquals("d1", written.captured["doneWorkoutId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a null streak shows no streak`() = runTest(dispatcher) {
        coEvery { workouts.getWorkout("d1") } returns Result.success(done(streak = null))
        val vm = viewModel()
        advanceUntilIdle()
        assertNull(vm.uiState.value.streak)
    }

    @Test
    fun `a replay finds the session done in the status and creates nothing`() = runTest(dispatcher) {
        coEvery { status.get(SPORT_ACTIVITY) } returns Result.success(statusOf(state(doneWorkoutId = "d1")))

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(SportEndLoad.READY, vm.uiState.value.load)
        coVerify(exactly = 0) { workouts.createSportWorkout(any()) }
    }

    @Test
    fun `a failed creation keeps the screen on a retry that writes the session once`() = runTest(dispatcher) {
        coEvery { workouts.createSportWorkout(any()) } returnsMany listOf(Result.failure(RuntimeException("down")), Result.success(done(streak = null)))

        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(SportEndLoad.ERROR, vm.uiState.value.load)
        verify(exactly = 0) { status.put(any(), any(), any()) }

        vm.retry()
        advanceUntilIdle()
        assertEquals(SportEndLoad.READY, vm.uiState.value.load)
        coVerify(exactly = 2) { workouts.createSportWorkout(any()) }
    }

    @Test
    fun `no status means nothing to end`() = runTest(dispatcher) {
        coEvery { status.get(SPORT_ACTIVITY) } returns Result.success(null)
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(SportEndLoad.GONE, vm.uiState.value.load)
        coVerify(exactly = 0) { workouts.createSportWorkout(any()) }
    }

    @Test
    fun `the difficulty and a feeling are chosen and a second touch takes them back`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.chooseDifficulty(7)
        vm.chooseFeeling(0, "right")
        assertEquals(7, vm.uiState.value.difficulty)
        assertEquals(listOf("right", null), vm.uiState.value.feelings)

        vm.chooseFeeling(0, "too_hard")
        assertEquals("too_hard", vm.uiState.value.feelings[0])
        vm.chooseFeeling(0, "too_hard")
        vm.chooseDifficulty(7)
        assertNull(vm.uiState.value.difficulty)
        assertNull(vm.uiState.value.feelings[0])
    }

    @Test
    fun `saving sends the difficulty and the feelings, clears the status and runs the sync`() = runTest(dispatcher) {
        val finish = slot<SportWorkoutFinish>()
        coEvery { workouts.finishSportWorkout("d1", capture(finish)) } returns Result.success(done())
        val vm = viewModel()
        advanceUntilIdle()
        vm.chooseDifficulty(7)
        vm.chooseFeeling(0, "right")

        vm.save()
        advanceUntilIdle()

        assertEquals(7, finish.captured.difficulty)
        assertEquals(listOf("right", null), finish.captured.exercises.map { it.feeling })
        assertEquals(2, finish.captured.exercises.size)
        assertEquals(listOf(5, 4), finish.captured.exercises[0].done.map { it.reps })
        verify(exactly = 1) { status.delete(SPORT_ACTIVITY) }
        coVerify(exactly = 1) { sync.run() }
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun `saving without a choice sends no difficulty`() = runTest(dispatcher) {
        val finish = slot<SportWorkoutFinish>()
        coEvery { workouts.finishSportWorkout("d1", capture(finish)) } returns Result.success(done())
        val vm = viewModel()
        advanceUntilIdle()

        vm.save()
        advanceUntilIdle()

        assertNull(finish.captured.difficulty)
        assertTrue(finish.captured.exercises.all { it.feeling == null })
    }

    @Test
    fun `a failed save keeps the choices and the status, then retries`() = runTest(dispatcher) {
        coEvery { workouts.finishSportWorkout(any(), any()) } returnsMany listOf(Result.failure(RuntimeException("down")), Result.success(done()))
        val vm = viewModel()
        advanceUntilIdle()
        vm.chooseDifficulty(6)

        vm.save()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.saveFailed)
        assertFalse(vm.uiState.value.saved)
        assertEquals(6, vm.uiState.value.difficulty)
        verify(exactly = 0) { status.delete(any()) }
        coVerify(exactly = 0) { sync.run() }

        vm.save()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.saveFailed)
        verify(exactly = 1) { status.delete(SPORT_ACTIVITY) }
    }

    @Test
    fun `the gap to the last session done is read per exercise, never against itself`() = runTest(dispatcher) {
        val last = Workout(
            id = "old", type = "strength", title = "Avant", startedAt = "2026-11-08T18:00:00+01:00", durationMinutes = 40, source = WorkoutSource.ACTIVITY,
            exercises = listOf(WorkoutExercise(exercise = "tractions", name = "Tractions", sets = 2, target = 5, done = listOf(WorkoutSetDone(reps = 4), WorkoutSetDone(reps = 4)))),
        )
        coEvery { workouts.getWorkoutsWithExercise("tractions") } returns Result.success(listOf(done(), last))

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("+1 rép.", vm.uiState.value.deltas["tractions"]?.text)
        assertNull(vm.uiState.value.deltas["pompes"])
    }

    @Test
    fun `a soft session says so`() = runTest(dispatcher) {
        coEvery { workouts.getWorkout("w1") } returns Result.success(planned(variant = "soft"))
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.soft)
    }

    @Test
    fun `quitting is asked first`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.askQuit()
        assertTrue(vm.uiState.value.quitAsked)
        vm.dismissQuit()
        assertFalse(vm.uiState.value.quitAsked)
    }
}
