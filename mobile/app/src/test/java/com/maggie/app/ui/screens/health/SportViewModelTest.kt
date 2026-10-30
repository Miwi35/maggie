package com.maggie.app.ui.screens.health

import com.maggie.app.data.api.WorkoutWriteRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Workout
import com.maggie.app.data.repository.WorkoutRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** « Santé › Sport » (MAG-705): one test per transition of the list, the detail and the form. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<WorkoutRepository>()
    private val authRepository = mockk<AuthRepository>()
    private val mercureService = mockk<MercureService>()
    private val pushes = MutableSharedFlow<MercureEvent>()
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:15:30Z"), ZoneId.of("Europe/Paris"))

    private val run = Workout("run", "run", "Course", "2026-10-19T07:10:00+02:00", 32, distanceMeters = 5400, kcal = 386)
    private val gym = Workout("gym", "strength", "Haut du corps", "2026-10-21T18:30:00+02:00", 45, source = "planned")
    private val swim = Workout("swim", "swim", "Piscine", "2026-10-15T12:00:00+02:00", 40)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.getWorkouts() } returns Result.success(listOf(run, swim, gym))
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns pushes
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = SportViewModel(repository, mercureService, authRepository, clock)

    @Test
    fun `loading, then the sessions newest first`() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasLoaded)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.hasLoaded)
        assertEquals(listOf("gym", "run", "swim"), state.workouts.map { it.id })
        assertNull(state.error)
    }

    @Test
    fun `a failed load says so in French and keeps the screen out of the empty state`() = runTest {
        coEvery { repository.getWorkouts() } returns Result.failure(RuntimeException("offline"))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(SportViewModel.MESSAGE_LOAD, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.hasLoaded)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a tap selects a session and opens its detail, closing keeps the selection`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.select("run")
        assertEquals("run", viewModel.uiState.value.selected?.id)
        assertTrue(viewModel.uiState.value.detailOpen)

        viewModel.closeDetail()
        assertFalse(viewModel.uiState.value.detailOpen)
        assertEquals("run", viewModel.uiState.value.selectedId)

        viewModel.select("unknown")
        assertEquals("run", viewModel.uiState.value.selectedId)
    }

    @Test
    fun `a push from Mercure reloads the list`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.getWorkouts() } returns Result.success(listOf(run))

        pushes.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals(listOf("run"), viewModel.uiState.value.workouts.map { it.id })
    }

    @Test
    fun `a new session opens on today and now, saving it sends metres and closes the form`() = runTest {
        val sent = slot<WorkoutWriteRequest>()
        coEvery { repository.createWorkout(capture(sent)) } returns Result.success(run.copy(id = "new"))
        val viewModel = viewModel()
        advanceUntilIdle()

        coEvery { repository.getWorkouts() } returns Result.success(listOf(run, run.copy(id = "new")))
        viewModel.openNew()
        val draft = viewModel.uiState.value.form!!.draft
        assertEquals("2026-10-21", draft.date.toString())
        assertEquals("10:15", draft.time.toString())

        viewModel.editDraft { it.copy(title = "Course", duration = "32", distance = "5,4") }
        viewModel.save()
        advanceUntilIdle()

        assertEquals(5400, sent.captured.distanceMeters)
        assertEquals("2026-10-21T10:15:00+02:00", sent.captured.startedAt)
        assertEquals("run", sent.captured.type)
        val state = viewModel.uiState.value
        assertTrue(state.form!!.done)
        assertEquals("Séance ajoutée", state.message)
        assertEquals("new", state.selectedId)
    }

    @Test
    fun `an incomplete form shows its errors and sends nothing`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.openNew()
        assertTrue(viewModel.uiState.value.form!!.errors.isEmpty())

        viewModel.save()
        advanceUntilIdle()

        val form = viewModel.uiState.value.form!!
        assertEquals(setOf(WorkoutField.TITLE, WorkoutField.DURATION), form.errors.keys)
        coVerify(exactly = 0) { repository.createWorkout(any()) }
    }

    @Test
    fun `a refused save stays on the form and says so`() = runTest {
        coEvery { repository.createWorkout(any()) } returns Result.failure(RuntimeException("422"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.openNew()
        viewModel.editDraft { it.copy(title = "Course", duration = "32") }

        viewModel.save()
        advanceUntilIdle()

        val form = viewModel.uiState.value.form!!
        assertFalse(form.done)
        assertFalse(form.isSaving)
        assertEquals(SportViewModel.MESSAGE_SAVE, form.error)
    }

    @Test
    fun `editing opens the form on the session and saves with a patch`() = runTest {
        coEvery { repository.updateWorkout("run", any()) } returns Result.success(run)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.select("run")

        viewModel.openEdit("run")
        val form = viewModel.uiState.value.form!!
        assertEquals("run", form.workoutId)
        assertEquals("Course", form.draft.title)
        assertEquals("5,4", form.draft.distance)
        assertFalse(viewModel.uiState.value.detailOpen)

        viewModel.save()
        advanceUntilIdle()

        coVerify { repository.updateWorkout("run", any()) }
        assertEquals("Séance enregistrée", viewModel.uiState.value.message)
    }

    @Test
    fun `editing a session that is gone says so instead of opening`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.openEdit("ghost")

        assertNull(viewModel.uiState.value.form)
        assertEquals(SportViewModel.MESSAGE_NOT_FOUND, viewModel.uiState.value.error)
    }

    @Test
    fun `deleting takes the session out at once and says it was deleted`() = runTest {
        coEvery { repository.deleteWorkout("run") } returns Result.success(Unit)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.select("run")
        coEvery { repository.getWorkouts() } returns Result.success(listOf(swim, gym))

        viewModel.delete("run")
        assertEquals(listOf("gym", "swim"), viewModel.uiState.value.workouts.map { it.id })
        assertFalse(viewModel.uiState.value.detailOpen)
        advanceUntilIdle()

        assertEquals("Séance supprimée", viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.selectedId)
    }

    @Test
    fun `a refused deletion puts the session back`() = runTest {
        coEvery { repository.deleteWorkout("run") } returns Result.failure(RuntimeException("500"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.delete("run")
        advanceUntilIdle()

        assertEquals(listOf("gym", "run", "swim"), viewModel.uiState.value.workouts.map { it.id })
        assertEquals(SportViewModel.MESSAGE_DELETE, viewModel.uiState.value.error)
        assertNotNull(viewModel.uiState.value.workouts.firstOrNull { it.id == "run" })
    }
}
