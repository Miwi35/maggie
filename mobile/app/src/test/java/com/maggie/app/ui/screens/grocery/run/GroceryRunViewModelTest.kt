package com.maggie.app.ui.screens.grocery.run

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.data.repository.GroceryListRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class GroceryRunViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var lists: GroceryListRepository
    private lateinit var status: ActivityStatusRepository
    private lateinit var mercure: MercureService
    private lateinit var auth: AuthRepository
    private val stream = MutableSharedFlow<MercureEvent>()
    private val written = mutableListOf<GroceryRunState>()

    private val superU = Store(id = "s-u", name = "Super U", visitOrder = 1)
    private val biocoop = Store(id = "s-b", name = "Biocoop", visitOrder = 2)
    private val market = Store(id = "s-m", name = "Marché", visitOrder = 3)
    private val empty = Store(id = "s-e", name = "Boucher", visitOrder = 4)

    private fun list(vararg extra: GroceryItem) = GroceryList(
        id = "list-1",
        items = listOf(
            GroceryItem(id = "1", label = "Lait", store = biocoop),
            GroceryItem(id = "2", label = "Pain", store = superU),
            GroceryItem(id = "3", label = "Beurre", store = superU),
            GroceryItem(id = "4", label = "Pommes", store = market),
            GroceryItem(id = "5", label = "Steak", store = empty, checked = true),
        ) + extra,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        lists = mockk()
        status = mockk()
        mercure = mockk()
        auth = mockk()
        coEvery { auth.getUserId() } returns "user-1"
        every { mercure.subscribe(any()) } returns stream
        every { mercure.reconnections(any()) } returns emptyFlow()
        coEvery { lists.getGroceryList() } returns Result.success(list())
        coEvery { status.get("courses") } returns Result.success(null)
        every { status.put("courses", any(), any()) } answers {
            written += checkNotNull(GroceryRunState.from(saved(thirdArg())))
            mockk(relaxed = true)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun saved(state: kotlinx.serialization.json.JsonObject, listId: String = "list-1") =
        ActivityStatus("courses", ActivityOrigin("list", listId), state, "2026-11-08T10:00:00+00:00", "2026-11-08T10:00:00+00:00")

    private fun viewModel() = GroceryRunViewModel(lists, status, mercure, auth, today = { LocalDate.of(2026, 11, 8) })

    private fun GroceryRunViewModel.tour() = uiState.value.tour.map { it.name }

    @Test
    fun `the tour lists the stores that hold articles in their habitual order, with their count`() = runTest {
        val vm = viewModel()
        assertEquals(GroceryRunLoad.LOADING, vm.uiState.value.load)
        advanceUntilIdle()

        assertEquals(GroceryRunLoad.READY, vm.uiState.value.load)
        assertEquals(listOf("Super U", "Biocoop", "Marché"), vm.tour())
        assertEquals(listOf(2, 1, 1), vm.uiState.value.tour.map { it.count })
        assertTrue(vm.uiState.value.skipped.isEmpty())
        assertNull(vm.uiState.value.current)
        assertTrue(vm.uiState.value.canStart)
    }

    @Test
    fun `a list with nothing left to take is empty`() = runTest {
        coEvery { lists.getGroceryList() } returns Result.success(GroceryList(id = "list-1", items = emptyList()))
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(GroceryRunLoad.EMPTY, vm.uiState.value.load)
        assertFalse(vm.uiState.value.canStart)
    }

    @Test
    fun `a store left out leaves the tour and comes back in its habitual place`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setVisited("s-b", false)
        assertEquals(listOf("Super U", "Marché"), vm.tour())
        assertEquals(listOf("Biocoop"), vm.uiState.value.skipped.map { it.name })
        assertEquals(listOf("s-u", "s-m"), written.last().tour)
        assertEquals(listOf("s-b"), written.last().skipped)

        vm.setVisited("s-b", true)
        assertEquals(listOf("Super U", "Biocoop", "Marché"), vm.tour())
        assertTrue(vm.uiState.value.skipped.isEmpty())
    }

    @Test
    fun `dragging a store reorders the session only and never writes the habitual order`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.move(1, 0)

        assertEquals(listOf("Biocoop", "Super U", "Marché"), vm.tour())
        assertEquals(listOf("s-b", "s-u", "s-m"), written.last().tour)
        coVerify(exactly = 0) { lists.checkItem(any(), any()) }
        verify(exactly = 0) { status.delete(any()) }
        assertEquals(listOf(1, 2, 3), vm.uiState.value.stores.map { listOf(superU, biocoop, market).first { s -> s.id == it.id }.visitOrder })
    }

    @Test
    fun `a move outside the tour is ignored`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val writes = written.size

        vm.move(0, 3)
        vm.move(2, 2)

        assertEquals(listOf("Super U", "Biocoop", "Marché"), vm.tour())
        assertEquals(writes, written.size)
    }

    @Test
    fun `C'est parti opens the first store of the tour and writes it`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.move(2, 0)

        vm.start()

        assertEquals("Marché", vm.uiState.value.current?.name)
        assertEquals("s-m", written.last().current)
    }

    @Test
    fun `an empty tour cannot start`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        listOf("s-u", "s-b", "s-m").forEach { vm.setVisited(it, false) }

        vm.start()

        assertFalse(vm.uiState.value.canStart)
        assertNull(vm.uiState.value.current)
    }

    @Test
    fun `the tour in progress is resumed from the activity status`() = runTest {
        val resumed = GroceryRunState(tour = listOf("s-m", "s-u"), current = "s-u", skipped = listOf("s-b"))
        coEvery { status.get("courses") } returns Result.success(saved(resumed.toJson()))
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("Marché", "Super U"), vm.tour())
        assertEquals("Super U", vm.uiState.value.current?.name)
        assertEquals(listOf("Biocoop"), vm.uiState.value.skipped.map { it.name })
        assertTrue(written.isEmpty())
    }

    @Test
    fun `a store that appeared since the saved tour joins it in its habitual place`() = runTest {
        val resumed = GroceryRunState(tour = listOf("s-m", "s-u"))
        coEvery { status.get("courses") } returns Result.success(saved(resumed.toJson()))
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("Biocoop", "Marché", "Super U"), vm.tour())
        assertEquals(listOf("s-b", "s-m", "s-u"), written.last().tour)
    }

    @Test
    fun `leaving is direct while nothing is checked`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.requestQuit())
        assertFalse(vm.uiState.value.quitAsked)
    }

    @Test
    fun `leaving asks once an article has been checked, and Rester dismisses the question`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        coEvery { lists.getGroceryList() } returns Result.success(
            list().copy(items = list().items.map { if (it.id == "1") it.copy(checked = true) else it }),
        )
        stream.emit(mockk(relaxed = true))
        advanceUntilIdle()

        assertFalse(vm.requestQuit())
        assertTrue(vm.uiState.value.quitAsked)
        vm.dismissQuit()
        assertFalse(vm.uiState.value.quitAsked)
    }

    @Test
    fun `an unreachable API shows the error and Réessayer reads again`() = runTest {
        coEvery { lists.getGroceryList() } returns Result.failure(java.io.IOException("offline"))
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(GroceryRunLoad.ERROR, vm.uiState.value.load)

        coEvery { lists.getGroceryList() } returns Result.success(list())
        vm.retry()
        assertEquals(GroceryRunLoad.LOADING, vm.uiState.value.load)
        advanceUntilIdle()

        assertEquals(GroceryRunLoad.READY, vm.uiState.value.load)
    }
}
