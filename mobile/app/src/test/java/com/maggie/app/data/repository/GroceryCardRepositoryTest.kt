package com.maggie.app.data.repository

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.AisleSummary
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.GroceryRecipeNeed
import com.maggie.app.data.model.Need
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Store
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The reads of the Courses card (MAG-590): the grocery list and what the meals need, never the home card endpoint; a part
 * answered 404 is empty; the topics of the list, the shops, the products and the meals wake it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroceryCardRepositoryTest {
    private val api = mockk<MaggieApiService>()
    private val mercure = mockk<MercureService>()
    private val auth = mockk<AuthRepository> { coEvery { getUserId() } returns "01HUSER" }
    private val repository = GroceryCardRepository(api, mercure, auth)

    private val today = LocalDate.of(2026, 10, 23)

    private val market = Store(id = "s1", name = "Marché", visitOrder = 1)
    private val supermarket = Store(id = "s2", name = "Supermarché", visitOrder = 2)

    private fun item(
        id: String,
        label: String = "Article $id",
        store: Store? = supermarket,
        aisle: AisleSummary? = null,
        position: Int = 0,
        checked: Boolean = false,
        buyAfter: String? = null,
        product: Product? = null,
        needed: List<Need>? = null,
        quantity: Float? = 1f,
        unit: CookbookUnit? = CookbookUnit.PIECE,
    ) = GroceryItem(
        id = id,
        label = label,
        store = store,
        aisle = aisle,
        position = position,
        checked = checked,
        buyAfter = buyAfter,
        product = product,
        needed = needed,
        quantity = quantity,
        unit = unit,
    )

    private fun need(productId: String, name: String = "Produit $productId", date: String = "2026-10-23", slot: String = "dinner") =
        GroceryRecipeNeed(productId = productId, name = name, date = date, slot = slot)

    private fun answers(items: List<GroceryItem> = emptyList(), needs: List<GroceryRecipeNeed> = emptyList()) {
        coEvery { api.getGroceryLists() } returns listOf(GroceryList(id = "l1"))
        coEvery { api.getGroceryList("l1") } returns GroceryList(id = "l1", items = items)
        coEvery { api.getGroceryRecipeNeeds(any(), any()) } returns needs
    }

    @Test
    fun `it reads the list, then its lines, and what the meals of today and tomorrow need`() = runTest {
        answers()

        repository.read(today).getOrThrow()

        coVerify(exactly = 1) { api.getGroceryLists() }
        coVerify(exactly = 1) { api.getGroceryList("l1") }
        coVerify(exactly = 1) { api.getGroceryRecipeNeeds("2026-10-23", "2026-10-24") }
    }

    @Test
    fun `tomorrow is the day after today across the end of the month`() = runTest {
        answers()

        repository.read(LocalDate.of(2026, 10, 31)).getOrThrow()

        coVerify(exactly = 1) { api.getGroceryRecipeNeeds("2026-10-31", "2026-11-01") }
    }

    @Test
    fun `the lines are grouped by shop, shops in the order of the way through, no shop last`() = runTest {
        answers(items = listOf(item("a", store = null), item("b", store = supermarket), item("c", store = market), item("d", store = supermarket)))

        val state = repository.read(today).getOrThrow()

        assertEquals(4, state.count)
        assertEquals(listOf("Marché", "Supermarché", null), state.stores.map { it.name })
        assertEquals(listOf("s1", "s2", null), state.stores.map { it.id })
        assertEquals(listOf(1, 2, 1), state.stores.map { it.count })
        assertEquals(listOf("b", "d"), state.stores[1].items.map { it.id })
    }

    @Test
    fun `inside a shop the lines go aisle by aisle, then by position, with no aisle last`() = runTest {
        val dairy = AisleSummary(id = "a1", name = "Crèmerie", position = 2)
        val bakery = AisleSummary(id = "a2", name = "Boulangerie", position = 1)
        val dairyToo = AisleSummary(id = "a3", name = "Beurre", position = 2)
        answers(
            items = listOf(
                item("none", aisle = null, position = 0),
                item("dairy-2", aisle = dairy, position = 2),
                item("bakery", aisle = bakery, position = 9),
                item("dairy-1", aisle = dairy, position = 1),
                item("butter", aisle = dairyToo, position = 5),
            ),
        )

        val ids = repository.read(today).getOrThrow().stores.single().items.map { it.id }

        assertEquals(listOf("bakery", "butter", "dairy-1", "dairy-2", "none"), ids)
    }

    @Test
    fun `a shop shows five lines and tells how many are left`() = runTest {
        answers(items = (1..7).map { item("i$it", position = it) })

        val store = repository.read(today).getOrThrow().stores.single()

        assertEquals(7, store.count)
        assertEquals(listOf("i1", "i2", "i3", "i4", "i5"), store.items.map { it.id })
        assertEquals(2, store.more)
    }

    @Test
    fun `a shop with exactly five lines has nothing left`() = runTest {
        answers(items = (1..5).map { item("i$it", position = it) })

        assertEquals(0, repository.read(today).getOrThrow().stores.single().more)
    }

    @Test
    fun `a ticked line and a line held back until a later day are not open`() = runTest {
        answers(
            items = listOf(
                item("open"),
                item("done", checked = true),
                item("later", buyAfter = "2026-10-24"),
                item("today", buyAfter = "2026-10-23"),
                item("past", buyAfter = "2026-10-01"),
            ),
        )

        val state = repository.read(today).getOrThrow()

        assertEquals(3, state.count)
        assertEquals(listOf("open", "today", "past"), state.stores.single().items.map { it.id })
    }

    @Test
    fun `a line carries its label, quantity and unit as the API names them`() = runTest {
        answers(items = listOf(item("a", label = "Lait", quantity = 2f, unit = CookbookUnit.L), item("b", label = "Sel", quantity = null, unit = null)))

        val lines = repository.read(today).getOrThrow().stores.single().items

        assertEquals(listOf("Lait", "Sel"), lines.map { it.name })
        assertEquals(2f, lines[0].quantity)
        assertEquals("l", lines[0].unit)
        assertNull(lines[1].quantity)
        assertNull(lines[1].unit)
    }

    @Test
    fun `a packaged line has no need, any other keeps what the meals need`() = runTest {
        val rice = Product(id = "p1", name = "Riz", category = ProductCategory.GRAIN, packagingUnit = CookbookUnit.PACK, packagingSize = 500f, packagingSizeUnit = CookbookUnit.G)
        val flour = Product(id = "p2", name = "Farine", category = ProductCategory.GRAIN)
        val needed = listOf(Need(800.0, "g"))
        answers(
            items = listOf(
                item("rice", product = rice, needed = needed, position = 1),
                item("flour", product = flour, needed = needed, position = 2),
                item("free", product = null, needed = null, position = 3),
            ),
        )

        val lines = repository.read(today).getOrThrow().stores.single().items

        assertNull(lines[0].needed)
        assertEquals(needed, lines[1].needed)
        assertNull(lines[2].needed)
    }

    @Test
    fun `what the meals lack is told once per product, the first meal winning, today or tomorrow`() = runTest {
        answers(
            needs = listOf(
                need("p1", name = "Parmesan", date = "2026-10-23", slot = "lunch"),
                need("p2", name = "Crème", date = "2026-10-24", slot = "dinner"),
                need("p1", name = "Parmesan", date = "2026-10-24", slot = "dinner"),
            ),
        )

        val recipes = repository.read(today).getOrThrow().forRecipes

        assertEquals(listOf("Parmesan", "Crème"), recipes.map { it.name })
        assertEquals(listOf("today", "tomorrow"), recipes.map { it.day })
        assertEquals(listOf("lunch", "dinner"), recipes.map { it.slot })
    }

    @Test
    fun `an empty list and no need make a state the Accueil does not draw`() = runTest {
        answers()

        val state = repository.read(today).getOrThrow()

        assertEquals(0, state.count)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `an account with no list has an empty card, without asking for a list`() = runTest {
        answers()
        coEvery { api.getGroceryLists() } returns emptyList()

        val state = repository.read(today).getOrThrow()

        assertTrue(state.stores.isEmpty())
        coVerify(exactly = 0) { api.getGroceryList(any()) }
    }

    @Test
    fun `the card never reads the home card endpoint`() = runTest {
        answers()

        repository.read(today).getOrThrow()

        coVerify(exactly = 0) { api.getHomeCard(any(), any()) }
    }

    @Test
    fun `the list and the needs start together`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { api.getGroceryLists() } coAnswers { gate.await(); emptyList() }
        coEvery { api.getGroceryRecipeNeeds(any(), any()) } coAnswers { gate.await(); emptyList() }

        val read = launch { repository.read(today) }
        runCurrent()

        coVerify(exactly = 1) { api.getGroceryLists() }
        coVerify(exactly = 1) { api.getGroceryRecipeNeeds(any(), any()) }
        gate.complete(Unit)
        read.join()
    }

    @Test
    fun `a part answered 404 is empty, the other one is kept`() = runTest {
        answers(items = listOf(item("a")))
        coEvery { api.getGroceryRecipeNeeds(any(), any()) } throws ApiRefusalException(404, "Not found")
        val withoutNeeds = repository.read(today).getOrThrow()
        assertEquals(1, withoutNeeds.count)
        assertTrue(withoutNeeds.forRecipes.isEmpty())

        answers(needs = listOf(need("p1")))
        coEvery { api.getGroceryLists() } throws ApiRefusalException(404, "Not found")
        val withoutList = repository.read(today).getOrThrow()
        assertEquals(0, withoutList.count)
        assertEquals(1, withoutList.forRecipes.size)

        answers(items = listOf(item("a")))
        coEvery { api.getGroceryList("l1") } throws ApiRefusalException(404, "Not found")
        assertEquals(0, repository.read(today).getOrThrow().count)
    }

    @Test
    fun `any other failure is a failure`() = runTest {
        answers()
        coEvery { api.getGroceryRecipeNeeds(any(), any()) } throws ApiRefusalException(500, "boom")
        assertTrue(repository.read(today).isFailure)

        answers()
        coEvery { api.getGroceryList("l1") } throws RuntimeException("offline")
        assertTrue(repository.read(today).isFailure)
    }

    private val topics = listOf("grocery_lists", "stores", "products", "aisles", "meals", "recipes", "ingredients")

    @Test
    fun `a change on the list, the shops, the products, the aisles, the meals, the recipes or the ingredients wakes the card, bursts merged`() = runTest {
        topics.forEach { every { mercure.subscribe("/users/01HUSER/api/$it/{id}") } returns flowOf() }
        every { mercure.subscribe("/users/01HUSER/api/grocery_lists/{id}") } returns flowOf(MercureEvent(id = "1"), MercureEvent(id = "2"))

        val changes = repository.changes().toList()

        assertEquals(1, changes.size)
        topics.forEach { verify(exactly = 1) { mercure.subscribe("/users/01HUSER/api/$it/{id}") } }
        verify(exactly = 0) { mercure.subscribe(match { it.contains("/home/") }) }
    }

    @Test
    fun `a meal changed elsewhere wakes the card`() = runTest {
        val meals = MutableSharedFlow<MercureEvent>()
        topics.forEach { every { mercure.subscribe("/users/01HUSER/api/$it/{id}") } returns flowOf() }
        every { mercure.subscribe("/users/01HUSER/api/meals/{id}") } returns meals
        var woken = 0
        val job = launch { repository.changes().collect { woken++ } }
        runCurrent()

        meals.emit(MercureEvent(id = "1"))
        advanceUntilIdle()

        assertEquals(1, woken)
        job.cancel()
    }

    @Test
    fun `without a signed-in user the card subscribes to nothing`() = runTest {
        coEvery { auth.getUserId() } returns null

        assertTrue(repository.changes().toList().isEmpty())
        verify(exactly = 0) { mercure.subscribe(any()) }
    }
}
