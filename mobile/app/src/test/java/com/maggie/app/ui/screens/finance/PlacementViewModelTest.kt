package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.PlacementWriteRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Placement
import com.maggie.app.data.model.PlacementFrequency
import com.maggie.app.data.model.PlacementPoint
import com.maggie.app.data.model.PlacementSimulation
import com.maggie.app.data.repository.PlacementRepository
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
import kotlinx.coroutines.test.advanceTimeBy
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

/**
 * « Placements » (MAG-555): one test per transition of the list, the projection, the steppers and
 * the form. The story is the seed's — a PEA paid monthly and a Livret paid once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlacementViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<PlacementRepository>()
    private val authRepository = mockk<AuthRepository>()
    private val mercureService = mockk<MercureService>()
    private val pushes = MutableSharedFlow<MercureEvent>()

    private val pea = Placement(
        id = "pea", name = "PEA", initial = 200000, deposit = 15000, frequency = "monthly", annualRate = 600, years = 20,
        finalCents = 7443007, paid = 3800000, interest = 3643007, rent = 24810, monthlyDeposit = 15000,
    )
    private val book = Placement(
        id = "book", name = "Livret jeune de Léo", initial = 50000, deposit = 0, frequency = "once", annualRate = 300, years = 10,
        finalCents = 67196, paid = 50000, interest = 17196, rent = 224,
    )

    private fun simulation(of: Placement) = PlacementSimulation(
        finalCents = of.finalCents, paid = of.paid, interest = of.interest, rent = of.rent,
        curve = listOf(PlacementPoint(0, of.initial.toLong(), of.initial.toLong()), PlacementPoint(of.years * 12, of.finalCents, of.paid)),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.getPlacements() } returns Result.success(listOf(pea, book))
        coEvery { repository.getSimulation("pea") } returns Result.success(simulation(pea))
        coEvery { repository.getSimulation("book") } returns Result.success(simulation(book))
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns pushes
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = PlacementViewModel(repository, mercureService, authRepository)

    @Test
    fun `loading, then the placements with the first selected and its curve`() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasLoaded)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.hasLoaded)
        assertEquals(listOf("PEA", "Livret jeune de Léo"), state.placements.map { it.name })
        assertEquals("pea", state.selected?.id)
        assertEquals(simulation(pea), state.simulation)
        assertEquals(15000L, state.monthlyDeposits)
        assertNull(state.error)
    }

    @Test
    fun `no placement yet is an empty list, loaded, with nothing to simulate`() = runTest {
        coEvery { repository.getPlacements() } returns Result.success(emptyList())
        val viewModel = viewModel()

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasLoaded)
        assertNull(viewModel.uiState.value.selected)
        assertNull(viewModel.uiState.value.simulation)
        coVerify(exactly = 0) { repository.getSimulation(any()) }
    }

    @Test
    fun `a failed load is a French sentence, not the exception's text, nor an empty list`() = runTest {
        coEvery { repository.getPlacements() } returns Result.failure(RuntimeException("Unable to resolve host \"api\""))
        val viewModel = viewModel()

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.hasLoaded)
        assertEquals(PlacementViewModel.MESSAGE_LOAD, state.error)
    }

    @Test
    fun `a failed simulation says so in its place and keeps the figures`() = runTest {
        coEvery { repository.getSimulation("pea") } returns Result.failure(RuntimeException("timeout"))
        val viewModel = viewModel()

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.simulationFailed)
        assertNull(state.simulation)
        assertEquals(7443007L, state.selected?.finalCents)
        assertNull(state.error)
    }

    @Test
    fun `selecting a placement shows its curve, and a late curve of the previous one is dropped`() = runTest {
        val late = CompletableDeferred<Result<PlacementSimulation>>()
        coEvery { repository.getSimulation("pea") } coAnswers { late.await() }
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.select("book")
        advanceUntilIdle()
        late.complete(Result.success(simulation(pea)))
        advanceUntilIdle()

        assertEquals("book", viewModel.uiState.value.selected?.id)
        assertEquals(simulation(book), viewModel.uiState.value.simulation)
    }

    @Test
    fun `a new placement is created, selected, and the list read again`() = runTest {
        val request = slot<PlacementWriteRequest>()
        val created = Placement(id = "life", name = "Assurance-vie", deposit = 120000, frequency = "yearly", annualRate = 350, years = 15)
        coEvery { repository.createPlacement(capture(request)) } returns Result.success(created)
        coEvery { repository.getSimulation("life") } returns Result.success(simulation(created))
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.getPlacements() } returns Result.success(listOf(created, pea, book))

        viewModel.openNew()
        viewModel.editDraft {
            it.copy(name = "Assurance-vie", deposit = "1200", frequency = PlacementFrequency.YEARLY, rate = "3,5", years = "15")
        }
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.form!!.done)
        assertEquals("Placement créé", state.message)
        assertEquals(listOf("Assurance-vie", "PEA", "Livret jeune de Léo"), state.placements.map { it.name })
        assertEquals("life", state.selected?.id)
        assertEquals(simulation(created), state.simulation)
        assertEquals(PlacementWriteRequest("Assurance-vie", 0, 120000, "yearly", 350, 15), request.captured)
    }

    @Test
    fun `a draft that pays nothing is not sent, the form says what is missing`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.openNew()
        viewModel.editDraft { it.copy(name = "Vide", rate = "3") }
        viewModel.save()
        advanceUntilIdle()

        val form = viewModel.uiState.value.form!!
        assertFalse(form.done)
        assertFalse(form.isSaving)
        assertEquals(PlacementViewModel.MESSAGE_NOTHING_PAID, form.errors[PlacementField.DEPOSIT])
        coVerify(exactly = 0) { repository.createPlacement(any()) }
    }

    @Test
    fun `the API's refusal of a placement that pays nothing is said in French`() = runTest {
        coEvery { repository.updatePlacement("book", any()) } returns
            Result.failure(ApiRefusalException(422, "deposit: A placement needs an initial payment or a deposit."))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.openEdit("book")
        viewModel.editDraft { it.copy(name = "Livret") }
        viewModel.save()
        advanceUntilIdle()

        val form = viewModel.uiState.value.form!!
        assertFalse(form.done)
        assertEquals("Un placement a besoin d'un versement initial ou d'un versement.", form.error)
    }

    @Test
    fun `a refusal the phone cannot translate, or no network, is a French sentence`() = runTest {
        coEvery { repository.updatePlacement("pea", any()) } returns
            Result.failure(ApiRefusalException(422, "years: This value should be between 1 and 60."))
        coEvery { repository.createPlacement(any()) } returns Result.failure(RuntimeException("Unable to resolve host"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.openEdit("pea")
        viewModel.save()
        advanceUntilIdle()
        assertEquals(PlacementViewModel.MESSAGE_SAVE, viewModel.uiState.value.form!!.error)

        viewModel.openNew()
        viewModel.editDraft { it.copy(name = "CTO", initial = "100", rate = "5", years = "5") }
        viewModel.save()
        advanceUntilIdle()
        assertEquals(PlacementViewModel.MESSAGE_SAVE, viewModel.uiState.value.form!!.error)
    }

    @Test
    fun `a placement edited is saved under its id and stays selected`() = runTest {
        val request = slot<PlacementWriteRequest>()
        val saved = book.copy(name = "Livret A de Léo", years = 12, finalCents = 71000)
        coEvery { repository.updatePlacement("book", capture(request)) } returns Result.success(saved)
        coEvery { repository.getSimulation("book") } returns Result.success(simulation(saved))
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.getPlacements() } returns Result.success(listOf(saved, pea))

        viewModel.openEdit("book")
        assertEquals("500", viewModel.uiState.value.form!!.draft.initial)
        viewModel.editDraft { it.copy(name = "Livret A de Léo", years = "12") }
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.form!!.done)
        assertEquals("Placement enregistré", state.message)
        assertEquals(12, request.captured.years)
        assertEquals("book", state.selected?.id)
        assertEquals(71000L, state.selected?.finalCents)
    }

    @Test
    fun `a placement that no longer exists does not open`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.openEdit("gone")

        assertNull(viewModel.uiState.value.form)
        assertEquals(PlacementViewModel.MESSAGE_NOT_FOUND, viewModel.uiState.value.error)
    }

    @Test
    fun `a deleted placement leaves at once, the next one is selected and the form closes`() = runTest {
        coEvery { repository.deletePlacement("pea") } returns Result.success(Unit)
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.getPlacements() } returns Result.success(listOf(book))

        viewModel.openEdit("pea")
        viewModel.delete("pea")

        assertEquals(listOf("Livret jeune de Léo"), viewModel.uiState.value.placements.map { it.name })
        assertEquals("book", viewModel.uiState.value.selected?.id)
        assertTrue(viewModel.uiState.value.form!!.done)

        advanceUntilIdle()
        assertEquals("Placement supprimé", viewModel.uiState.value.message)
        assertEquals(simulation(book), viewModel.uiState.value.simulation)
    }

    @Test
    fun `a failed deletion puts the placement back and says so in French`() = runTest {
        coEvery { repository.deletePlacement("book") } returns Result.failure(RuntimeException("Unable to resolve host"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.delete("book")
        advanceUntilIdle()

        assertEquals(listOf("PEA", "Livret jeune de Léo"), viewModel.uiState.value.placements.map { it.name })
        assertEquals(PlacementViewModel.MESSAGE_DELETE, viewModel.uiState.value.error)
    }

    @Test
    fun `steppers change the placement at once, and the pause sends it whole in one PATCH`() = runTest {
        val request = slot<PlacementWriteRequest>()
        val saved = pea.copy(deposit = 17000, annualRate = 650, years = 21, finalCents = 9000000)
        coEvery { repository.updatePlacement("pea", capture(request)) } returns Result.success(saved)
        coEvery { repository.getSimulation("pea") } returns Result.success(simulation(saved))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.step(PlacementStep.DEPOSIT, plus = true)
        viewModel.step(PlacementStep.DEPOSIT, plus = true)
        viewModel.step(PlacementStep.RATE, plus = true)
        viewModel.step(PlacementStep.YEARS, plus = true)
        val shown = viewModel.uiState.value.selected!!
        assertEquals(17000, shown.deposit)
        assertEquals(650, shown.annualRate)
        assertEquals(21, shown.years)

        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updatePlacement("pea", any()) }
        assertEquals(PlacementWriteRequest("PEA", 200000, 17000, "monthly", 650, 21), request.captured)
        assertEquals(9000000L, viewModel.uiState.value.selected?.finalCents)
        assertEquals(simulation(saved), viewModel.uiState.value.simulation)
    }

    @Test
    fun `the segmented control changes the rhythm of the deposit`() = runTest {
        val request = slot<PlacementWriteRequest>()
        coEvery { repository.updatePlacement("pea", capture(request)) } answers { Result.success(pea.copy(frequency = "quarterly")) }
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setFrequency(PlacementFrequency.QUARTERLY)
        advanceUntilIdle()

        assertEquals("quarterly", request.captured.frequency)
        assertEquals("quarterly", viewModel.uiState.value.selected?.frequency)
    }

    @Test
    fun `a step never leaves the bounds, nor a placement that pays nothing`() = runTest {
        coEvery { repository.getPlacements() } returns Result.success(listOf(pea.copy(initial = 0, deposit = 1000, years = 1, annualRate = 0)))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.step(PlacementStep.DEPOSIT, plus = false)
        viewModel.step(PlacementStep.YEARS, plus = false)
        viewModel.step(PlacementStep.RATE, plus = false)
        advanceUntilIdle()

        val shown = viewModel.uiState.value.selected!!
        assertEquals(1000, shown.deposit)
        assertEquals(1, shown.years)
        assertEquals(0, shown.annualRate)
        assertFalse(canStepDown(PlacementStep.DEPOSIT, shown))
        assertFalse(canStepDown(PlacementStep.YEARS, shown))
        coVerify(exactly = 0) { repository.updatePlacement(any(), any()) }
    }

    @Test
    fun `a tap during a PATCH on its way waits for it, and only the last answer is shown`() = runTest {
        val first = CompletableDeferred<Result<Placement>>()
        val requests = mutableListOf<PlacementWriteRequest>()
        coEvery { repository.updatePlacement("pea", capture(requests)) } coAnswers {
            if (requests.size == 1) first.await() else Result.success(pea.copy(years = 22, finalCents = 9500000))
        }
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.step(PlacementStep.YEARS, plus = true)
        advanceTimeBy(PlacementViewModel.STEP_DEBOUNCE_MS + 1)
        runCurrent()
        viewModel.step(PlacementStep.YEARS, plus = true)
        advanceTimeBy(PlacementViewModel.STEP_DEBOUNCE_MS + 1)
        runCurrent()
        // The second waits for the first: one request on its way at a time.
        assertEquals(1, requests.size)
        first.complete(Result.success(pea.copy(years = 21, finalCents = 8000000)))
        advanceUntilIdle()

        assertEquals(listOf(21, 22), requests.map { it.years })
        assertEquals(22, viewModel.uiState.value.selected?.years)
        assertEquals(9500000L, viewModel.uiState.value.selected?.finalCents)
    }

    @Test
    fun `a refused step puts the placement back as stored and says why in French`() = runTest {
        coEvery { repository.updatePlacement("pea", any()) } returns
            Result.failure(ApiRefusalException(422, "annualRate: This value should be between 0 and 10000."))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.step(PlacementStep.RATE, plus = true)
        assertEquals(650, viewModel.uiState.value.selected?.annualRate)
        advanceUntilIdle()

        assertEquals(600, viewModel.uiState.value.selected?.annualRate)
        assertEquals(PlacementViewModel.MESSAGE_SAVE, viewModel.uiState.value.error)
    }

    @Test
    fun `a placement written elsewhere reaches the screen through Mercure`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        val renamed = book.copy(name = "Livret A")
        coEvery { repository.getPlacements() } returns Result.success(listOf(pea, renamed))

        pushes.emit(MercureEvent(data = """{"@id":"/api/finance/placements/book","name":"Livret A"}"""))
        advanceUntilIdle()

        assertEquals(listOf("PEA", "Livret A"), viewModel.uiState.value.placements.map { it.name })
        coVerify { mercureService.subscribe("/users/user-1/api/finance/placements/{id}") }
    }

    @Test
    fun `a push while a step is on its way does not undo it`() = runTest {
        val answer = CompletableDeferred<Result<Placement>>()
        coEvery { repository.updatePlacement("pea", any()) } coAnswers { answer.await() }
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.step(PlacementStep.YEARS, plus = true)
        advanceTimeBy(PlacementViewModel.STEP_DEBOUNCE_MS + 1)
        runCurrent()
        pushes.emit(MercureEvent(data = """{"@id":"/api/finance/placements/pea"}"""))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(21, viewModel.uiState.value.selected?.years)
        coVerify(exactly = 1) { repository.getPlacements() }

        // Once answered, the next reading is the server's, which has the step.
        coEvery { repository.getPlacements() } returns Result.success(listOf(pea.copy(years = 21), book))
        answer.complete(Result.success(pea.copy(years = 21)))
        advanceUntilIdle()
        assertEquals(21, viewModel.uiState.value.selected?.years)
    }
}
