package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.api.ReorderEntry
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.AisleSummary
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductAisle
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.GroceryListRepository
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.StoreRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The list sorted aisle by aisle in each store, and an item's aisle chosen from its detail (MAG-493). */
@OptIn(ExperimentalCoroutinesApi::class)
class GroceryAislesTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var groceryListRepository: GroceryListRepository
    private lateinit var productRepository: ProductRepository
    private lateinit var storeRepository: StoreRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository

    private val carrefour = Store(id = "carrefour", name = "Carrefour", visitOrder = 1)
    private val biocoop = Store(id = "biocoop", name = "Biocoop", visitOrder = 2)

    private val fruits = AisleSummary(id = "a-fruits", name = "Fruits et légumes", position = 1)
    private val cremerie = AisleSummary(id = "a-cremerie", name = "Crèmerie", position = 2)
    private val boulangerie = AisleSummary(id = "a-boulangerie", name = "Boulangerie", position = 3)

    private fun product(id: String, name: String) = Product(id = id, name = name, category = ProductCategory.OTHER)

    private val lait = GroceryItem(id = "lait", label = "Lait", product = product("p-lait", "Lait"), store = carrefour, position = 0, aisle = cremerie)
    private val pommes = GroceryItem(id = "pommes", label = "Pommes", product = product("p-pommes", "Pommes"), store = carrefour, position = 1, aisle = fruits)
    private val pain = GroceryItem(id = "pain", label = "Pain", product = product("p-pain", "Pain"), store = carrefour, position = 2, aisle = boulangerie)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        groceryListRepository = mockk()
        productRepository = mockk()
        storeRepository = mockk()
        mercureService = mockk()
        authRepository = mockk()

        coEvery { authRepository.getUserId() } returns "user-1"
        every { authRepository.token } returns flowOf("jwt")
        every { mercureService.subscribe(any()) } returns emptyFlow()
        coEvery { productRepository.getProducts() } returns Result.success(emptyList())
        coEvery { storeRepository.getStores() } returns Result.success(listOf(carrefour, biocoop))
        coEvery { groceryListRepository.reorderItems(any()) } returns Result.success(Unit)
        coEvery { groceryListRepository.getSuggestions() } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun givenList(vararg items: GroceryItem) {
        coEvery { groceryListRepository.getGroceryList() } returns
            Result.success(GroceryList(id = "list-1", items = items.toList()))
    }

    private fun createViewModel() =
        GroceryViewModel(groceryListRepository, productRepository, storeRepository, mercureService, authRepository)

    private fun GroceryViewModel.labelsOf(storeId: String?): List<String> =
        uiState.value.storeGroups.first { it.store?.id == storeId }.items.map { it.label }

    private fun GroceryViewModel.sectionsOf(storeId: String?): List<String?> =
        uiState.value.storeGroups.first { it.store?.id == storeId }.sections.map { it.aisle?.name }

    @Test
    fun `a store's items follow its aisles, whatever order they were added in`() = runTest {
        givenList(lait, pommes, pain)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf("Pommes", "Lait", "Pain"), viewModel.labelsOf("carrefour"))
        assertEquals(listOf("Fruits et légumes", "Crèmerie", "Boulangerie"), viewModel.sectionsOf("carrefour"))
    }

    @Test
    fun `swapping two aisles swaps their items`() = runTest {
        givenList(
            lait.copy(aisle = cremerie.copy(position = 3)),
            pommes,
            pain.copy(aisle = boulangerie.copy(position = 2)),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf("Pommes", "Pain", "Lait"), viewModel.labelsOf("carrefour"))
        assertEquals(listOf("Fruits et légumes", "Boulangerie", "Crèmerie"), viewModel.sectionsOf("carrefour"))
    }

    @Test
    fun `the same product sits in a different aisle in each store`() = runTest {
        val bioFrais = AisleSummary(id = "b-frais", name = "Frais", position = 2)
        val bioEntree = AisleSummary(id = "b-entree", name = "Entrée", position = 1)
        givenList(
            lait,
            pommes,
            lait.copy(id = "lait-bio", store = biocoop, aisle = bioFrais, position = 0),
            pain.copy(id = "pain-bio", store = biocoop, aisle = bioEntree, position = 1),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf("Pommes", "Lait"), viewModel.labelsOf("carrefour"))
        assertEquals(listOf("Pain", "Lait"), viewModel.labelsOf("biocoop"))
        assertEquals(listOf("Entrée", "Frais"), viewModel.sectionsOf("biocoop"))
    }

    @Test
    fun `an item with no aisle comes last, under Sans rayon`() = runTest {
        val sucre = GroceryItem(id = "sucre", label = "Sucre", store = carrefour, position = 0)
        givenList(sucre, lait, pommes)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf("Pommes", "Lait", "Sucre"), viewModel.labelsOf("carrefour"))
        // null is « Sans rayon »
        assertEquals(listOf("Fruits et légumes", "Crèmerie", null), viewModel.sectionsOf("carrefour"))
    }

    @Test
    fun `a store with no aisled item keeps its own order and no aisle header`() = runTest {
        givenList(
            lait.copy(aisle = null, position = 1),
            pommes.copy(aisle = null, position = 0),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf("Pommes", "Lait"), viewModel.labelsOf("carrefour"))
        assertTrue(viewModel.sectionsOf("carrefour").isEmpty())
    }

    @Test
    fun `within an aisle the items keep their own position`() = runTest {
        val yaourt = GroceryItem(id = "yaourt", label = "Yaourt", store = carrefour, position = 0, aisle = cremerie)
        givenList(lait.copy(position = 4), yaourt, pommes)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf("Pommes", "Yaourt", "Lait"), viewModel.labelsOf("carrefour"))
    }

    @Test
    fun `a Mercure payload carries each item's aisle and re-sorts the store`() = runTest {
        givenList(lait, pommes)
        val updates = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns updates
        val viewModel = createViewModel()
        advanceUntilIdle()

        // The owner moved « Crèmerie » before « Fruits et légumes » on the admin.
        updates.emit(
            MercureEvent(
                data = """{"items":[
                    {"id":"lait","label":"Lait","store":{"id":"carrefour","name":"Carrefour","visitOrder":1},"aisle":{"id":"a-cremerie","name":"Crèmerie","position":1},"position":0},
                    {"id":"pommes","label":"Pommes","store":{"id":"carrefour","name":"Carrefour","visitOrder":1},"aisle":{"id":"a-fruits","name":"Fruits et légumes","position":2},"position":1}
                ]}""",
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("Lait", "Pommes"), viewModel.labelsOf("carrefour"))
        assertEquals(listOf("Crèmerie", "Fruits et légumes"), viewModel.sectionsOf("carrefour"))
    }

    @Test
    fun `moving an item within its aisle keeps the aisle order`() = runTest {
        val yaourt = GroceryItem(id = "yaourt", label = "Yaourt", store = carrefour, position = 3, aisle = cremerie)
        givenList(pommes, lait, yaourt, pain)
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(listOf("Pommes", "Lait", "Yaourt", "Pain"), viewModel.labelsOf("carrefour"))

        viewModel.moveItem("carrefour", 2, 1)
        advanceUntilIdle()

        assertEquals(listOf("Pommes", "Yaourt", "Lait", "Pain"), viewModel.labelsOf("carrefour"))
        coVerify {
            groceryListRepository.reorderItems(
                listOf(
                    ReorderEntry("pommes", 0),
                    ReorderEntry("yaourt", 1),
                    ReorderEntry("lait", 2),
                    ReorderEntry("pain", 3),
                ),
            )
        }
    }

    @Test
    fun `loading a store's aisles keeps them for the aisle choice`() = runTest {
        givenList(lait)
        val aisles = listOf(Aisle(id = "a-fruits", store = "/api/stores/carrefour", name = "Fruits et légumes", position = 1))
        coEvery { storeRepository.getAisles("carrefour") } returns Result.success(aisles)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadAisles("carrefour")
        advanceUntilIdle()

        assertEquals(aisles, viewModel.uiState.value.aislesByStore["carrefour"])
    }

    @Test
    fun `choosing an item's aisle replaces only that store's entry of its product`() = runTest {
        givenList(lait)
        coEvery { productRepository.getProduct("p-lait") } returns Result.success(
            product("p-lait", "Lait").copy(
                aisles = listOf(ProductAisle("carrefour", "a-cremerie"), ProductAisle("biocoop", "b-frais")),
            ),
        )
        coEvery { productRepository.updateAisles(any(), any()) } returns Result.success(product("p-lait", "Lait"))
        coEvery { storeRepository.getAisles(any()) } returns Result.success(emptyList())
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setItemAisle(lait, "carrefour", "a-fruits")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            productRepository.updateAisles(
                "p-lait",
                listOf(ProductAisle("biocoop", "b-frais"), ProductAisle("carrefour", "a-fruits")),
            )
        }
        // The list is read again: the item now carries its new aisle.
        coVerify(exactly = 2) { groceryListRepository.getGroceryList() }
    }

    @Test
    fun `a new aisle is sent by its name and the store's aisles are read again`() = runTest {
        givenList(lait)
        coEvery { productRepository.getProduct("p-lait") } returns Result.success(product("p-lait", "Lait"))
        coEvery { productRepository.updateAisles(any(), any()) } returns Result.success(product("p-lait", "Lait"))
        coEvery { storeRepository.getAisles("carrefour") } returns Result.success(emptyList())
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setItemAisle(lait, "carrefour", "Produits laitiers")
        advanceUntilIdle()

        coVerify { productRepository.updateAisles("p-lait", listOf(ProductAisle("carrefour", "Produits laitiers"))) }
        coVerify { storeRepository.getAisles("carrefour") }
    }

    @Test
    fun `Sans rayon removes the store's entry from the product`() = runTest {
        givenList(lait)
        coEvery { productRepository.getProduct("p-lait") } returns Result.success(
            product("p-lait", "Lait").copy(aisles = listOf(ProductAisle("carrefour", "a-cremerie"))),
        )
        coEvery { productRepository.updateAisles(any(), any()) } returns Result.success(product("p-lait", "Lait"))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setItemAisle(lait, "carrefour", null)
        advanceUntilIdle()

        coVerify(exactly = 1) { productRepository.updateAisles("p-lait", emptyList()) }
    }

    @Test
    fun `a refused aisle change says so and leaves the list as it was`() = runTest {
        givenList(lait)
        coEvery { productRepository.getProduct("p-lait") } returns Result.success(product("p-lait", "Lait"))
        coEvery { productRepository.updateAisles(any(), any()) } returns Result.failure(RuntimeException("400"))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setItemAisle(lait, "carrefour", "a-fruits")
        advanceUntilIdle()

        assertEquals("Erreur : le rayon de « Lait » n'a pas été modifié", viewModel.uiState.value.aisleMessage)
        assertEquals(cremerie, viewModel.uiState.value.groceryList!!.items.single().aisle)

        viewModel.dismissAisleMessage()
        assertNull(viewModel.uiState.value.aisleMessage)
    }

    @Test
    fun `an item without a product has no aisle to choose`() = runTest {
        val free = GroceryItem(id = "free", label = "Truc", store = carrefour)
        givenList(free)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setItemAisle(free, "carrefour", "a-fruits")
        advanceUntilIdle()

        coVerify(exactly = 0) { productRepository.getProduct(any()) }
        coVerify(exactly = 0) { productRepository.updateAisles(any(), any()) }
    }
}
