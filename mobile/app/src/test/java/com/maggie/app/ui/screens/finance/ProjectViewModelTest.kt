package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.ProjectWriteRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ProjectDateMode
import com.maggie.app.data.repository.ProjectRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * « Projets » (MAG-519): one test per transition of the list and its form. The story is the
 * seed's — « Déménagement » first, then « Canapé », which waits for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<ProjectRepository>()
    private val authRepository = mockk<AuthRepository>()
    private val mercureService = mockk<MercureService>()
    private val pushes = MutableSharedFlow<MercureEvent>()

    private val move = Project(
        id = "p1", name = "Déménagement", amountCents = 250000, wellbeing = "essential", wellbeingScore = 4,
        position = 0, dateMode = "fixed", targetOn = "2027-03-01",
    )
    private val sofa = Project(
        id = "p2", name = "Canapé", amountCents = 120000, wellbeing = "comfort", wellbeingScore = 1,
        position = 1, dependsOn = listOf("/api/finance/projects/p1"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.getProjects() } returns Result.success(listOf(move, sofa))
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns pushes
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ProjectViewModel(repository, mercureService, authRepository)

    @Test
    fun `loading then the projects in priority order`() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasLoaded)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.hasLoaded)
        assertEquals(listOf("Déménagement", "Canapé"), state.projects.map { it.name })
        assertNull(state.error)
    }

    @Test
    fun `no project yet is an empty list, loaded`() = runTest {
        coEvery { repository.getProjects() } returns Result.success(emptyList())
        val viewModel = viewModel()

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasLoaded)
        assertTrue(viewModel.uiState.value.projects.isEmpty())
    }

    @Test
    fun `a failed load says so and is not taken for an empty list`() = runTest {
        coEvery { repository.getProjects() } returns Result.failure(RuntimeException(""))
        val viewModel = viewModel()

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.hasLoaded)
        assertEquals(ProjectViewModel.MESSAGE_LOAD, state.error)
    }

    @Test
    fun `a network failure on load is a French sentence, not the exception's text`() = runTest {
        coEvery { repository.getProjects() } returns Result.failure(RuntimeException("Unable to resolve host \"api\""))
        val viewModel = viewModel()

        advanceUntilIdle()

        assertEquals(ProjectViewModel.MESSAGE_LOAD, viewModel.uiState.value.error)
    }

    @Test
    fun `a load answered during a drag does not undo the order under the finger`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        val answer = CompletableDeferred<Result<List<Project>>>()
        coEvery { repository.getProjects() } coAnswers { answer.await() }

        viewModel.refresh()
        runCurrent()
        viewModel.previewMove(1, 0)
        answer.complete(Result.success(listOf(move, sofa)))
        advanceUntilIdle()

        assertEquals(listOf("Canapé", "Déménagement"), viewModel.uiState.value.projects.map { it.name })
    }

    @Test
    fun `a new project is created, the form closes and the list is read again`() = runTest {
        val request = slot<ProjectWriteRequest>()
        val created = Project(id = "p3", name = "Vélo", amountCents = 90000, position = 2)
        coEvery { repository.createProject(capture(request)) } returns Result.success(created)
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.getProjects() } returns Result.success(listOf(move, sofa, created))

        viewModel.openNew()
        viewModel.editDraft { it.copy(name = "Vélo", amount = "900", dependsOn = listOf("p1")) }
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.form!!.done)
        assertEquals("Projet créé", state.message)
        assertEquals(listOf("Déménagement", "Canapé", "Vélo"), state.projects.map { it.name })
        assertEquals("Vélo", request.captured.name)
        assertEquals(90000, request.captured.amountCents)
        assertEquals("none", request.captured.dateMode)
        assertNull(request.captured.targetOn)
        assertEquals(listOf("/api/finance/projects/p1"), request.captured.dependsOn)
    }

    @Test
    fun `a fixed date without a date is not sent, the form says what is missing`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.openNew()
        viewModel.editDraft { it.copy(name = "Vélo", amount = "900", dateMode = ProjectDateMode.FIXED) }
        viewModel.save()
        advanceUntilIdle()

        val form = viewModel.uiState.value.form!!
        assertFalse(form.done)
        assertFalse(form.isSaving)
        assertEquals("Choisissez la date du projet.", form.errors[ProjectField.TARGET_ON])
        coVerify(exactly = 0) { repository.createProject(any()) }
    }

    @Test
    fun `a refusal of the API stays on the form, said in French`() = runTest {
        coEvery { repository.updateProject("p1", any()) } returns Result.failure(
            ApiRefusalException(400, "These dependencies would make a cycle: a project would end up waiting for itself."),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.openEdit("p1")
        viewModel.editDraft { it.copy(dependsOn = listOf("p2")) }
        viewModel.save()
        advanceUntilIdle()

        val form = viewModel.uiState.value.form!!
        assertFalse(form.done)
        assertFalse(form.isSaving)
        assertEquals(ProjectViewModel.MESSAGE_CYCLE, form.error)
        assertEquals("Ces dépendances formeraient un cycle : un projet finirait par attendre lui-même.", form.error)
    }

    @Test
    fun `a dependency on itself is refused in French`() = runTest {
        coEvery { repository.updateProject("p1", any()) } returns
            Result.failure(ApiRefusalException(400, "dependsOn: A project cannot depend on itself."))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.openEdit("p1")
        viewModel.editDraft { it.copy(dependsOn = listOf("p1")) }
        viewModel.save()
        advanceUntilIdle()

        assertEquals("Un projet ne peut pas dépendre de lui-même.", viewModel.uiState.value.form!!.error)
    }

    @Test
    fun `a refusal the phone cannot translate is a French sentence, never the English one`() = runTest {
        coEvery { repository.createProject(any()) } returns
            Result.failure(ApiRefusalException(400, "The currency must be a 3-letter ISO 4217 code."))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.openNew()
        viewModel.editDraft { it.copy(name = "Vélo", amount = "900") }
        viewModel.save()
        advanceUntilIdle()

        assertEquals(ProjectViewModel.MESSAGE_SAVE, viewModel.uiState.value.form!!.error)
    }

    @Test
    fun `a network failure on save is a French sentence, not the exception's text`() = runTest {
        coEvery { repository.createProject(any()) } returns Result.failure(RuntimeException("Unable to resolve host"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.openNew()
        viewModel.editDraft { it.copy(name = "Vélo", amount = "900") }
        viewModel.save()
        advanceUntilIdle()

        assertEquals(ProjectViewModel.MESSAGE_SAVE, viewModel.uiState.value.form!!.error)
    }

    @Test
    fun `a project edited gets a limited date, saved under its id`() = runTest {
        val request = slot<ProjectWriteRequest>()
        val dated = sofa.copy(dateMode = "deadline", targetOn = "2027-01-15")
        coEvery { repository.updateProject("p2", capture(request)) } returns Result.success(dated)
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.getProjects() } returns Result.success(listOf(move, dated))

        viewModel.openEdit("p2")
        assertEquals("Canapé", viewModel.uiState.value.form!!.draft.name)
        assertEquals(listOf("p1"), viewModel.uiState.value.form!!.draft.dependsOn)
        viewModel.editDraft { it.copy(dateMode = ProjectDateMode.DEADLINE, targetOn = LocalDate.of(2027, 1, 15)) }
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.form!!.done)
        assertEquals("Projet enregistré", state.message)
        assertEquals("deadline", request.captured.dateMode)
        assertEquals("2027-01-15", request.captured.targetOn)
        assertEquals("2027-01-15", state.projects[1].targetOn)
    }

    @Test
    fun `a project that no longer exists does not open`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.openEdit("gone")

        assertNull(viewModel.uiState.value.form)
        assertEquals(ProjectViewModel.MESSAGE_NOT_FOUND, viewModel.uiState.value.error)
    }

    @Test
    fun `a deleted project leaves the list at once and the form closes`() = runTest {
        coEvery { repository.deleteProject("p2") } returns Result.success(Unit)
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.getProjects() } returns Result.success(listOf(move))

        viewModel.openEdit("p2")
        viewModel.delete("p2")

        assertEquals(listOf("Déménagement"), viewModel.uiState.value.projects.map { it.name })
        assertTrue(viewModel.uiState.value.form!!.done)

        advanceUntilIdle()
        assertEquals("Projet supprimé", viewModel.uiState.value.message)
        assertEquals(listOf("Déménagement"), viewModel.uiState.value.projects.map { it.name })
    }

    @Test
    fun `a failed deletion puts the project back and says so in French`() = runTest {
        coEvery { repository.deleteProject("p2") } returns Result.failure(RuntimeException("Unable to resolve host"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.delete("p2")
        advanceUntilIdle()

        assertEquals(listOf("Déménagement", "Canapé"), viewModel.uiState.value.projects.map { it.name })
        assertEquals(ProjectViewModel.MESSAGE_DELETE, viewModel.uiState.value.error)
    }

    @Test
    fun `a drag moves the row at once, and the lifted finger sends the whole order`() = runTest {
        val answer = CompletableDeferred<Result<List<Project>>>()
        coEvery { repository.reorderProjects(listOf("p2", "p1")) } coAnswers { answer.await() }
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.previewMove(1, 0)
        assertEquals(listOf("Canapé", "Déménagement"), viewModel.uiState.value.projects.map { it.name })

        viewModel.endMove()
        runCurrent()
        // Optimistic: the new order is on screen while the request is on its way.
        assertEquals(listOf("Canapé", "Déménagement"), viewModel.uiState.value.projects.map { it.name })

        val stored = listOf(sofa.copy(position = 0, unmetDependencies = listOf("p1")), move.copy(position = 1))
        answer.complete(Result.success(stored))
        advanceUntilIdle()

        assertEquals(stored, viewModel.uiState.value.projects)
        assertNull(viewModel.uiState.value.error)
        coVerify(exactly = 1) { repository.reorderProjects(listOf("p2", "p1")) }
    }

    @Test
    fun `a refused reorder puts the order back and says so`() = runTest {
        coEvery { repository.reorderProjects(any()) } returns Result.failure(RuntimeException(""))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.previewMove(1, 0)
        viewModel.endMove()
        advanceUntilIdle()

        assertEquals(listOf("Déménagement", "Canapé"), viewModel.uiState.value.projects.map { it.name })
        assertEquals(ProjectViewModel.MESSAGE_REORDER, viewModel.uiState.value.error)
    }

    @Test
    fun `a reorder refused in English says so in French only`() = runTest {
        coEvery { repository.reorderProjects(any()) } returns Result.failure(
            ApiRefusalException(400, "The list must name every project, in the new order. Missing: p3."),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.previewMove(1, 0)
        viewModel.endMove()
        advanceUntilIdle()

        assertEquals(ProjectViewModel.MESSAGE_REORDER, viewModel.uiState.value.error)
    }

    @Test
    fun `a refused reorder reads the list again, with a project created during the drag`() = runTest {
        val bike = Project(id = "p3", name = "Vélo", amountCents = 90000, position = 2)
        coEvery { repository.reorderProjects(any()) } returns
            Result.failure(ApiRefusalException(400, "The list must name every project, in the new order. Missing: p3."))
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.getProjects() } returns Result.success(listOf(move, sofa, bike))

        viewModel.previewMove(1, 0)
        viewModel.endMove()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Déménagement", "Canapé", "Vélo"), state.projects.map { it.name })
        assertFalse(state.isLoading)
        assertEquals(ProjectViewModel.MESSAGE_REORDER, state.error)
    }

    @Test
    fun `a reorder answered during the next drag does not undo the order under the finger`() = runTest {
        val bike = Project(id = "p3", name = "Vélo", amountCents = 90000, position = 2)
        coEvery { repository.getProjects() } returns Result.success(listOf(move, sofa, bike))
        val answer = CompletableDeferred<Result<List<Project>>>()
        coEvery { repository.reorderProjects(listOf("p2", "p1", "p3")) } coAnswers { answer.await() }
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.previewMove(1, 0)
        viewModel.endMove()
        runCurrent()
        viewModel.previewMove(2, 0)
        answer.complete(Result.success(listOf(sofa, move, bike)))
        advanceUntilIdle()

        assertEquals(listOf("Vélo", "Canapé", "Déménagement"), viewModel.uiState.value.projects.map { it.name })
    }

    @Test
    fun `a drag that ends where it started sends nothing`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.previewMove(1, 0)
        viewModel.previewMove(0, 1)
        viewModel.endMove()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.reorderProjects(any()) }
    }

    @Test
    fun `the accessibility action moves a project by one place`() = runTest {
        coEvery { repository.reorderProjects(listOf("p2", "p1")) } returns
            Result.success(listOf(sofa.copy(position = 0), move.copy(position = 1)))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.moveBy("p2", -1)
        advanceUntilIdle()

        assertEquals(listOf("Canapé", "Déménagement"), viewModel.uiState.value.projects.map { it.name })
    }

    @Test
    fun `a project written elsewhere reaches the list through Mercure`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        val renamed = sofa.copy(name = "Canapé d'angle")
        coEvery { repository.getProjects() } returns Result.success(listOf(move, renamed))

        pushes.emit(MercureEvent(data = """{"@id":"/api/finance/projects/p2","name":"Canapé d'angle"}"""))
        advanceUntilIdle()

        assertEquals(listOf("Déménagement", "Canapé d'angle"), viewModel.uiState.value.projects.map { it.name })
        coVerify { mercureService.subscribe("/users/user-1/api/finance/projects/{id}") }
    }

    @Test
    fun `a push during a drag does not undo the order under the finger`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.previewMove(1, 0)
        pushes.emit(MercureEvent(data = """{"@id":"/api/finance/projects/p1"}"""))
        advanceUntilIdle()

        assertEquals(listOf("Canapé", "Déménagement"), viewModel.uiState.value.projects.map { it.name })
        coVerify(exactly = 1) { repository.getProjects() }
    }
}
