package com.maggie.app.ui.screens.grocery

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Aisle
import com.maggie.app.data.repository.StoreRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StoreAislesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var storeRepository: StoreRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository

    private fun aisle(id: String, name: String, position: Int) =
        Aisle(id = id, store = "/api/stores/carrefour", name = name, position = position)

    private val fruits = aisle("a-fruits", "Fruits et légumes", 1)
    private val cremerie = aisle("a-cremerie", "Crèmerie", 2)
    private val boulangerie = aisle("a-boulangerie", "Boulangerie", 3)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        storeRepository = mockk()
        mercureService = mockk()
        authRepository = mockk()
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns emptyFlow()
        coEvery { storeRepository.getAisles("carrefour") } returns Result.success(listOf(fruits, cremerie, boulangerie))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = StoreAislesViewModel("carrefour", storeRepository, mercureService, authRepository)

    private fun StoreAislesViewModel.names() = uiState.value.aisles.map { it.name }

    @Test
    fun `opening the screen lists the store's aisles in the order of the way`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf("Fruits et légumes", "Crèmerie", "Boulangerie"), viewModel.names())
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
        verify { mercureService.subscribe("/users/user-1/api/aisles/{id}") }
    }

    @Test
    fun `a failed load says so`() = runTest {
        coEvery { storeRepository.getAisles("carrefour") } returns Result.failure(RuntimeException(""))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(StoreAislesViewModel.MESSAGE_LOAD, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `an added aisle comes last`() = runTest {
        val surgeles = aisle("a-surgeles", "Surgelés", 4)
        coEvery { storeRepository.createAisle("carrefour", "Surgelés") } returns Result.success(surgeles)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addAisle("  Surgelés ")
        advanceUntilIdle()

        assertEquals(listOf("Fruits et légumes", "Crèmerie", "Boulangerie", "Surgelés"), viewModel.names())
    }

    @Test
    fun `a blank name is not sent`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addAisle("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { storeRepository.createAisle(any(), any()) }
    }

    @Test
    fun `a refused aisle shows the API's reason`() = runTest {
        coEvery { storeRepository.createAisle(any(), any()) } returns
            Result.failure(RuntimeException("Ce magasin a déjà un rayon « Crèmerie »."))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addAisle("crèmerie")
        advanceUntilIdle()

        assertEquals("Ce magasin a déjà un rayon « Crèmerie ».", viewModel.uiState.value.error)
        assertEquals(3, viewModel.uiState.value.aisles.size)

        viewModel.dismissError()
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `renaming an aisle changes its name in place`() = runTest {
        coEvery { storeRepository.renameAisle("a-cremerie", "Produits laitiers") } returns
            Result.success(cremerie.copy(name = "Produits laitiers"))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.renameAisle("a-cremerie", "Produits laitiers")
        advanceUntilIdle()

        assertEquals(listOf("Fruits et légumes", "Produits laitiers", "Boulangerie"), viewModel.names())
    }

    @Test
    fun `a refused rename keeps the old name`() = runTest {
        coEvery { storeRepository.renameAisle(any(), any()) } returns Result.failure(RuntimeException(""))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.renameAisle("a-cremerie", "Boulangerie")
        advanceUntilIdle()

        assertEquals(StoreAislesViewModel.MESSAGE_RENAME, viewModel.uiState.value.error)
        assertEquals(listOf("Fruits et légumes", "Crèmerie", "Boulangerie"), viewModel.names())
    }

    @Test
    fun `dragging follows the finger and sends nothing until it is lifted`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.previewMove(2, 1)
        viewModel.previewMove(1, 0)
        advanceUntilIdle()

        assertEquals(listOf("Boulangerie", "Fruits et légumes", "Crèmerie"), viewModel.names())
        coVerify(exactly = 0) { storeRepository.moveAisle(any(), any()) }
    }

    @Test
    fun `lifting the finger sends one move with the new place, 1 being the first`() = runTest {
        coEvery { storeRepository.moveAisle(any(), any()) } returns Result.success(boulangerie.copy(position = 1))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.previewMove(2, 1)
        viewModel.previewMove(1, 0)
        viewModel.endMove("a-boulangerie")
        advanceUntilIdle()

        coVerify(exactly = 1) { storeRepository.moveAisle(any(), any()) }
        coVerify { storeRepository.moveAisle("a-boulangerie", 1) }
        assertEquals(listOf(1, 2, 3), viewModel.uiState.value.aisles.map { it.position })
    }

    @Test
    fun `an aisle dropped back where it was is not sent`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.previewMove(0, 1)
        viewModel.previewMove(1, 0)
        viewModel.endMove("a-fruits")
        advanceUntilIdle()

        coVerify(exactly = 0) { storeRepository.moveAisle(any(), any()) }
    }

    @Test
    fun `a refused move says so and reads the order again`() = runTest {
        coEvery { storeRepository.moveAisle(any(), any()) } returns Result.failure(RuntimeException(""))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.previewMove(0, 2)
        viewModel.endMove("a-fruits")
        advanceUntilIdle()

        assertEquals(StoreAislesViewModel.MESSAGE_MOVE, viewModel.uiState.value.error)
        assertEquals(listOf("Fruits et légumes", "Crèmerie", "Boulangerie"), viewModel.names())
        coVerify(exactly = 2) { storeRepository.getAisles("carrefour") }
    }

    @Test
    fun `deleting an aisle removes it`() = runTest {
        coEvery { storeRepository.deleteAisle("a-cremerie") } returns Result.success(Unit)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteAisle("a-cremerie")
        advanceUntilIdle()

        assertEquals(listOf("Fruits et légumes", "Boulangerie"), viewModel.names())
    }

    @Test
    fun `a refused deletion brings the aisle back`() = runTest {
        coEvery { storeRepository.deleteAisle(any()) } returns Result.failure(RuntimeException(""))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteAisle("a-cremerie")
        advanceUntilIdle()

        assertEquals(StoreAislesViewModel.MESSAGE_DELETE, viewModel.uiState.value.error)
        assertEquals(listOf("Fruits et légumes", "Crèmerie", "Boulangerie"), viewModel.names())
    }

    @Test
    fun `a change to this store's aisles made elsewhere is read again`() = runTest {
        val updates = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns updates
        val viewModel = createViewModel()
        advanceUntilIdle()
        coEvery { storeRepository.getAisles("carrefour") } returns Result.success(listOf(cremerie, fruits))

        updates.emit(MercureEvent(data = """{"name":"Crèmerie","position":1,"storeId":"carrefour"}"""))
        advanceUntilIdle()

        assertEquals(listOf("Crèmerie", "Fruits et légumes"), viewModel.names())
    }

    @Test
    fun `a deleted aisle, whose payload names no store, is read again too`() = runTest {
        val updates = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns updates
        createViewModel()
        advanceUntilIdle()

        updates.emit(MercureEvent(data = """{"deleted":true}"""))
        advanceUntilIdle()

        coVerify(exactly = 2) { storeRepository.getAisles("carrefour") }
    }

    @Test
    fun `another store's aisle changes nothing here`() = runTest {
        val updates = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns updates
        createViewModel()
        advanceUntilIdle()

        updates.emit(MercureEvent(data = """{"name":"Frais","position":1,"storeId":"biocoop"}"""))
        advanceUntilIdle()

        coVerify(exactly = 1) { storeRepository.getAisles("carrefour") }
    }
}
