package com.maggie.app.ui.screens.bilan

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import com.maggie.app.data.repository.ActivityStatusRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
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

    private fun viewModel() = BilanActivityViewModel(repository, mercure, auth) { now }

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

        assertTrue(vm.uiState.value.finished)
        assertEquals(1, deletes)
        assertNull(stored)
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
}
