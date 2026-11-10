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
    fun `a tick the API has received stays shown while no refresh has come`() = runTest {
        val vm = started()

        vm.check("i-3")
        advanceUntilIdle()

        assertTrue(queue.pending.value.isEmpty())
        assertEquals(RunProgress(2, 4), vm.uiState.value.progress)
        assertTrue(vm.uiState.value.recapTaken.any { it.id == "i-3" })
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

    // « Manquant » (MAG-764): Lait's product falls back to Biocoop, Pain's has no fallback.
    private fun missingList(vararg extra: GroceryItem) = GroceryList(
        "list-1",
        listOf(
            GroceryItem(id = "i-1", label = "Pain", store = superU, position = 1),
            GroceryItem(
                id = "i-3", label = "Lait", store = superU, position = 3,
                product = com.maggie.app.data.model.Product(id = "p-1", name = "Lait", category = com.maggie.app.data.model.ProductCategory.DAIRY, fallbackStore = "/api/stores/s-b"),
            ),
            GroceryItem(id = "i-9", label = "Œufs", store = biocoop, position = 1),
        ) + extra,
    )

    private suspend fun kotlinx.coroutines.test.TestScope.inSuperU(list: GroceryList = missingList(), tour: List<String> = listOf("s-u", "s-b"), skipped: List<String> = emptyList()): GroceryRunViewModel {
        coEvery { lists.getGroceryList() } returns Result.success(list)
        coEvery { lists.markMissing(any(), any()) } returns Result.success(Unit)
        coEvery { status.get("courses") } returns Result.success(saved(GroceryRunState(tour = tour, current = "s-u", skipped = skipped).toJson()))
        val vm = viewModel()
        advanceUntilIdle()
        return vm
    }

    private fun GroceryRunViewModel.line(id: String) = uiState.value.currentAisles.flatMap { it.lines }.firstOrNull { it.id == id }

    private fun GroceryRunViewModel.lineIn(storeId: String, id: String) = uiState.value
        .let { it.copy(run = it.run.copy(current = storeId)) }
        .currentAisles.flatMap { it.lines }.firstOrNull { it.id == id }

    @Test
    fun `a missing article whose fallback comes later in the tour moves there, missing from its store`() = runTest {
        val vm = inSuperU()
        // Offline: the mark stays on the device, so the tour below reads it as it was asked.
        coEvery { lists.markMissing("i-3", true) } returns Result.failure(java.io.IOException("offline"))

        vm.markMissing("i-3")
        assertNull(vm.line("i-3"))
        assertEquals(RunProgress(0, 1), vm.uiState.value.progress)
        assertEquals(RunMissing.MovedFrom("Super U"), vm.lineIn("s-b", "i-3")?.missing)
        advanceUntilIdle()
        coVerify(exactly = 1) { lists.markMissing("i-3", true) }

        // Biocoop dragged before Super U: the fallback no longer comes later, so the article waits in Super U.
        vm.move(1, 0)
        assertNull(vm.lineIn("s-b", "i-3"))
        assertEquals(RunMissing.NoFallback, vm.line("i-3")?.missing)
    }

    @Test
    fun `the API's moved article shows in the fallback store with manquant chez`() = runTest {
        val moved = missingList().items.map {
            if (it.id == "i-3") it.copy(store = biocoop, missingFromStore = superU, missingAt = "2026-11-08T10:00:00+00:00") else it
        }
        coEvery { lists.getGroceryList() } returns Result.success(GroceryList("list-1", moved))
        coEvery { status.get("courses") } returns Result.success(saved(GroceryRunState(tour = listOf("s-u", "s-b"), current = "s-b").toJson()))
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(RunMissing.MovedFrom("Super U"), vm.line("i-3")?.missing)
        assertEquals(RunProgress(0, 2), vm.uiState.value.progress)
        // Its own store still counts it for the tour: it is where it was missing.
        assertEquals(listOf("s-u", "s-b"), vm.uiState.value.tour.map { it.id })
    }

    @Test
    fun `a missing article without a fallback stays in its store, marked pas de repli and done`() = runTest {
        val vm = inSuperU()

        vm.markMissing("i-1")

        assertEquals(RunMissing.NoFallback, vm.line("i-1")?.missing)
        assertEquals(RunProgress(1, 2), vm.uiState.value.progress)
    }

    // Recette refusée (MAG-764): the API received the mark, the queue let it go, and the line went back to « Manquant ».
    @Test
    fun `a missing mark the API has received stays shown while no read says it yet`() = runTest {
        val vm = inSuperU()

        vm.markMissing("i-1")
        vm.markMissing("i-3")
        advanceUntilIdle()

        assertTrue(queue.pendingMissing.value.isEmpty())
        assertEquals(RunMissing.NoFallback, vm.line("i-1")?.missing)
        assertNull(vm.line("i-3"))
        assertEquals(RunMissing.MovedFrom("Super U"), vm.lineIn("s-b", "i-3")?.missing)
        assertEquals(RunProgress(1, 1), vm.uiState.value.progress)

        // A read the API answered before it had the mark does not take it back.
        stream.emit(MercureEvent(id = "1", data = "{}"))
        advanceUntilIdle()
        assertEquals(RunMissing.NoFallback, vm.line("i-1")?.missing)
        assertEquals(RunMissing.MovedFrom("Super U"), vm.lineIn("s-b", "i-3")?.missing)
    }

    @Test
    fun `a missing mark is read from the list once the API has it`() = runTest {
        val vm = inSuperU()
        vm.markMissing("i-1")
        advanceUntilIdle()

        val marked = missingList().items.map { if (it.id == "i-1") it.copy(missingFromStore = superU, missingAt = "2026-11-08T10:00:00+00:00") else it }
        coEvery { lists.getGroceryList() } returns Result.success(GroceryList("list-1", marked))
        stream.emit(MercureEvent(id = "1", data = "{}"))
        advanceUntilIdle()
        assertEquals(RunMissing.NoFallback, vm.line("i-1")?.missing)

        // Cancelled elsewhere: the next read says so, and the line follows it.
        coEvery { lists.getGroceryList() } returns Result.success(missingList())
        stream.emit(MercureEvent(id = "2", data = "{}"))
        advanceUntilIdle()
        assertNull(vm.line("i-1")?.missing)
    }

    @Test
    fun `a received Annuler stays shown while no read says it yet`() = runTest {
        val moved = missingList().items.map {
            if (it.id == "i-3") it.copy(store = biocoop, missingFromStore = superU, missingAt = "2026-11-08T10:00:00+00:00") else it
        }
        val vm = inSuperU(GroceryList("list-1", moved))

        vm.markMissing("i-3")
        advanceUntilIdle()

        assertNull(vm.lineIn("s-b", "i-3"))
        assertNull(checkNotNull(vm.line("i-3")).missing)
    }

    @Test
    fun `a fallback store left out of the tour leaves the article in its store, pas de repli`() = runTest {
        val vm = inSuperU(tour = listOf("s-u"), skipped = listOf("s-b"))

        vm.markMissing("i-3")

        assertEquals(RunMissing.NoFallback, vm.line("i-3")?.missing)
    }

    @Test
    fun `Annuler gives the article back to its store and sends it`() = runTest {
        val moved = missingList().items.map {
            if (it.id == "i-3") it.copy(store = biocoop, missingFromStore = superU, missingAt = "2026-11-08T10:00:00+00:00") else it
        }
        val vm = inSuperU(GroceryList("list-1", moved))
        assertEquals(RunMissing.MovedFrom("Super U"), vm.lineIn("s-b", "i-3")?.missing)

        vm.markMissing("i-3")

        assertNull(vm.lineIn("s-b", "i-3"))
        assertNull(checkNotNull(vm.line("i-3")).missing)
        advanceUntilIdle()
        coVerify(exactly = 1) { lists.markMissing("i-3", false) }
    }

    @Test
    fun `a ticked article cannot be marked missing`() = runTest {
        val vm = inSuperU()
        vm.check("i-1")

        vm.markMissing("i-1")
        advanceUntilIdle()

        assertNull(vm.line("i-1")?.missing)
        coVerify(exactly = 0) { lists.markMissing(any(), any()) }
    }

    @Test
    fun `a missing mark made offline is kept, then sent when the network is back`() = runTest {
        val vm = inSuperU()
        coEvery { lists.markMissing("i-1", true) } returns Result.failure(java.io.IOException("offline"))

        vm.markMissing("i-1")
        advanceUntilIdle()
        assertEquals(mapOf("i-1" to true), vm.uiState.value.pendingMissing)
        assertEquals(RunMissing.NoFallback, vm.line("i-1")?.missing)
        assertFalse(vm.requestQuit())

        coEvery { lists.markMissing("i-1", true) } returns Result.success(Unit)
        queue.flush()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.pendingMissing.isEmpty())
    }

    // « Terminer le magasin » (MAG-765): in Super U, Beurre is taken, Pain has no fallback, Lait falls back to Biocoop.
    private val butter = GroceryItem(id = "i-4", label = "Beurre", store = superU, position = 4, checked = true)

    /** The list as the API reads it once Pain and Lait are marked missing in Super U. */
    private fun markedList(vararg extra: GroceryItem) = GroceryList(
        "list-1",
        missingList().items.map {
            when (it.id) {
                "i-1" -> it.copy(missingFromStore = superU, missingAt = "2026-11-08T10:00:00+00:00")
                "i-3" -> it.copy(store = biocoop, missingFromStore = superU, missingAt = "2026-11-08T10:00:00+00:00")
                else -> it
            }
        } + extra,
    )

    private suspend fun kotlinx.coroutines.test.TestScope.finishing(
        list: GroceryList = markedList(butter),
        tour: List<String> = listOf("s-u", "s-b"),
        current: String = "s-u",
        done: List<String> = emptyList(),
        finished: Boolean = false,
    ): GroceryRunViewModel {
        coEvery { lists.getGroceryList() } returns Result.success(list)
        coEvery { lists.markMissing(any(), any()) } returns Result.success(Unit)
        coEvery { lists.endErrand(any(), any()) } returns Result.success(com.maggie.app.data.api.EndErrandResponse(success = true))
        coEvery { lists.deleteItem(any()) } returns Result.success(Unit)
        every { status.delete("courses") } returns Unit
        val state = GroceryRunState(tour = tour, current = current.takeIf { !finished }, done = done, finished = finished)
        coEvery { status.get("courses") } returns Result.success(saved(state.toJson()))
        val vm = viewModel()
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `Terminer le magasin sums up what was taken and where each missing article goes`() = runTest {
        val vm = finishing()

        vm.openRecap()

        assertTrue(vm.uiState.value.recap)
        assertEquals(listOf("Beurre"), vm.uiState.value.recapTaken.map { it.label })
        assertEquals(listOf("Pain" to null, "Lait" to "Biocoop"), vm.uiState.value.recapMissing.map { it.label to it.store })
        vm.closeRecap()
        assertFalse(vm.uiState.value.recap)
    }

    @Test
    fun `Valider ends the store's errand with the amount in cents, then opens the next store`() = runTest {
        val vm = finishing()
        vm.openRecap()

        vm.setPaidAmount("42,80")
        vm.finishStore()
        advanceUntilIdle()

        coVerify(exactly = 1) { lists.endErrand("s-u", 4280) }
        assertFalse(vm.uiState.value.recap)
        assertEquals("Biocoop", vm.uiState.value.current?.name)
        assertEquals(GroceryRunState(tour = listOf("s-u", "s-b"), current = "s-b", done = listOf("s-u")), written.last())
        // Super U keeps its place in the tour: « Magasin 2 sur 2 ».
        assertEquals(2 to 2, vm.uiState.value.tourPosition)
    }

    @Test
    fun `no amount ends the errand without one`() = runTest {
        val vm = finishing()
        vm.openRecap()

        vm.finishStore()
        advanceUntilIdle()

        coVerify(exactly = 1) { lists.endErrand("s-u", null) }
    }

    @Test
    fun `an amount that is not in euros is refused before anything is sent`() = runTest {
        val vm = finishing()
        vm.openRecap()

        vm.setPaidAmount("quarante")
        vm.finishStore()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.amountInvalid)
        assertTrue(vm.uiState.value.recap)
        coVerify(exactly = 0) { lists.endErrand(any(), any()) }
    }

    @Test
    fun `a failed end keeps the summary and the store, to validate again`() = runTest {
        val vm = finishing()
        coEvery { lists.endErrand(any(), any()) } returns Result.failure(java.io.IOException("offline"))
        vm.openRecap()

        vm.finishStore()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.finishFailed)
        assertTrue(vm.uiState.value.recap)
        assertEquals("Super U", vm.uiState.value.current?.name)
    }

    @Test
    fun `a tick still waiting is sent before the errand ends, and one that cannot be sent stops it`() = runTest {
        val vm = finishing(markedList(butter, GroceryItem(id = "i-5", label = "Sel", store = superU, position = 5)))
        coEvery { lists.checkItem("i-5", true) } returns Result.failure(java.io.IOException("offline"))
        vm.check("i-5")
        vm.openRecap()

        vm.finishStore()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.finishFailed)
        coVerify(exactly = 0) { lists.endErrand(any(), any()) }

        coEvery { lists.checkItem("i-5", true) } returns Result.success(Unit)
        vm.finishStore()
        advanceUntilIdle()

        coVerify(exactly = 1) { lists.endErrand("s-u", null) }
    }

    @Test
    fun `after the last store, Manquants sans repli lists the missing articles that found no store, not the moved ones`() = runTest {
        val vm = finishing(current = "s-b", done = listOf("s-u"))
        vm.openRecap()

        vm.finishStore()
        advanceUntilIdle()

        coVerify(exactly = 1) { lists.endErrand("s-b", null) }
        assertTrue(vm.uiState.value.run.finished)
        assertNull(vm.uiState.value.run.current)
        assertEquals(listOf("Pain" to "Super U"), vm.uiState.value.leftovers.map { it.label to it.store })
        assertFalse(vm.uiState.value.ended)
        assertTrue(written.last().finished)
    }

    @Test
    fun `Prochaine fois takes the mark off and leaves the article on the list, then the activity ends`() = runTest {
        val vm = finishing(finished = true, done = listOf("s-u", "s-b"))

        vm.keepForNextTime("i-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { lists.markMissing("i-1", false) }
        coVerify(exactly = 0) { lists.deleteItem(any()) }
        assertTrue(vm.uiState.value.leftovers.isEmpty())
        assertTrue(vm.uiState.value.ended)
        verify(exactly = 1) { status.delete("courses") }
        assertTrue(vm.requestQuit())
    }

    @Test
    fun `Supprimer takes the article off the list, then the activity ends`() = runTest {
        val vm = finishing(finished = true, done = listOf("s-u", "s-b"))

        vm.remove("i-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { lists.deleteItem("i-1") }
        coVerify(exactly = 0) { lists.markMissing(any(), any()) }
        assertTrue(vm.uiState.value.ended)
        verify(exactly = 1) { status.delete("courses") }
    }

    @Test
    fun `a Supprimer the API refuses keeps the article and asks again`() = runTest {
        val vm = finishing(finished = true, done = listOf("s-u", "s-b"))
        coEvery { lists.deleteItem("i-1") } returns Result.failure(java.io.IOException("offline"))

        vm.remove("i-1")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.removeFailed)
        assertEquals(listOf("Pain"), vm.uiState.value.leftovers.map { it.label })
        assertFalse(vm.uiState.value.ended)
    }

    @Test
    fun `a last store with no missing article left goes straight to Fin`() = runTest {
        val vm = finishing(GroceryList("list-1", listOf(butter)), tour = listOf("s-u"))
        vm.openRecap()
        coEvery { lists.getGroceryList() } returns Result.success(GroceryList("list-1", emptyList()))

        vm.finishStore()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.ended)
        verify(exactly = 1) { status.delete("courses") }
    }

    @Test
    fun `euros are read with a comma or a dot, up to two decimals`() {
        assertEquals(4280, euroCents("42,80"))
        assertEquals(4280, euroCents("42.8"))
        assertEquals(4200, euroCents(" 42 € "))
        assertEquals(5, euroCents("0,05"))
        assertNull(euroCents("42,805"))
        assertNull(euroCents("-3"))
        assertNull(euroCents("quarante"))
    }
}
