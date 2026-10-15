package com.maggie.app.ui.screens.cookbook.meals

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealSlot
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.repository.MealRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sheet of a planned meal (MAG-378): one test per state transition, against a counting
 * [MockEngine] — what matters is what goes on the wire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MealDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<HttpRequestData>())
    private var gate: CompletableDeferred<Unit>? = null
    private var status = HttpStatusCode.OK

    private val pasta = Recipe(id = "r-pasta", name = "Pâtes")
    private val salad = Recipe(id = "r-salad", name = "Salade verte")
    private val meal = Meal(
        id = "meal-1",
        summary = "Pâtes",
        date = "2026-10-12",
        slot = MealSlot.LUNCH,
        recipes = listOf("/api/recipes/r-pasta"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): MealDetailViewModel {
        val engine = MockEngine { request ->
            requests += request
            gate?.await()
            when {
                status.value >= 400 -> respondError(status)
                request.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                else -> respond(
                    content = ByteReadChannel(
                        """{"id":"meal-1","summary":"Soirée pizza","date":"2026-10-12","slot":"lunch","recipes":["/api/recipes/r-pasta"],"customTitle":"Soirée pizza"}""",
                    ),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        return MealDetailViewModel(MealRepository(MaggieApiService(client)))
    }

    private suspend fun TestScope.settle(until: () -> Boolean) {
        repeat(150) {
            advanceUntilIdle()
            if (until()) return
            withContext(Dispatchers.Default) { delay(20) }
        }
    }

    private fun HttpRequestData.json(): String = (body as TextContent).text

    @Test
    fun `opening starts from the meal as the week shows it`() {
        val viewModel = newViewModel()

        viewModel.open(meal.copy(customTitle = "Mon dimanche"))

        val state = viewModel.uiState.value
        assertEquals(listOf("r-pasta"), state.recipeIds)
        assertEquals("Mon dimanche", state.title)
        assertFalse(state.hasChanges)
    }

    @Test
    fun `a title typed is a change, and typing it back is none`() {
        val viewModel = newViewModel()
        viewModel.open(meal)

        viewModel.setTitle("Dimanche")
        assertTrue(viewModel.uiState.value.hasChanges)

        viewModel.setTitle("")
        assertFalse(viewModel.uiState.value.hasChanges)
    }

    @Test
    fun `a recipe already on the menu is not added twice`() {
        val viewModel = newViewModel()
        viewModel.open(meal)

        viewModel.add(pasta)
        viewModel.add(salad)
        viewModel.add(salad)

        assertEquals(listOf("r-pasta", "r-salad"), viewModel.uiState.value.recipeIds)
    }

    @Test
    fun `removing a recipe takes it off the menu`() {
        val viewModel = newViewModel()
        viewModel.open(meal)

        viewModel.remove("r-pasta")

        assertEquals(emptyList<String>(), viewModel.uiState.value.recipeIds)
        assertTrue(viewModel.uiState.value.hasChanges)
    }

    @Test
    fun `saving patches the menu and the title and hands back the meal`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        viewModel.open(meal)
        viewModel.add(salad)
        viewModel.setTitle("  Soirée pizza ")
        var saved: Meal? = null

        viewModel.save { saved = it }
        settle { saved != null }

        val patch = requests.single()
        assertEquals(HttpMethod.Patch, patch.method)
        assertEquals("/api/meals/meal-1", patch.url.encodedPath)
        val body = Json.parseToJsonElement(patch.json()).toString()
        assertEquals("""{"recipes":["/api/recipes/r-pasta","/api/recipes/r-salad"],"customTitle":"Soirée pizza"}""", body)
        assertEquals("Soirée pizza", saved?.customTitle)
    }

    @Test
    fun `clearing the title sends an empty one so the API goes back to the recipe names`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        viewModel.open(meal.copy(customTitle = "Mon dimanche"))
        viewModel.setTitle("")
        var saved = false

        viewModel.save { saved = true }
        settle { saved }

        assertTrue(Json.parseToJsonElement(requests.single().json()).toString().contains(""""customTitle":"""""))
    }

    @Test
    fun `two quick taps on Enregistrer send a single PATCH`() = runTest(testDispatcher) {
        gate = CompletableDeferred()
        val viewModel = newViewModel()
        viewModel.open(meal)
        viewModel.setTitle("Dimanche")
        var saved = 0

        viewModel.save { saved++ }
        viewModel.save { saved++ }
        settle { requests.size >= 1 }
        gate!!.complete(Unit)
        settle { saved > 0 }
        withContext(Dispatchers.Default) { delay(150) }
        advanceUntilIdle()

        assertEquals(1, requests.size)
        assertEquals(1, saved)
    }

    @Test
    fun `a refused save keeps the sheet open with the reason`() = runTest(testDispatcher) {
        status = HttpStatusCode.UnprocessableEntity
        val viewModel = newViewModel()
        viewModel.open(meal)
        viewModel.setTitle("Dimanche")
        var saved = false

        viewModel.save { saved = true }
        settle { viewModel.uiState.value.error != null }

        val state = viewModel.uiState.value
        assertFalse(saved)
        assertFalse(state.isSaving)
        assertNotNull(state.error)
        assertEquals("Dimanche", state.title)
    }

    @Test
    fun `deleting calls the API once and tells the sheet`() = runTest(testDispatcher) {
        gate = CompletableDeferred()
        val viewModel = newViewModel()
        viewModel.open(meal)
        var deleted = 0

        viewModel.delete { deleted++ }
        viewModel.delete { deleted++ }
        settle { requests.size >= 1 }
        gate!!.complete(Unit)
        settle { deleted > 0 }
        withContext(Dispatchers.Default) { delay(150) }
        advanceUntilIdle()

        assertEquals(1, requests.size)
        assertEquals(HttpMethod.Delete, requests.single().method)
        assertEquals(1, deleted)
    }

    @Test
    fun `a refused deletion keeps the meal and says why`() = runTest(testDispatcher) {
        status = HttpStatusCode.Forbidden
        val viewModel = newViewModel()
        viewModel.open(meal)
        var deleted = false

        viewModel.delete { deleted = true }
        settle { viewModel.uiState.value.error != null }

        assertFalse(deleted)
        assertFalse(viewModel.uiState.value.isDeleting)
        assertNotNull(viewModel.uiState.value.error)
    }
}
