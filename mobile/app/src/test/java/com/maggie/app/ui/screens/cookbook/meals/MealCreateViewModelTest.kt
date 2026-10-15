package com.maggie.app.ui.screens.cookbook.meals

import com.maggie.app.data.api.MaggieApiService
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * Planning a meal creates it once (MAG-378): the sending state lives in the ViewModel, so a
 * second tap on « Créer » finds the first request still in flight and sends nothing. The API
 * behind it is a counting [MockEngine] — the claim is about requests on the wire, not about
 * a mocked repository being called.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MealCreateViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val posts = java.util.Collections.synchronizedList(mutableListOf<HttpRequestData>())
    private var gate: CompletableDeferred<Unit>? = null
    private var status = HttpStatusCode.Created
    private var settingsStatus: HttpStatusCode = HttpStatusCode.OK

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): MealCreateViewModel {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) posts += request
            gate?.await()
            if (request.url.encodedPath.endsWith("/api/meal-settings/me")) {
                return@MockEngine if (settingsStatus.value >= 400) {
                    respondError(settingsStatus)
                } else {
                    respond(
                        content = ByteReadChannel("""{"id":"ms-1","defaultGuests":3,"defaults":{"defaultGuests":2}}"""),
                        status = settingsStatus,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            if (status.value >= 400) {
                respondError(status)
            } else {
                respond(
                    content = ByteReadChannel("""{"id":"meal-1","summary":"Pâtes","date":"2026-10-12","slot":"lunch","recipes":[]}"""),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        return MealCreateViewModel(MealRepository(MaggieApiService(client)))
    }

    // The engine answers on its own dispatcher: the test clock cannot advance it, only wait for it.
    private suspend fun TestScope.settle(until: () -> Boolean) {
        repeat(150) {
            advanceUntilIdle()
            if (until()) return
            withContext(Dispatchers.Default) { delay(20) }
        }
    }

    private val pasta = Recipe(id = "r-pasta", name = "Pâtes")
    private val salad = Recipe(id = "r-salad", name = "Salade verte")

    @Test
    fun `two quick taps on Creer send a single POST`() = runTest(testDispatcher) {
        gate = CompletableDeferred()
        val viewModel = newViewModel()
        viewModel.add(pasta)
        val created = mutableListOf<String>()

        viewModel.submit("2026-10-12", "lunch") { id, _ -> created += id }
        viewModel.submit("2026-10-12", "lunch") { id, _ -> created += id }
        settle { posts.size >= 1 }
        gate!!.complete(Unit)
        settle { created.isNotEmpty() }
        // Long enough for a second request to have come, had one been sent.
        withContext(Dispatchers.Default) { delay(150) }
        advanceUntilIdle()

        assertEquals(1, posts.size)
        assertEquals(listOf("meal-1"), created)
    }

    @Test
    fun `the first tap turns the button into an indicator until the dialog is gone`() = runTest(testDispatcher) {
        gate = CompletableDeferred()
        val viewModel = newViewModel()
        var created = false

        assertFalse(viewModel.uiState.value.isSubmitting)
        viewModel.submit("2026-10-12", "lunch") { _, _ -> created = true }
        assertTrue(viewModel.uiState.value.isSubmitting)
        settle { posts.size >= 1 }
        assertTrue("still waiting for the answer", viewModel.uiState.value.isSubmitting)

        gate!!.complete(Unit)
        settle { created }
        assertTrue("a tap between the answer and the close must not send again", viewModel.uiState.value.isSubmitting)

        viewModel.reset()
        assertFalse(viewModel.uiState.value.isSubmitting)
    }

    @Test
    fun `a success hands the new meal over, with whether it has recipes`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        viewModel.add(pasta)
        viewModel.add(salad)
        var result: Pair<String, Boolean>? = null

        viewModel.submit("2026-10-12", "lunch") { id, withRecipes -> result = id to withRecipes }
        settle { result != null }

        assertEquals("meal-1" to true, result)
        val body = (posts.single().body as TextContent).text
        assertTrue(body, body.contains("/api/recipes/r-pasta") && body.contains("/api/recipes/r-salad"))
        assertFalse("the title is the API's to write: $body", body.contains("summary"))
    }

    @Test
    fun `an empty menu is a meal with no recipes`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        var result: Pair<String, Boolean>? = null

        viewModel.submit("2026-10-12", "dinner") { id, withRecipes -> result = id to withRecipes }
        settle { result != null }

        assertEquals("meal-1" to false, result)
    }

    @Test
    fun `a refused creation shows the error and lets the user try again`() = runTest(testDispatcher) {
        status = HttpStatusCode.BadRequest
        val viewModel = newViewModel()
        var created = false

        viewModel.submit("2026-10-12", "lunch") { _, _ -> created = true }
        settle { viewModel.uiState.value.error != null }

        assertFalse(created)
        assertFalse(viewModel.uiState.value.isSubmitting)
        assertNotNull(viewModel.uiState.value.error)

        status = HttpStatusCode.Created
        viewModel.submit("2026-10-12", "lunch") { _, _ -> created = true }
        settle { created }

        assertTrue(created)
        assertEquals(2, posts.size)
    }

    @Test
    fun `the menu keeps each recipe once and lets one go`() = runTest(testDispatcher) {
        val viewModel = newViewModel()

        viewModel.add(pasta)
        viewModel.add(salad)
        viewModel.add(pasta)
        assertEquals(listOf(pasta, salad), viewModel.uiState.value.recipes)

        viewModel.remove(pasta.id)
        assertEquals(listOf(salad), viewModel.uiState.value.recipes)
    }

    @Test
    fun `reset forgets the menu and the error`() = runTest(testDispatcher) {
        status = HttpStatusCode.BadRequest
        val viewModel = newViewModel()
        viewModel.add(pasta)
        viewModel.submit("2026-10-12", "lunch") { _, _ -> }
        settle { viewModel.uiState.value.error != null }

        viewModel.reset()

        assertEquals(MealCreateUiState(), viewModel.uiState.value)
        assertNull(viewModel.uiState.value.error)
    }

    private fun postedBody(): String = (posts.single().body as TextContent).text

    @Test
    fun `the stepper starts at the default read from the meal settings, marked par defaut`() = runTest(testDispatcher) {
        val viewModel = newViewModel()

        viewModel.loadDefaultGuests()
        settle { viewModel.uiState.value.defaultGuests != null }

        assertEquals(3, viewModel.uiState.value.guests)
        assertTrue(viewModel.uiState.value.guestsIsDefault)
    }

    @Test
    fun `an untouched stepper creates the meal without a number, so it follows the default`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        viewModel.loadDefaultGuests()
        settle { viewModel.uiState.value.defaultGuests != null }
        var created = false

        viewModel.submit("2026-10-12", "lunch") { _, _ -> created = true }
        settle { created }

        assertFalse(postedBody(), postedBody().contains("guests"))
    }

    @Test
    fun `touching the stepper sends the number and drops the par defaut mark`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        viewModel.loadDefaultGuests()
        settle { viewModel.uiState.value.defaultGuests != null }
        var created = false

        viewModel.stepGuests(1)
        viewModel.stepGuests(1)
        assertEquals(5, viewModel.uiState.value.guests)
        assertFalse(viewModel.uiState.value.guestsIsDefault)

        viewModel.stepGuests(-1)
        assertEquals(4, viewModel.uiState.value.guests)
        viewModel.submit("2026-10-12", "lunch") { _, _ -> created = true }
        settle { created }

        assertTrue(postedBody(), postedBody().contains("\"guests\":4"))
    }

    @Test
    fun `the number never goes under one`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        viewModel.loadDefaultGuests()
        settle { viewModel.uiState.value.defaultGuests != null }

        repeat(6) { viewModel.stepGuests(-1) }

        assertEquals(1, viewModel.uiState.value.guests)
    }

    @Test
    fun `without a readable default the stepper starts at one and still sends no number if untouched`() = runTest(testDispatcher) {
        settingsStatus = HttpStatusCode.InternalServerError
        val viewModel = newViewModel()
        var created = false

        viewModel.loadDefaultGuests()
        withContext(Dispatchers.Default) { delay(150) }
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.guests)
        assertNull(viewModel.uiState.value.defaultGuests)

        viewModel.submit("2026-10-12", "lunch") { _, _ -> created = true }
        settle { created }

        assertFalse(postedBody(), postedBody().contains("guests"))
    }
}
