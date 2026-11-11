package com.maggie.app.ui.screens.health.sport

import androidx.lifecycle.SavedStateHandle
import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import com.maggie.app.data.model.Workout
import com.maggie.app.data.model.WorkoutExercise
import com.maggie.app.data.model.WorkoutSource
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.data.repository.WorkoutRepository
import com.maggie.app.ui.navigation.DeepLinks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** The moves of the Sport activity (MAG-780): one test per transition, the timers on a simulated clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportActivityStepsTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var workouts: WorkoutRepository
    private lateinit var status: ActivityStatusRepository
    private val writes = mutableListOf<SportState>()
    private var beeps = 0

    private val beep = object : SportSound {
        override fun beep() {
            beeps++
        }
    }

    private val session = Workout(
        id = "w1", type = "strength", title = "Séance A", startedAt = "2026-11-11T18:30:00+01:00", durationMinutes = 45, source = WorkoutSource.PLANNED,
        exercises = listOf(
            WorkoutExercise(exercise = "pull", name = "Tractions négatives", sets = 3, target = 5, restSeconds = 120),
            WorkoutExercise(exercise = "squat", name = "Squat gobelet", sets = 3, target = 10, loadKg = 10.0, restSeconds = 90),
            WorkoutExercise(exercise = "plank", name = "Planche", unit = "seconds", sets = 3, target = 30, restSeconds = 60),
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        workouts = mockk()
        status = mockk()
        every { status.put(any(), any(), any()) } answers {
            writes += stateJsonToSport(thirdArg())
        }
        coEvery { status.get(SPORT_ACTIVITY) } returns Result.success(null)
        coEvery { workouts.getWorkout("w1") } returns Result.success(session)
        coEvery { workouts.getWorkoutsWithExercise(any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stateJsonToSport(json: JsonObject): SportState = ActivityStatus("sport", ActivityOrigin("workout", "w1"), json, "", "").toSportState()!!

    private fun TestScope.open(saved: SportState? = null): SportActivityViewModel {
        coEvery { status.get(SPORT_ACTIVITY) } returns Result.success(
            saved?.let { ActivityStatus("sport", ActivityOrigin("workout", "w1"), it.toJson(), "2026-11-11T17:00:00+00:00", "2026-11-11T17:00:00+00:00") },
        )
        val vm = SportActivityViewModel(
            SavedStateHandle(mapOf(DeepLinks.ARG_WORKOUT to "w1")),
            workouts,
            status,
            clock = { Instant.ofEpochMilli(testScheduler.currentTime) },
            sound = beep,
        )
        runCurrent()
        return vm
    }


    private fun saved(exercise: Int, set: Int = 0, phase: String = SportPhase.WORK, ends: Instant? = null, paused: Int? = null, done: List<List<SportSetDone>> = List(3) { emptyList() }) =
        SportState(exercise, set, phase, ends?.toString(), paused, listOf(null, 10.0, null), done)

    // --- a series, then its rest ----------------------------------------------------------------------------

    @Test
    fun `the stepper starts at the target, minus twice then Série faite writes 3 and starts the 2 minutes of rest`() = runTest(dispatcher) {
        val vm = open()
        assertEquals(5, vm.uiState.value.reps)

        vm.changeReps(-1)
        vm.changeReps(-1)
        vm.setDone()

        val ui = vm.uiState.value
        assertEquals(listOf(SportSetDone(reps = 3)), ui.session.done[0])
        assertEquals(SportPhase.REST, ui.phase)
        assertEquals(120, ui.remaining)
        assertEquals(SportPhase.REST, writes.last().phase)
        assertEquals(3, writes.last().done[0][0].reps)
    }

    @Test
    fun `plus 15 adds a quarter of a minute to the rest and Passer le repos starts the next set`() = runTest(dispatcher) {
        val vm = open()
        vm.setDone()

        vm.adjustRest(15)
        assertEquals(135, vm.uiState.value.remaining)
        vm.adjustRest(-15)
        assertEquals(120, vm.uiState.value.remaining)

        vm.skipRest()
        val ui = vm.uiState.value
        assertEquals(1, ui.setIndex)
        assertEquals(SportPhase.WORK, ui.phase)
        assertNull(ui.session.timerEndsAt)
        assertEquals(5, ui.reps)
        assertFalse(vm.uiState.value.timerRunning)
    }

    @Test
    fun `a rest cut below nothing is over`() = runTest(dispatcher) {
        val vm = open()
        vm.setDone()
        advanceTimeBy(110_000)

        vm.adjustRest(-15)

        assertEquals(1, vm.uiState.value.setIndex)
        assertEquals(SportPhase.WORK, vm.uiState.value.phase)
    }

    @Test
    fun `the rest runs out on the clock, moves to the next set and beeps once`() = runTest(dispatcher) {
        val vm = open()
        vm.setDone()

        advanceTimeBy(60_001)
        assertEquals(60, vm.uiState.value.remaining)
        assertEquals(0, beeps)
        advanceTimeBy(60_400)

        assertEquals(1, vm.uiState.value.setIndex)
        assertEquals(SportPhase.WORK, vm.uiState.value.phase)
        assertEquals(1, beeps)
        assertEquals(SportPhase.WORK, writes.last().phase)
    }

    @Test
    fun `the last set of an exercise rests, then opens the next exercise`() = runTest(dispatcher) {
        val vm = open(saved(0, set = 2))

        vm.setDone()
        assertEquals(0, vm.uiState.value.exerciseIndex)
        assertEquals(SportPhase.REST, vm.uiState.value.phase)

        vm.skipRest()
        assertEquals(1, vm.uiState.value.exerciseIndex)
        assertEquals(0, vm.uiState.value.setIndex)
        assertEquals(10, vm.uiState.value.reps)
    }

    @Test
    fun `the lest goes up by 5 kg and the set done carries it`() = runTest(dispatcher) {
        val vm = open(saved(1))

        vm.changeLoad(5.0)
        assertEquals(15.0, vm.uiState.value.loadKg!!, 0.0)
        assertEquals(15.0, writes.last().loadKg[1]!!, 0.0)
        vm.setDone()

        assertEquals(15.0, writes.last().done[1][0].loadKg!!, 0.0)
    }

    @Test
    fun `the lest never goes below zero`() = runTest(dispatcher) {
        val vm = open(saved(1))

        repeat(4) { vm.changeLoad(-5.0) }

        assertEquals(0.0, vm.uiState.value.loadKg!!, 0.0)
    }

    // --- a held exercise ------------------------------------------------------------------------------------

    @Test
    fun `a held exercise is ready at its target and Démarrer counts down to zero, then the set holds the target`() = runTest(dispatcher) {
        val vm = open(saved(2))
        assertEquals(30, vm.uiState.value.remaining)
        assertFalse(vm.uiState.value.timerRunning)

        vm.startHold()
        assertEquals(SportPhase.HOLD, vm.uiState.value.phase)
        advanceTimeBy(10_001)
        assertEquals(20, vm.uiState.value.remaining)
        advanceTimeBy(20_400)

        assertEquals(30, vm.uiState.value.session.done[2][0].seconds)
        assertEquals(SportPhase.REST, vm.uiState.value.phase)
        assertEquals(1, beeps)
    }

    @Test
    fun `Pause at 18 seconds then Série faite holds 12 seconds`() = runTest(dispatcher) {
        val vm = open(saved(2))
        vm.startHold()
        advanceTimeBy(12_100)

        vm.pauseHold()
        assertEquals(18, vm.uiState.value.remaining)
        assertFalse(vm.uiState.value.timerRunning)
        advanceTimeBy(5_000)
        assertEquals(18, vm.uiState.value.remaining)
        vm.setDone()

        assertEquals(12, vm.uiState.value.session.done[2][0].seconds)
        assertEquals(SportPhase.REST, vm.uiState.value.phase)
        assertEquals(0, beeps)
    }

    @Test
    fun `a pause is resumed from the seconds left`() = runTest(dispatcher) {
        val vm = open(saved(2))
        vm.startHold()
        advanceTimeBy(12_100)
        vm.pauseHold()

        vm.startHold()
        advanceTimeBy(3_001)

        assertEquals(15, vm.uiState.value.remaining)
        assertTrue(vm.uiState.value.timerRunning)
    }

    @Test
    fun `Série faite on a held exercise not started holds its target`() = runTest(dispatcher) {
        val vm = open(saved(2))

        vm.setDone()

        assertEquals(30, vm.uiState.value.session.done[2][0].seconds)
    }

    // --- back -----------------------------------------------------------------------------------------------

    @Test
    fun `back from a rest returns to the set just done, with its reps on the stepper`() = runTest(dispatcher) {
        val vm = open()
        vm.changeReps(-2)
        vm.setDone()

        vm.back()

        val ui = vm.uiState.value
        assertEquals(0, ui.setIndex)
        assertEquals(SportPhase.WORK, ui.phase)
        assertEquals(3, ui.reps)
        assertFalse(ui.timerRunning)
    }

    @Test
    fun `back from a set returns to the previous set, then to the previous exercise, then stops`() = runTest(dispatcher) {
        val vm = open(saved(1, set = 1, done = listOf(emptyList(), listOf(SportSetDone(reps = 9)), emptyList())))

        vm.back()
        assertEquals(0, vm.uiState.value.setIndex)
        assertEquals(9, vm.uiState.value.reps)
        vm.back()
        assertEquals(0, vm.uiState.value.exerciseIndex)
        assertEquals(2, vm.uiState.value.setIndex)
        vm.back()
        vm.back()
        vm.back()
        assertEquals(0, vm.uiState.value.exerciseIndex)
        assertEquals(0, vm.uiState.value.setIndex)
    }

    @Test
    fun `a set redone replaces the one recorded`() = runTest(dispatcher) {
        val vm = open()
        vm.setDone()
        vm.back()
        vm.changeReps(-1)

        vm.setDone()

        assertEquals(listOf(SportSetDone(reps = 4)), vm.uiState.value.session.done[0])
    }

    // --- the end --------------------------------------------------------------------------------------------

    @Test
    fun `the last set of the last exercise finishes the session with every set in the status`() = runTest(dispatcher) {
        val full = List(3) { List(3) { SportSetDone(reps = 5) } }.toMutableList()
        full[2] = listOf(SportSetDone(seconds = 30), SportSetDone(seconds = 30))
        val vm = open(saved(2, set = 2, done = full))

        vm.setDone()

        assertTrue(vm.uiState.value.finished)
        assertEquals(listOf(30, 30, 30), writes.last().done[2].map { it.seconds })
        assertEquals(3, writes.last().done.size)
        assertEquals(SportPhase.WORK, writes.last().phase)
        assertFalse(vm.uiState.value.timerRunning)
    }

    @Test
    fun `a held exercise that runs out on the last set finishes the session`() = runTest(dispatcher) {
        val vm = open(saved(2, set = 2, done = listOf(emptyList(), emptyList(), List(2) { SportSetDone(seconds = 30) })))
        vm.startHold()

        advanceTimeBy(30_400)

        assertTrue(vm.uiState.value.finished)
        assertEquals(30, vm.uiState.value.session.done[2][2].seconds)
    }

    // --- resuming -------------------------------------------------------------------------------------------

    @Test
    fun `a rest reopened 40 seconds later shows 40 seconds less`() = runTest(dispatcher) {
        advanceTimeBy(1_000_000)
        val start = Instant.ofEpochMilli(testScheduler.currentTime)
        val resting = saved(0, phase = SportPhase.REST, ends = start.plusSeconds(120), done = listOf(listOf(SportSetDone(reps = 5)), emptyList(), emptyList()))
        advanceTimeBy(40_000)

        val vm = open(resting)

        assertEquals(80, vm.uiState.value.remaining)
        assertEquals(SportPhase.REST, vm.uiState.value.phase)
        assertEquals(0, beeps)
    }

    @Test
    fun `a rest that ended while the app was closed opens the next set without a beep`() = runTest(dispatcher) {
        advanceTimeBy(1_000_000)
        val ended = Instant.ofEpochMilli(testScheduler.currentTime).minusSeconds(30)

        val vm = open(saved(0, phase = SportPhase.REST, ends = ended, done = listOf(listOf(SportSetDone(reps = 5)), emptyList(), emptyList())))

        assertEquals(1, vm.uiState.value.setIndex)
        assertEquals(SportPhase.WORK, vm.uiState.value.phase)
        assertEquals(0, beeps)
        assertEquals(1, writes.last().setIndex)
    }

    @Test
    fun `a paused held exercise reopens with its seconds left`() = runTest(dispatcher) {
        val vm = open(saved(2, phase = SportPhase.HOLD, paused = 18))

        assertEquals(18, vm.uiState.value.remaining)
        assertFalse(vm.uiState.value.timerRunning)
    }

    @Test
    fun `every step writes the whole status for the session, nothing is written by a tick`() = runTest(dispatcher) {
        val origin = slot<ActivityOrigin>()
        every { status.put(SPORT_ACTIVITY, capture(origin), any()) } answers { writes += SportState() }
        val vm = open()
        val opened = writes.size

        vm.setDone()
        val written = writes.size
        advanceTimeBy(30_000)

        assertEquals(ActivityOrigin("workout", "w1"), origin.captured)
        assertEquals(opened + 1, written)
        assertEquals(written, writes.size)
    }
}
