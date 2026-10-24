package com.maggie.app.ui.screens.cookbook.cooking

import androidx.lifecycle.SavedStateHandle
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.local.dao.RecipeDao
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.RecipeRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.mockk.mockk
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Cuisine activity's state (MAG-580): what it reads, how a rotation resumes it, and the three states
 * between (loading, empty, error). Against a counting [MockEngine] — what matters is what goes on the wire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CookingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<HttpRequestData>())
    private var failing = false
    private var mealJson = mealWith(listOf("r-a", "r-b", "r-c"), guests = 4)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun mealWith(recipes: List<String>, guests: Int) =
        """{"id":"meal-1","summary":"Dîner","date":"2026-10-24","slot":"dinner","guests":$guests,""" +
            """"recipes":[${recipes.joinToString(",") { "\"/api/recipes/$it\"" }}]}"""

    /** A recipe for 4 whose flour is 200 g: the API scales it to the `guests` asked, as the real one does. */
    private fun recipeJson(id: String, guests: Int?, steps: Int = 2): String {
        val flour = 200 * (guests ?: 4) / 4
        val stepsJson = (steps downTo 1).joinToString(",") { """{"position":$it,"text":"Étape $it de $id"}""" }
        return """{"id":"$id","name":"Recette $id","servings":4,""" +
            """"ingredients":[{"ingredientName":"Farine","quantity":$flour,"unit":"g"}],""" +
            """"utensils":[{"id":"u1","name":"Fouet"}],"steps":[$stepsJson]}"""
    }

    private fun newViewModel(handle: SavedStateHandle, stepsOf: (String) -> Int = { 2 }): CookingViewModel {
        val engine = MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            val body = when {
                failing -> null
                path == "/api/meals/meal-1" -> mealJson
                path == "/api/meals/meal-empty" -> mealWith(emptyList(), guests = 2)
                path.startsWith("/api/recipes/") -> {
                    val id = path.substringAfterLast('/')
                    recipeJson(id, request.url.parameters["guests"]?.toInt(), stepsOf(id))
                }
                else -> null
            }
            if (body == null) {
                respondError(HttpStatusCode.InternalServerError)
            } else {
                respond(
                    content = ByteReadChannel(body),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val api = MaggieApiService(client)
        return CookingViewModel(handle, MealRepository(api), RecipeRepository(api, mockk<RecipeDao>(relaxed = true)))
    }

    private fun mealHandle() = SavedStateHandle(mapOf("meal" to "meal-1"))

    private fun recipeHandle() = SavedStateHandle(mapOf("recipe" to "r-a"))

    private suspend fun TestScope.settle(until: () -> Boolean) {
        repeat(150) {
            advanceUntilIdle()
            if (until()) return
            withContext(Dispatchers.Default) { delay(20) }
        }
    }

    private suspend fun TestScope.ready(viewModel: CookingViewModel) {
        settle {
            val state = viewModel.uiState.value
            state.load == CookingLoad.READY && !state.refreshing && state.recipe != null
        }
    }

    private fun guestsRequested() = requests.mapNotNull { it.url.parameters["guests"] }

    @Test
    fun `it starts loading`() {
        val viewModel = newViewModel(mealHandle())

        assertEquals(CookingLoad.LOADING, viewModel.uiState.value.load)
        assertNull(viewModel.uiState.value.session)
    }

    @Test
    fun `a meal opens on the preparation of its first recipe, for the meal guests`() = runTest(testDispatcher) {
        val viewModel = newViewModel(mealHandle())
        ready(viewModel)

        val state = viewModel.uiState.value
        val session = state.session!!
        assertEquals(CookingPhase.COOKING, session.phase)
        assertEquals(0, session.step)
        assertEquals(4, session.guests)
        assertEquals(listOf("r-a", "r-b", "r-c"), session.queue.map { it.recipeId })
        assertEquals("Recette r-a", state.recipe?.name)
        assertTrue("every recipe is read for the meal's guests", requests.any { it.url.parameters["guests"] == "4" })
    }

    @Test
    fun `a recipe asks Pour combien first, filled with its own servings and read as it is`() = runTest(testDispatcher) {
        val viewModel = newViewModel(recipeHandle())
        ready(viewModel)

        val session = viewModel.uiState.value.session!!
        assertEquals(CookingPhase.SERVINGS, session.phase)
        assertEquals(4, session.guests)
        assertTrue("no scaling before the question is answered", guestsRequested().isEmpty())
    }

    @Test
    fun `4 to 2 halves the quantities, the API doing the scaling`() = runTest(testDispatcher) {
        val viewModel = newViewModel(recipeHandle())
        ready(viewModel)

        viewModel.setGuests(3)
        viewModel.setGuests(2)
        viewModel.confirmServings()
        settle { viewModel.uiState.value.recipe?.ingredients?.firstOrNull()?.quantity == 100f }

        assertEquals(2, viewModel.uiState.value.session?.guests)
        assertEquals(100f, viewModel.uiState.value.recipe?.ingredients?.first()?.quantity)
        assertEquals(CookingPhase.COOKING, viewModel.uiState.value.session?.phase)
        assertEquals("only the last number asked is read", listOf("2"), guestsRequested().distinct())
    }

    @Test
    fun `guests changed in the preparation scale every recipe of the queue`() = runTest(testDispatcher) {
        val viewModel = newViewModel(mealHandle())
        ready(viewModel)

        viewModel.setGuests(2)
        settle { viewModel.uiState.value.recipe?.ingredients?.firstOrNull()?.quantity == 100f && !viewModel.uiState.value.refreshing }

        assertEquals(listOf(2, 2, 2), viewModel.uiState.value.session?.queue?.map { it.servings })
        assertEquals(setOf("r-a", "r-b", "r-c"), viewModel.uiState.value.recipes.keys)
        assertTrue(viewModel.uiState.value.recipes.values.all { it.ingredients.first().quantity == 100f })
    }

    @Test
    fun `three recipes are cooked one after the other, in order, then Bon appetit`() = runTest(testDispatcher) {
        val viewModel = newViewModel(mealHandle())
        ready(viewModel)
        val visited = mutableListOf<String>()

        while (viewModel.uiState.value.session?.phase == CookingPhase.COOKING) {
            val session = viewModel.uiState.value.session!!
            visited += "${session.current?.recipeId}:${session.step}"
            viewModel.next()
        }

        assertEquals(listOf("r-a:0", "r-a:1", "r-a:2", "r-b:0", "r-b:1", "r-b:2", "r-c:0", "r-c:1", "r-c:2"), visited)
        assertEquals(CookingPhase.DONE, viewModel.uiState.value.session?.phase)
    }

    @Test
    fun `steps are read in their position order and Precedent goes back down to the preparation`() = runTest(testDispatcher) {
        val viewModel = newViewModel(mealHandle())
        ready(viewModel)

        assertEquals(listOf("Étape 1 de r-a", "Étape 2 de r-a"), viewModel.uiState.value.steps)
        viewModel.next()
        viewModel.next()
        assertEquals(2, viewModel.uiState.value.session?.step)

        viewModel.previous()
        viewModel.previous()
        viewModel.previous()

        assertEquals(0, viewModel.uiState.value.session?.step)
    }

    @Test
    fun `a recreation with the same saved state resumes on the same recipe, step and guests`() = runTest(testDispatcher) {
        val handle = mealHandle()
        val first = newViewModel(handle)
        ready(first)
        first.setGuests(6)
        settle { !first.uiState.value.refreshing && first.uiState.value.recipe?.ingredients?.firstOrNull()?.quantity == 300f }
        repeat(3) { first.next() } // preparation → step 1 → step 2 → recipe B's preparation
        first.next()

        val second = newViewModel(handle)
        ready(second)

        val resumed = second.uiState.value.session!!
        assertEquals(first.uiState.value.session, resumed)
        assertEquals("r-b", resumed.current?.recipeId)
        assertEquals(1, resumed.step)
        assertEquals(6, resumed.guests)
        assertEquals("Recette r-b", second.uiState.value.recipe?.name)
        assertEquals("the meal itself is not read again", 1, requests.count { it.url.encodedPath == "/api/meals/meal-1" })
    }

    @Test
    fun `a stored session of another launch is not resumed`() = runTest(testDispatcher) {
        val handle = mealHandle()
        val first = newViewModel(handle)
        ready(first)
        first.next()

        val other = SavedStateHandle(mapOf("recipe" to "r-a", "cooking-session" to handle.get<String>("cooking-session")))
        val second = newViewModel(other)
        ready(second)

        assertEquals(CookingPhase.SERVINGS, second.uiState.value.session?.phase)
    }

    @Test
    fun `a meal with no recipe has nothing to cook`() = runTest(testDispatcher) {
        val viewModel = newViewModel(SavedStateHandle(mapOf("meal" to "meal-empty")))

        settle { viewModel.uiState.value.load == CookingLoad.EMPTY }

        assertEquals(CookingLoad.EMPTY, viewModel.uiState.value.load)
    }

    @Test
    fun `an address that names nothing valid has nothing to cook`() = runTest(testDispatcher) {
        val viewModel = newViewModel(SavedStateHandle(mapOf("meal" to "../etc")))

        settle { viewModel.uiState.value.load == CookingLoad.EMPTY }

        assertEquals(CookingLoad.EMPTY, viewModel.uiState.value.load)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a recipe with no step is passed over`() = runTest(testDispatcher) {
        val viewModel = newViewModel(mealHandle()) { id -> if (id == "r-a") 0 else 2 }
        ready(viewModel)

        assertTrue(viewModel.uiState.value.steps.isEmpty())

        viewModel.skipRecipe()
        settle { viewModel.uiState.value.recipe != null }

        assertEquals("r-b", viewModel.uiState.value.session?.current?.recipeId)
    }

    @Test
    fun `an unreachable API shows the error, never closes, and Reessayer starts again`() = runTest(testDispatcher) {
        failing = true
        val viewModel = newViewModel(mealHandle())
        settle { viewModel.uiState.value.load == CookingLoad.ERROR }
        assertEquals(CookingLoad.ERROR, viewModel.uiState.value.load)

        failing = false
        viewModel.retry()
        ready(viewModel)

        assertEquals(CookingLoad.READY, viewModel.uiState.value.load)
        assertNotNull(viewModel.uiState.value.session)
        assertFalse(viewModel.uiState.value.refreshing)
    }

    @Test
    fun `a recipe lost while the guests change shows the error too`() = runTest(testDispatcher) {
        val viewModel = newViewModel(mealHandle())
        ready(viewModel)

        failing = true
        viewModel.setGuests(2)
        settle { viewModel.uiState.value.load == CookingLoad.ERROR }

        assertEquals(CookingLoad.ERROR, viewModel.uiState.value.load)
        assertEquals("the session is kept", 2, viewModel.uiState.value.session?.guests)
    }
}
