package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.ProjectFundingSourceRequest
import com.maggie.app.data.api.ProjectOptionsParams
import com.maggie.app.data.api.ProjectWriteRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.PlannedProject
import com.maggie.app.data.model.PlanningMonth
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ProjectDateMode
import com.maggie.app.data.model.ProjectPlanning
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.FundingKind
import com.maggie.app.data.model.ProjectFundingSource
import com.maggie.app.data.model.ProjectOption
import com.maggie.app.data.model.ProjectOptions
import com.maggie.app.data.model.projectFundingSummary
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

    // « Déménagement » then « Canapé » at 500 €/month: the first is bought in month 5, the second in month 8.
    private val planning = ProjectPlanning(
        startMonth = "2026-10",
        isIncomeKnown = true,
        projects = listOf(
            PlannedProject(projectId = "p1", name = "Déménagement", position = 0, amountCents = 250000, savingFromMonth = "2026-10", purchaseMonth = "2027-03"),
            PlannedProject(projectId = "p2", name = "Canapé", position = 1, amountCents = 120000, savingFromMonth = "2027-03", purchaseMonth = "2027-06"),
        ),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.getProjectPlanning() } returns Result.success(planning)
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

    @Test
    fun `the planning loads with the list, the Gantt waits for its answer`() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.isPlanningLoading)
        assertNull(viewModel.uiState.value.planning)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isPlanningLoading)
        assertFalse(state.planningError)
        assertEquals(planning, state.planning)
    }

    @Test
    fun `a planning that cannot be read says so and leaves the list alone`() = runTest {
        coEvery { repository.getProjectPlanning() } returns Result.failure(RuntimeException("Unable to resolve host"))
        val viewModel = viewModel()

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.planningError)
        assertFalse(state.isPlanningLoading)
        assertNull(state.planning)
        assertEquals(listOf("Déménagement", "Canapé"), state.projects.map { it.name })
        assertNull(state.error)
    }

    @Test
    fun `retrying the planning reads it again, and only it`() = runTest {
        coEvery { repository.getProjectPlanning() } returns Result.failure(RuntimeException(""))
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.getProjectPlanning() } returns Result.success(planning)

        viewModel.refreshPlanning()
        assertTrue(viewModel.uiState.value.isPlanningLoading)
        assertFalse(viewModel.uiState.value.planningError)
        advanceUntilIdle()

        assertEquals(planning, viewModel.uiState.value.planning)
        coVerify(exactly = 1) { repository.getProjects() }
    }

    @Test
    fun `the months of the funding chart come with the planning and follow its re-reads (MAG-528)`() = runTest {
        val over = PlanningMonth("2026-10", capacityCents = 50000, salaryCents = 200000, isOverCapacity = true)
        val within = PlanningMonth("2026-10", capacityCents = 50000, salaryCents = 50000)
        coEvery { repository.getProjectPlanning() } returns Result.success(planning.copy(months = listOf(over)))
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.planning?.months.isNullOrEmpty())

        advanceUntilIdle()
        assertEquals(listOf(over), viewModel.uiState.value.planning!!.months)

        coEvery { repository.getProjectPlanning() } returns Result.success(planning.copy(months = listOf(within)))
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(listOf(within), viewModel.uiState.value.planning!!.months)

        coEvery { repository.getProjectPlanning() } returns Result.failure(RuntimeException(""))
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(listOf(within), viewModel.uiState.value.planning!!.months)
        assertFalse(viewModel.uiState.value.planningError)
    }

    @Test
    fun `a failed re-read keeps the Gantt already on screen`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.getProjectPlanning() } returns Result.failure(RuntimeException(""))

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(planning, viewModel.uiState.value.planning)
        assertFalse(viewModel.uiState.value.planningError)
    }

    @Test
    fun `a reorder reads the planning again, Canapé first`() = runTest {
        val reordered = planning.copy(
            projects = listOf(
                planning.projects[1].copy(position = 0, savingFromMonth = "2026-10", purchaseMonth = "2027-01"),
                planning.projects[0].copy(position = 1, savingFromMonth = "2027-01", purchaseMonth = "2027-06"),
            ),
        )
        coEvery { repository.reorderProjects(listOf("p2", "p1")) } returns
            Result.success(listOf(sofa.copy(position = 0), move.copy(position = 1)))
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.getProjectPlanning() } returns Result.success(reordered)

        viewModel.previewMove(1, 0)
        viewModel.endMove()
        advanceUntilIdle()

        assertEquals(listOf("Canapé", "Déménagement"), viewModel.uiState.value.planning!!.projects.map { it.name })
        coVerify(exactly = 2) { repository.getProjectPlanning() }
    }

    @Test
    fun `a saved project and a deleted one read the planning again`() = runTest {
        coEvery { repository.deleteProject("p2") } returns Result.success(Unit)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.delete("p2")
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.getProjectPlanning() }
    }

    @Test
    fun `a loan, the cushion or an account written elsewhere reads the planning, not the list`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        val later = planning.copy(projects = planning.projects.map { it.copy(purchaseMonth = "2028-01") })
        coEvery { repository.getProjectPlanning() } returns Result.success(later)

        pushes.emit(MercureEvent(data = """{"@id":"/api/loans/l1"}"""))
        advanceUntilIdle()

        assertEquals(later, viewModel.uiState.value.planning)
        coVerify { mercureService.subscribe("/users/user-1/api/loans/{id}") }
        coVerify { mercureService.subscribe("/users/user-1/api/safety_cushions/{id}") }
        coVerify { mercureService.subscribe("/users/user-1/api/accounts/{id}") }
    }

    @Test
    fun `an answer to an older read does not replace a newer one`() = runTest {
        val first = CompletableDeferred<Result<ProjectPlanning>>()
        coEvery { repository.getProjectPlanning() } coAnswers { first.await() }
        val viewModel = viewModel()
        runCurrent()
        val newer = planning.copy(currency = "USD")
        coEvery { repository.getProjectPlanning() } returns Result.success(newer)

        viewModel.refreshPlanning()
        advanceUntilIdle()
        first.complete(Result.success(planning))
        advanceUntilIdle()

        assertEquals(newer, viewModel.uiState.value.planning)
    }

    // --- MAG-525: funding sources and priced options ---------------------------------------------

    private val car = Project(
        id = "p3", name = "Voiture", amountCents = 1_000_000, position = 2,
        fundingSources = listOf(
            ProjectFundingSource(kind = "savings", amountCents = 100_000, accountId = "acc-1"),
            ProjectFundingSource(kind = "loan", amountCents = 600_000, loanId = "loan-1"),
        ),
        salaryCents = 300_000,
    )

    private fun priced(downPaymentCents: Int = 0) = ProjectOptions(
        projectId = "p3",
        amountCents = 1_000_000,
        loanRateBasisPoints = 400,
        loanMonths = 24,
        downPaymentCents = downPaymentCents,
        yieldBasisPoints = 300,
        cash = ProjectOption(totalCostCents = 900_000, availableInMonths = 20),
        loan = ProjectOption(totalCostCents = 950_000, monthlyPaymentCents = 40_000, availableInMonths = 0),
        partial = ProjectOption(totalCostCents = 920_000, monthlyPaymentCents = 20_000, availableInMonths = 8, nImpactMonths = 3),
    )

    private suspend fun kotlinx.coroutines.test.TestScope.viewModelWithCar(): ProjectViewModel {
        coEvery { repository.getProjects() } returns Result.success(listOf(move, sofa, car))
        coEvery { repository.getOptions("p3", any()) } returns Result.success(priced())
        val viewModel = viewModel()
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `the projects load with their sources and the salary that is left`() = runTest {
        val viewModel = viewModelWithCar()

        val loaded = viewModel.uiState.value.projects.single { it.id == "p3" }
        assertEquals(listOf("savings", "loan"), loaded.fundingSources.map { it.kind })
        assertEquals(300_000, loaded.salaryCents)
        assertEquals("économies + emprunt + salaire", projectFundingSummary(loaded))
        assertNull(viewModel.uiState.value.sheet)
    }

    @Test
    fun `opening a sheet prices the three options and holds the sources as drafts`() = runTest {
        val viewModel = viewModelWithCar()

        viewModel.openSheet("p3")
        advanceUntilIdle()

        val sheet = viewModel.uiState.value.sheet!!
        assertEquals("p3", sheet.projectId)
        assertEquals(priced(), sheet.options)
        assertFalse(sheet.isLoadingOptions)
        assertNull(sheet.optionsError)
        assertEquals(listOf(FundingKind.SAVINGS, FundingKind.LOAN), sheet.sources.map { it.kind })
        assertEquals("1000,00", sheet.sources[0].amount.replace(" ", "").replace('.', ','))
        // The yield is the only parameter the screen supplies by itself.
        coVerify { repository.getOptions("p3", ProjectOptionsParams(yieldBasisPoints = 300)) }
    }

    @Test
    fun `options that fail to price say so and keep no figures`() = runTest {
        val viewModel = viewModelWithCar()
        coEvery { repository.getOptions("p3", any()) } returns Result.failure(RuntimeException("boom"))

        viewModel.openSheet("p3")
        advanceUntilIdle()

        val sheet = viewModel.uiState.value.sheet!!
        assertNull(sheet.options)
        assertEquals(ProjectViewModel.MESSAGE_OPTIONS, sheet.optionsError)
    }

    @Test
    fun `a project with no loan and no typed terms asks for them instead of calling the API`() = runTest {
        val viewModel = viewModelWithCar()

        viewModel.openSheet("p1")
        advanceUntilIdle()

        assertEquals(ProjectViewModel.MESSAGE_OPTIONS_TERMS, viewModel.uiState.value.sheet!!.optionsError)
        coVerify(exactly = 0) { repository.getOptions("p1", any()) }
    }

    @Test
    fun `the typed parameters price the options once applied`() = runTest {
        val viewModel = viewModelWithCar()
        viewModel.openSheet("p1")
        advanceUntilIdle()
        coEvery { repository.getOptions("p1", any()) } returns Result.success(priced(downPaymentCents = 400_000).copy(projectId = "p1"))

        viewModel.editOptions { it.copy(rate = "4", months = "24", downPayment = "4000") }
        coVerify(exactly = 0) { repository.getOptions("p1", any()) }
        viewModel.applyOptions()
        advanceUntilIdle()

        coVerify {
            repository.getOptions(
                "p1",
                ProjectOptionsParams(loanRateBasisPoints = 400, loanMonths = 24, downPaymentCents = 400_000, yieldBasisPoints = 300),
            )
        }
        assertEquals(400_000, viewModel.uiState.value.sheet!!.options!!.downPaymentCents)
    }

    @Test
    fun `a parameter that is not a number is said, not sent`() = runTest {
        val viewModel = viewModelWithCar()
        viewModel.openSheet("p3")
        advanceUntilIdle()

        viewModel.editOptions { it.copy(rate = "abc") }
        viewModel.applyOptions()
        advanceUntilIdle()

        assertEquals(ProjectViewModel.MESSAGE_OPTIONS_INVALID, viewModel.uiState.value.sheet!!.optionsError)
        coVerify(exactly = 1) { repository.getOptions("p3", any()) }
    }

    @Test
    fun `saved sources replace the project's and the options are priced again`() = runTest {
        val viewModel = viewModelWithCar()
        viewModel.openSheet("p3")
        advanceUntilIdle()
        val saved = car.copy(
            fundingSources = listOf(
                ProjectFundingSource(kind = "savings", amountCents = 200_000, accountId = "acc-1"),
                ProjectFundingSource(kind = "loan", amountCents = 600_000, loanId = "loan-1"),
            ),
            salaryCents = 200_000,
        )
        val requests = slot<List<ProjectFundingSourceRequest>>()
        coEvery { repository.setFunding("p3", capture(requests)) } returns Result.success(saved)

        viewModel.editSource(0) { it.copy(amount = "2000") }
        viewModel.saveFunding("p3", viewModel.uiState.value.sheet!!.sources)
        advanceUntilIdle()

        assertEquals(listOf("savings", "loan"), requests.captured.map { it.kind })
        assertEquals(200_000, requests.captured.first().amountCents)
        assertEquals("acc-1", requests.captured.first().account)
        val state = viewModel.uiState.value
        assertEquals(200_000, state.projects.single { it.id == "p3" }.salaryCents)
        assertTrue(state.sheet!!.fundingSaved)
        assertFalse(state.sheet!!.dirty)
        assertFalse(state.sheet!!.isSavingFunding)
        coVerify(exactly = 2) { repository.getOptions("p3", any()) }
    }

    @Test
    fun `a refusal keeps the previous sources and shows the API's message as it was written`() = runTest {
        val viewModel = viewModelWithCar()
        viewModel.openSheet("p3")
        advanceUntilIdle()
        coEvery { repository.setFunding("p3", any()) } returns
            Result.failure(ApiRefusalException(400, "Savings account is the cushion."))

        viewModel.editSource(0) { it.copy(amount = "2000") }
        viewModel.saveFunding("p3", viewModel.uiState.value.sheet!!.sources)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Savings account is the cushion.", state.sheet!!.fundingError)
        assertEquals(listOf("savings", "loan"), state.projects.single { it.id == "p3" }.fundingSources.map { it.kind })
        assertFalse(state.sheet!!.isSavingFunding)
        assertTrue(state.sheet!!.dirty)
    }

    @Test
    fun `sources that overshoot the amount are refused before any request`() = runTest {
        val viewModel = viewModelWithCar()
        viewModel.openSheet("p3")
        advanceUntilIdle()

        viewModel.editSource(0) { it.copy(amount = "9999") }
        viewModel.saveFunding("p3", viewModel.uiState.value.sheet!!.sources)
        advanceUntilIdle()

        assertEquals("Les sources dépassent le montant du projet : baissez-les.", viewModel.uiState.value.sheet!!.fundingError)
        coVerify(exactly = 0) { repository.setFunding(any(), any()) }
    }

    @Test
    fun `a push refreshes the sources of the open sheet and prices it again`() = runTest {
        val viewModel = viewModelWithCar()
        viewModel.openSheet("p3")
        advanceUntilIdle()
        val elsewhere = car.copy(
            fundingSources = listOf(
                ProjectFundingSource(kind = "savings", amountCents = 300_000, accountId = "acc-1"),
                ProjectFundingSource(kind = "loan", amountCents = 600_000, loanId = "loan-1"),
            ),
            salaryCents = 100_000,
        )
        coEvery { repository.getProjects() } returns Result.success(listOf(move, sofa, elsewhere))

        pushes.emit(MercureEvent(data = """{"@id":"/api/finance/projects/p3"}"""))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(100_000, state.projects.single { it.id == "p3" }.salaryCents)
        assertEquals(listOf(FundingKind.SAVINGS, FundingKind.LOAN), state.sheet!!.sources.map { it.kind })
        coVerify(exactly = 2) { repository.getOptions("p3", any()) }
    }

    @Test
    fun `a push does not overwrite sources being edited`() = runTest {
        val viewModel = viewModelWithCar()
        viewModel.openSheet("p3")
        advanceUntilIdle()
        viewModel.editSource(0) { it.copy(amount = "1500") }
        coEvery { repository.getProjects() } returns Result.success(listOf(move, sofa, car.copy(salaryCents = 1)))

        pushes.emit(MercureEvent(data = """{"@id":"/api/finance/projects/p3"}"""))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.sheet!!.sources[0].amount.startsWith("1500"))
    }

    @Test
    fun `closing the sheet forgets it and a late answer does not bring it back`() = runTest {
        val viewModel = viewModelWithCar()
        val answer = CompletableDeferred<Result<ProjectOptions>>()
        coEvery { repository.getOptions("p3", any()) } coAnswers { answer.await() }

        viewModel.openSheet("p3")
        runCurrent()
        viewModel.closeSheet()
        answer.complete(Result.success(priced()))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.sheet)
    }

    @Test
    fun `adding a loan opens blank conditions and the accounts load once`() = runTest {
        val viewModel = viewModelWithCar()
        coEvery { repository.getAccounts() } returns Result.success(listOf(Account(id = "acc-1", name = "Livret")))
        viewModel.openSheet("p1")
        advanceUntilIdle()

        viewModel.addSource(FundingKind.LOAN)
        viewModel.loadAccounts()
        advanceUntilIdle()
        viewModel.loadAccounts()
        advanceUntilIdle()

        val sheet = viewModel.uiState.value.sheet!!
        assertEquals(LoanTermsDraft(), sheet.sources.single().loanTerms)
        assertTrue(sheet.dirty)
        assertEquals(listOf("acc-1"), viewModel.uiState.value.accounts.map { it.id })
        coVerify(exactly = 1) { repository.getAccounts() }
    }

    // A car that receives 1 000 € from the resale of a box: the sheet edits only the 9 000 € left to the others.
    private val carWithResale = car.copy(
        fundingSources = car.fundingSources + listOf(
            ProjectFundingSource(kind = "resale", amountCents = 100_000, sourceProjectId = "p9"),
            ProjectFundingSource(kind = "somethingNew", amountCents = 5),
        ),
    )

    private suspend fun kotlinx.coroutines.test.TestScope.viewModelWithResale(): ProjectViewModel {
        coEvery { repository.getProjects() } returns Result.success(listOf(move, sofa, carWithResale))
        coEvery { repository.getOptions("p3", any()) } returns Result.success(priced())
        val viewModel = viewModel()
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `a resale the project receives is read, and kept out of the drafts the tablet edits (MAG-557)`() = runTest {
        val viewModel = viewModelWithResale()

        viewModel.openSheet("p3")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(100_000, state.projects.single { it.id == "p3" }.resaleCents)
        assertEquals(listOf(FundingKind.SAVINGS, FundingKind.LOAN), state.sheet!!.sources.map { it.kind })
    }

    @Test
    fun `saving does not send the resale back, and counts it in the amount the others share`() = runTest {
        val viewModel = viewModelWithResale()
        viewModel.openSheet("p3")
        advanceUntilIdle()
        val requests = slot<List<ProjectFundingSourceRequest>>()
        coEvery { repository.setFunding("p3", capture(requests)) } returns Result.success(carWithResale)

        viewModel.editSource(0) { it.copy(amount = "3000") }
        viewModel.saveFunding("p3", viewModel.uiState.value.sheet!!.sources)
        advanceUntilIdle()

        assertEquals(listOf("savings", "loan"), requests.captured.map { it.kind })
        assertEquals(300_000, requests.captured.first().amountCents)
        val sheet = viewModel.uiState.value.sheet!!
        assertTrue(sheet.fundingSaved)
        assertEquals(listOf(FundingKind.SAVINGS, FundingKind.LOAN), sheet.sources.map { it.kind })
    }

    @Test
    fun `sources that overshoot what the resale leaves are refused before any request`() = runTest {
        val viewModel = viewModelWithResale()
        viewModel.openSheet("p3")
        advanceUntilIdle()

        // 3 001 € + 6 000 € of loan = 9 001 €, one euro over the 9 000 € left once the 1 000 € resale is counted.
        viewModel.editSource(0) { it.copy(amount = "3001") }
        viewModel.saveFunding("p3", viewModel.uiState.value.sheet!!.sources)
        advanceUntilIdle()

        assertEquals("Les sources dépassent le montant du projet : baissez-les.", viewModel.uiState.value.sheet!!.fundingError)
        coVerify(exactly = 0) { repository.setFunding(any(), any()) }
    }

    @Test
    fun `a push that brings a new resale refreshes the open sheet without it becoming an editable source`() = runTest {
        val viewModel = viewModelWithCar()
        viewModel.openSheet("p3")
        advanceUntilIdle()
        coEvery { repository.getProjects() } returns Result.success(listOf(move, sofa, carWithResale))

        pushes.emit(MercureEvent(data = """{"@id":"/api/finance/projects/p3"}"""))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(100_000, state.projects.single { it.id == "p3" }.resaleCents)
        assertEquals(listOf(FundingKind.SAVINGS, FundingKind.LOAN), state.sheet!!.sources.map { it.kind })
    }

    @Test
    fun `an investment arrives with the project and reaches the list through a push`() = runTest {
        val box = Project(
            id = "p9", name = "Box à louer", amountCents = 1_800_000, position = 3,
            investment = com.maggie.app.data.model.ProjectInvestment(projectId = "p9", monthlyRentCents = 16_000, monthlyBalanceCents = 1_892),
        )
        val viewModel = viewModel()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.projects.firstOrNull { it.id == "p9" })
        coEvery { repository.getProjects() } returns Result.success(listOf(move, sofa, box))

        pushes.emit(MercureEvent(data = """{"@id":"/api/finance/projects/p9"}"""))
        advanceUntilIdle()

        assertEquals(16_000, viewModel.uiState.value.projects.single { it.id == "p9" }.investment?.monthlyRentCents)
    }
}
