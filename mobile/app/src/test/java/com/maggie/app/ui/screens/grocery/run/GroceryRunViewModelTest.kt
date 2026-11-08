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
import com.maggie.app.data.model.AisleSummary
import com.maggie.app.data.model.GroceryRecipeNeed
import com.maggie.app.data.repository.GroceryTickQueue
import kotlinx.coroutines.CoroutineScope
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
    private lateinit var queue: GroceryTickQueue
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
        queue = GroceryTickQueue(lists, CoroutineScope(dispatcher))
        coEvery { auth.getUserId() } returns "user-1"
        every { mercure.subscribe(any()) } returns stream
        every { mercure.reconnections(any()) } returns emptyFlow()
        coEvery { lists.getGroceryList() } returns Result.success(list())
        coEvery { lists.getMealNeeds(any(), any()) } returns Result.success(emptyList())
        coEvery { lists.checkItem(any(), any()) } returns Result.success(Unit)
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

    private fun viewModel() = GroceryRunViewModel(lists, status, mercure, auth, queue, today = { LocalDate.of(2026, 11, 8) })

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

    private val frais = AisleSummary("a-1", "Frais", 1)
    private val epicerie = AisleSummary("a-2", "Épicerie", 2)

    private fun runList() = GroceryList(
        id = "list-1",
        items = listOf(
            GroceryItem(id = "i-1", label = "Pain", store = superU, position = 1),
            GroceryItem(id = "i-2", label = "Pâtes", store = superU, aisle = epicerie, position = 2),
            GroceryItem(id = "i-3", label = "Lait", store = superU, aisle = frais, position = 3),
            GroceryItem(id = "i-4", label = "Beurre", store = superU, aisle = frais, position = 4, checked = true),
        ),
    )

    private suspend fun kotlinx.coroutines.test.TestScope.started(): GroceryRunViewModel {
        coEvery { lists.getGroceryList() } returns Result.success(runList())
        coEvery { status.get("courses") } returns Result.success(saved(GroceryRunState(tour = listOf("s-u"), current = "s-u").toJson()))
        val vm = viewModel()
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `the store being done lists its articles by aisle in the store's order, Sans rayon last, ticked ones sinking`() = runTest {
        val vm = started()

        val aisles = vm.uiState.value.currentAisles
        assertEquals(listOf("Frais", "Épicerie", null), aisles.map { it.name })
        assertEquals(listOf("Lait", "Beurre"), aisles[0].lines.map { it.label })
        assertEquals(listOf(false, true), aisles[0].lines.map { it.checked })
        assertEquals(RunProgress(1, 4), vm.uiState.value.progress)
        assertEquals(1 to 1, vm.uiState.value.tourPosition)
    }

    @Test
    fun `ticking an article shows it ticked at once and sends it to the API`() = runTest {
        val vm = started()

        vm.check("i-3")
        assertEquals(RunProgress(2, 4), vm.uiState.value.progress)
        advanceUntilIdle()

        coVerify(exactly = 1) { lists.checkItem("i-3", true) }
        assertTrue(queue.pending.value.isEmpty())
    }

    @Test
    fun `unticking a ticked article sends the opposite`() = runTest {
        val vm = started()

        vm.check("i-4")
        assertEquals(RunProgress(0, 4), vm.uiState.value.progress)
        advanceUntilIdle()

        coVerify(exactly = 1) { lists.checkItem("i-4", false) }
    }

    @Test
    fun `a tick made offline is kept, then sent once when the network is back`() = runTest {
        val vm = started()
        coEvery { lists.checkItem("i-3", true) } returns Result.failure(java.io.IOException("offline"))

        vm.check("i-3")
        advanceUntilIdle()
        assertEquals(mapOf("i-3" to true), vm.uiState.value.pending)
        assertEquals(RunProgress(2, 4), vm.uiState.value.progress)
        assertFalse(vm.requestQuit())

        coEvery { lists.checkItem("i-3", true) } returns Result.success(Unit)
        queue.flush()
        queue.flush()
        advanceUntilIdle()

        // One failed try, one success: the second flush finds nothing left to send.
        coVerify(exactly = 2) { lists.checkItem("i-3", true) }
        assertTrue(vm.uiState.value.pending.isEmpty())
    }

    @Test
    fun `a tick on an article gone from the list is dropped`() = runTest {
        val vm = started()
        coEvery { lists.checkItem("i-3", true) } returns Result.failure(java.io.IOException("offline"))
        vm.check("i-3")
        advanceUntilIdle()

        coEvery { lists.getGroceryList() } returns Result.success(GroceryList("list-1", runList().items.filter { it.id != "i-3" }))
        stream.emit(MercureEvent(id = "1", data = "{}"))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.pending.isEmpty())
    }

    @Test
    fun `an article a meal uses carries the soonest meal`() = runTest {
        coEvery { lists.getMealNeeds("2026-11-08", "2026-11-14") } returns Result.success(
            listOf(
                GroceryRecipeNeed("p-1", "Lait", "2026-11-08", "dinner", "m-1", "Bœuf bourguignon"),
                GroceryRecipeNeed("p-1", "Lait", "2026-11-09", "lunch", "m-2", "Gratin"),
            ),
        )
        coEvery { lists.getGroceryList() } returns Result.success(
            GroceryList("list-1", listOf(GroceryItem(id = "i-3", label = "Lait", store = superU, product = com.maggie.app.data.model.Product(id = "p-1", name = "Lait", category = com.maggie.app.data.model.ProductCategory.DAIRY)))),
        )
        coEvery { status.get("courses") } returns Result.success(saved(GroceryRunState(tour = listOf("s-u"), current = "s-u").toJson()))
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("ce soir · bœuf bourguignon", vm.uiState.value.currentAisles.single().lines.single().forMeal)
    }

    @Test
    fun `a store whose last article is ticked stays while it is the one being done`() = runTest {
        coEvery { lists.getGroceryList() } returns Result.success(
            GroceryList("list-1", listOf(GroceryItem(id = "i-1", label = "Pain", store = superU, checked = true), GroceryItem(id = "i-2", label = "Lait", store = biocoop))),
        )
        coEvery { status.get("courses") } returns Result.success(saved(GroceryRunState(tour = listOf("s-u", "s-b"), current = "s-u").toJson()))
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("Super U", vm.uiState.value.current?.name)
        assertEquals(RunProgress(1, 1), vm.uiState.value.progress)
    }
}
