package com.maggie.app.ui.screens.bilan

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import com.maggie.app.data.model.Task
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.data.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.coVerify
import io.mockk.MockKMatcherScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** The Bilan de la journée's transitions (MAG-751): opening, next step, quit, resume, network error, and the other devices. */
@OptIn(ExperimentalCoroutinesApi::class)
class BilanActivityViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // Sunday 8 November 2026, 21:40 in Paris.
    private val now = Instant.parse("2026-11-08T20:40:00Z")
    private val events = MutableSharedFlow<MercureEvent>(extraBufferCapacity = 8)
    private val mercure = mockk<MercureService> { every { subscribe(any()) } returns events }
    private val auth = mockk<AuthRepository> { coEvery { getUserId() } returns "u-1" }
    private val repository = mockk<ActivityStatusRepository>()
    private val doneStore = FakeBilanDoneStore()
    private val tasks = mockk<TaskRepository>()
    private var stored: ActivityStatus? = null
    private var failing = false
    private val puts = mutableListOf<Pair<ActivityOrigin, JsonObject>>()
    private var deletes = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.get(BILAN_ACTIVITY) } answers {
            if (failing) Result.failure(RuntimeException("network")) else Result.success(stored)
        }
        val origin = slot<ActivityOrigin>()
        val state = slot<JsonObject>()
        every { repository.put(BILAN_ACTIVITY, capture(origin), capture(state)) } answers {
            puts += origin.captured to state.captured
            stored = status(origin.captured.id, state.captured.toString())
        }
        every { repository.delete(BILAN_ACTIVITY) } answers {
            deletes++
            stored = null
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun status(day: String, state: String) = ActivityStatus(
        activity = "bilan",
        origin = ActivityOrigin("day", day),
        state = Json.parseToJsonElement(state).jsonObject,
        startedAt = "2026-11-08T18:00:00+00:00",
        updatedAt = "2026-11-08T18:00:00+00:00",
    )

    private fun viewModel() = BilanActivityViewModel(repository, mercure, auth, doneStore, tasks) { now }

    @Test
    fun `opening with nothing in progress starts at Journée and writes it`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        val ui = vm.uiState.value
        assertEquals(BilanLoad.READY, ui.load)
        assertEquals(BilanStep.JOURNEE, ui.step)
        assertEquals("Journée · 1 sur 5", ui.counter)
        assertEquals(listOf(ActivityOrigin("day", "2026-11-08")), puts.map { it.first })
        assertEquals("journee", puts.single().second.getValue("step").jsonPrimitive.content)
    }

    @Test
    fun `next moves one step and writes it, keeping the answers`() = runTest(testDispatcher) {
        stored = status("2026-11-08", """{"step":"journee","answers":{"mood":7},"instructions":[]}""")
        val vm = viewModel()
        advanceUntilIdle()

        vm.next()

        assertEquals(BilanStep.FINANCE, vm.uiState.value.step)
        val written = puts.single().second
        assertEquals("finance", written.getValue("step").jsonPrimitive.content)
        assertEquals("""{"mood":7}""", written.getValue("answers").toString())
    }

    @Test
    fun `a task choice is written into answers tasks at once, the other answers untouched`() = runTest(testDispatcher) {
        stored = status("2026-11-08", """{"step":"journee","answers":{"mood":7}}""")
        val vm = viewModel()
        advanceUntilIdle()

        vm.recordTaskChoice("t-1", "tomorrow")
        vm.recordTaskChoice("t-2", "later")
        vm.recordTaskChoice("t-1", "later")

        val answers = puts.last().second.getValue("answers").jsonObject
        assertEquals(7, answers.getValue("mood").jsonPrimitive.content.toInt())
        assertEquals(mapOf("t-1" to "later", "t-2" to "later"), vm.uiState.value.session!!.taskChoices())
        assertEquals(3, puts.size)
        vm.recordTaskChoice("t-2", null)
        assertEquals(mapOf("t-1" to "later"), vm.uiState.value.session!!.taskChoices())
    }

    @Test
    fun `an answer is written into answers at once and resuming keeps it with the other answers`() = runTest(testDispatcher) {
        stored = status("2026-11-08", """{"step":"sante","answers":{"mood":7}}""")
        val vm = viewModel()
        advanceUntilIdle()

        vm.recordAnswer("fatigue", 4)

        assertEquals(4, vm.uiState.value.session?.answerInt("fatigue"))
        assertEquals(7, vm.uiState.value.session?.answerInt("mood"))
        assertEquals("""{"mood":7,"fatigue":4}""", puts.single().second.getValue("answers").toString())
        assertEquals("sante", puts.single().second.getValue("step").jsonPrimitive.content)
    }

    @Test
    fun `previous and the segments move back and forth, the first step has no previous`() = runTest(testDispatcher) {
        stored = status("2026-11-08", """{"step":"sante"}""")
        val vm = viewModel()
        advanceUntilIdle()

        vm.previous()
        assertEquals(BilanStep.FINANCE, vm.uiState.value.step)
        vm.goTo(BilanStep.DEMAIN)
        assertEquals(BilanStep.DEMAIN, vm.uiState.value.step)
        vm.goTo(BilanStep.JOURNEE)
        vm.previous()
        assertEquals(BilanStep.JOURNEE, vm.uiState.value.step)
        assertEquals(listOf("finance", "demain", "journee"), puts.map { it.second.getValue("step").jsonPrimitive.content })
    }

    @Test
    fun `Bonne nuit at the last step ends the bilan`() = runTest(testDispatcher) {
        stored = status("2026-11-08", """{"step":"demain"}""")
        val vm = viewModel()
        advanceUntilIdle()

        vm.next()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.finished)
        assertEquals(1, deletes)
        assertNull(stored)
        assertEquals(LocalDate.of(2026, 11, 8), doneStore.day.value)
    }

    @Test
    fun `quitting asks nothing and writes nothing, and the next opening resumes the same step`() = runTest(testDispatcher) {
        stored = status("2026-11-08", """{"step":"finance"}""")
        val first = viewModel()
        advanceUntilIdle()
        assertEquals(BilanStep.FINANCE, first.uiState.value.step)

        // ✕ is the screen's onQuit: the view model has nothing to do, and the status stays.
        val second = viewModel()
        advanceUntilIdle()

        assertEquals(BilanStep.FINANCE, second.uiState.value.step)
        assertTrue(puts.isEmpty())
        assertEquals(0, deletes)
    }

    @Test
    fun `a bilan of a day that has gone is not resumed, today starts again at Journée`() = runTest(testDispatcher) {
        stored = status("2026-11-07", """{"step":"consignes"}""")
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(BilanStep.JOURNEE, vm.uiState.value.step)
        assertEquals("2026-11-08", puts.single().first.id)
    }

    @Test
    fun `a network error shows the error and Réessayer reads again`() = runTest(testDispatcher) {
        failing = true
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(BilanLoad.ERROR, vm.uiState.value.load)
        assertTrue(puts.isEmpty())

        failing = false
        stored = status("2026-11-08", """{"step":"sante"}""")
        vm.retry()
        advanceUntilIdle()

        assertEquals(BilanLoad.READY, vm.uiState.value.load)
        assertEquals(BilanStep.SANTE, vm.uiState.value.step)
    }

    @Test
    fun `another device moving the bilan moves this screen, and ending it finishes this one`() = runTest(testDispatcher) {
        stored = status("2026-11-08", """{"step":"journee"}""")
        val vm = viewModel()
        advanceUntilIdle()

        stored = status("2026-11-08", """{"step":"consignes"}""")
        events.emit(MercureEvent(data = "{}"))
        advanceTimeBy(1_000)
        advanceUntilIdle()
        assertEquals(BilanStep.CONSIGNES, vm.uiState.value.step)
        assertFalse(vm.uiState.value.finished)

        stored = null
        events.emit(MercureEvent(data = "{}"))
        advanceTimeBy(1_000)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.finished)
        verify(exactly = 0) { repository.delete(BILAN_ACTIVITY) }
    }

    @Test
    fun `a trip answer is written beside the step and survives a change of step`() = runTest(testDispatcher) {
        stored = status("2026-11-08", """{"step":"demain","answers":{"tasks":[]}}""")
        val vm = viewModel()
        advanceUntilIdle()

        vm.recordTripAnswer("e1:origin")
        vm.recordTripAnswer("e1:origin")
        vm.previous()
        advanceUntilIdle()

        val written = puts.last().second
        assertEquals("consignes", written["step"]!!.jsonPrimitive.content)
        assertEquals(setOf("e1:origin"), vm.uiState.value.session!!.answeredTrips())
        assertTrue(written["answers"]!!.jsonObject.containsKey("tasks"))
        assertEquals(2, puts.size)
    }

    private val prepared = """{"tomorrowTasks":[{"taskId":"a","title":"Trier les papiers"}],"tomorrowSpares":[{"taskId":"b","title":"Ranger les photos"}]}"""

    private fun MockKMatcherScope.planned(vararg fields: Pair<String, String?>) =
        match<JsonObject> { body -> body.keys == fields.map { it.first }.toSet() && fields.all { (k, v) -> body[k]?.toString()?.trim('"').let { it == (v ?: "null") } } }

    private fun stubTasks() {
        coEvery { tasks.updateTask(any(), any()) } returns Result.success(Task(id = "x", title = "x"))
        coEvery { tasks.setDueDate(any(), any()) } returns Result.success(Task(id = "x", title = "x"))
        coEvery { tasks.fetchTask(any()) } returns Result.success(Task(id = "x", title = "x"))
    }

    @Test
    fun `opening a prepared bilan keeps its tomorrow tasks`() = runTest(testDispatcher) {
        stored = status("2026-11-08", prepared)
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(BilanStep.JOURNEE, vm.uiState.value.step)
        assertEquals(listOf("a"), vm.uiState.value.session!!.tomorrowTasks().map { it.taskId })
        assertEquals(listOf("b"), vm.uiState.value.session!!.tomorrowSpares().map { it.taskId })
        assertEquals("journee", puts.single().second.getValue("step").jsonPrimitive.content)
        assertTrue(puts.single().second.containsKey("tomorrowTasks"))
    }

    @Test
    fun `a swap rewrites both lists`() = runTest(testDispatcher) {
        stored = status("2026-11-08", """{"step":"demain",""" + prepared.drop(1))
        val vm = viewModel()
        advanceUntilIdle()

        vm.swap("a")

        val session = vm.uiState.value.session!!
        assertEquals(listOf("b"), session.tomorrowTasks().map { it.taskId })
        assertEquals(listOf("a"), session.tomorrowSpares().map { it.taskId })
        assertEquals(session.statusState(), puts.last().second)
    }

    @Test
    fun `swapping a task left at Journée puts it off and adds the spare`() = runTest(testDispatcher) {
        stubTasks()
        stored = status("2026-11-08", """{"step":"demain","answers":{"tasks":[{"taskId":"j","choice":"tomorrow"}]},""" + prepared.drop(1))
        val vm = viewModel()
        advanceUntilIdle()

        vm.swap("j", "Appeler")
        advanceUntilIdle()

        val session = vm.uiState.value.session!!
        assertEquals(mapOf("j" to "later"), session.taskChoices())
        assertEquals(listOf("a", "b"), session.tomorrowTasks().map { it.taskId })
        coVerify { tasks.setDueDate("j", null) }
    }

    @Test
    fun `good night plans every shown task for tomorrow without its due date`() = runTest(testDispatcher) {
        stubTasks()
        stored = status("2026-11-08", """{"step":"demain",""" + prepared.drop(1))
        val vm = viewModel()
        advanceUntilIdle()
        vm.shown(listOf("j", "a"))

        vm.next()
        advanceUntilIdle()

        coVerify { tasks.updateTask("j", planned("scheduledDate" to "2026-11-09")) }
        coVerify { tasks.updateTask("a", planned("scheduledDate" to "2026-11-09")) }
        assertTrue(vm.uiState.value.finished)
        assertEquals(1, deletes)
    }

    @Test
    fun `good night takes a swapped-out task off tomorrow`() = runTest(testDispatcher) {
        stubTasks()
        coEvery { tasks.fetchTask("a") } returns Result.success(Task(id = "a", title = "Trier les papiers", scheduledDate = "2026-11-09", scheduledMoment = "morning"))
        stored = status("2026-11-08", """{"step":"demain",""" + prepared.drop(1))
        val vm = viewModel()
        advanceUntilIdle()
        vm.swap("a")
        vm.shown(listOf("b"))

        vm.next()
        advanceUntilIdle()

        coVerify { tasks.updateTask("a", planned("scheduledDate" to null, "scheduledMoment" to null, "scheduledTime" to null)) }
        assertTrue(vm.uiState.value.finished)
    }

    @Test
    fun `a refused plan keeps the bilan open and says so`() = runTest(testDispatcher) {
        stubTasks()
        coEvery { tasks.updateTask("a", any()) } returns Result.failure(RuntimeException("500"))
        stored = status("2026-11-08", """{"step":"demain",""" + prepared.drop(1))
        val vm = viewModel()
        advanceUntilIdle()
        vm.shown(listOf("a"))

        vm.next()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.finished)
        assertEquals("Impossible de prévoir vos tâches de demain : réessayez.", vm.uiState.value.failure)
        assertEquals(0, deletes)
        assertEquals(BilanStep.DEMAIN, vm.uiState.value.step)
    }

    @Test
    fun `a task done since is not planned`() = runTest(testDispatcher) {
        stubTasks()
        stored = status("2026-11-08", """{"step":"demain",""" + prepared.drop(1))
        val vm = viewModel()
        advanceUntilIdle()
        vm.shown(emptyList())

        vm.next()
        advanceUntilIdle()

        coVerify(exactly = 0) { tasks.updateTask("a", any()) }
        assertTrue(vm.uiState.value.finished)
    }
}
