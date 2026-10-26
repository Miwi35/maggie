package com.maggie.app.ui.screens.cookbook.cooking

import androidx.lifecycle.SavedStateHandle
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.local.dao.RecipeDao
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.data.repository.CookingTimerRepository
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
import io.ktor.http.content.OutgoingContent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Cuisine activity's state (MAG-580): what it reads, how the activity status (MAG-615) resumes it, and the
 * states between (loading, empty, error, conflict). Against a counting [MockEngine] — what matters is what goes on the wire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CookingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<HttpRequestData>())
    private var failing = false
    private var mealJson = mealWith(listOf("r-a", "r-b", "r-c"), guests = 4)

    /** The API's timers (MAG-582), on the clock the test moves. */
    private val server = FakeTimerServer { nowMs }
    private var nowMs = 1_000_000L
    private val timerEvents = MutableSharedFlow<MercureEvent>(extraBufferCapacity = 16)
    private val mercure = mockk<MercureService> {
        every { subscribe(any()) } returns timerEvents
        every { reconnections(any()) } returns emptyFlow()
    }
    private val auth = mockk<AuthRepository> { coEvery { getUserId() } returns "u-1" }

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
        val stepsJson = if (id == REFERENCE) REFERENCE_STEPS else {
            (steps downTo 1).joinToString(",") { """{"position":$it,"text":"Étape $it de $id"}""" }
        }
        return """{"id":"$id","name":"Recette $id","servings":4,""" +
            """"ingredients":[{"ingredientName":"Farine","quantity":$flour,"unit":"g"}],""" +
            """"utensils":[{"id":"u1","name":"Fouet"}],"steps":[$stepsJson]}"""
    }

    /** Where the timers' cancellations and the status' writes are sent: the test's own scheduler. */
    private var timerScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(testDispatcher)

    /** The activity status (MAG-615), shared by the view models of a test as the app's repository is. */
    private val status = FakeActivityStatusServer()
    private var statusRepository: ActivityStatusRepository? = null

    private fun newViewModel(handle: SavedStateHandle, stepsOf: (String) -> Int = { 2 }): CookingViewModel {
        val engine = MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            val timerAnswer = if (path.startsWith("/api/cooking/timers")) {
                server.handle(request.method, path, (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString())
            } else {
                null
            }
            if (path.startsWith("/api/activity-status/")) {
                val (code, content) = status.handle(request.method, (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString())
                return@MockEngine respond(
                    content = ByteReadChannel(content),
                    status = HttpStatusCode.fromValue(code),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val body = when {
                timerAnswer != null -> timerAnswer.second
                failing -> null
                path == "/api/meals/meal-1" -> mealJson
                path == "/api/meals/meal-empty" -> mealWith(emptyList(), guests = 2)
                path.startsWith("/api/recipes/") -> {
                    val id = path.substringAfterLast('/')
                    recipeJson(id, request.url.parameters["guests"]?.toInt(), stepsOf(id))
                }
                else -> null
            }
            if (timerAnswer != null) {
                respond(
                    content = ByteReadChannel(timerAnswer.second),
                    status = HttpStatusCode.fromValue(timerAnswer.first),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else if (body == null) {
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
        return CookingViewModel(
            handle,
            MealRepository(api),
            RecipeRepository(api, mockk<RecipeDao>(relaxed = true)),
            CookingTimerRepository(api, sendScope = timerScope),
            statusRepository ?: ActivityStatusRepository(api, timerScope).also { statusRepository = it },
            mercure,
            auth,
            clock = { nowMs },
        )
    }

    private fun referenceHandle(): SavedStateHandle {
        mealJson = mealWith(listOf(REFERENCE, "r-b"), guests = 4)
        return mealHandle()
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
            state.load == CookingLoad.READY && !state.refreshing && state.recipe != null && state.timersLoaded
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

        assertEquals(listOf("Étape 1 de r-a", "Étape 2 de r-a"), viewModel.uiState.value.steps.map { it.text })
        viewModel.next()
        viewModel.next()
        assertEquals(2, viewModel.uiState.value.session?.step)

        viewModel.previous()
        viewModel.previous()
        viewModel.previous()

        assertEquals(0, viewModel.uiState.value.session?.step)
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

    @Test
    fun `Suivant ticks the step it leaves, the preparation ticks nothing`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)

        viewModel.next()
        assertEquals(1, viewModel.uiState.value.session?.step)
        assertEquals(emptyMap<String, List<Int>>(), viewModel.uiState.value.session?.doneSteps)

        viewModel.next()
        viewModel.next()

        assertEquals(3, viewModel.uiState.value.session?.step)
        assertEquals(mapOf(REFERENCE to listOf(1, 2)), viewModel.uiState.value.session?.doneSteps)
    }

    @Test
    fun `Suivant is never held back, whatever the steps awaited and their timers`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        repeat(3) { viewModel.next() }
        assertEquals("step 3 awaits step 1, which has no timer running", 3, viewModel.uiState.value.session?.step)

        viewModel.next()
        assertEquals(4, viewModel.uiState.value.session?.step)

        viewModel.next()
        assertEquals("step 5 awaits steps 3 and 4, never finished", 5, viewModel.uiState.value.session?.step)

        viewModel.next()
        assertEquals("r-b", viewModel.uiState.value.session?.current?.recipeId)
        assertEquals(0, viewModel.uiState.value.session?.step)
        assertEquals(mapOf(REFERENCE to listOf(1, 2, 3, 4, 5)), viewModel.uiState.value.session?.doneSteps)
    }

    @Test
    fun `Precedent goes back and leaves the ticks as they are`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        repeat(3) { viewModel.next() }
        val ticked = viewModel.uiState.value.session?.doneSteps

        viewModel.previous()

        assertEquals(2, viewModel.uiState.value.session?.step)
        assertEquals(ticked, viewModel.uiState.value.session?.doneSteps)
    }

    @Test
    fun `a step touched in the list is shown, not ticked`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        viewModel.next()

        viewModel.openStep(0, 4)

        assertEquals(0, viewModel.uiState.value.session?.recipeIndex)
        assertEquals(4, viewModel.uiState.value.session?.step)
        assertEquals(emptyMap<String, List<Int>>(), viewModel.uiState.value.session?.doneSteps)

        viewModel.openStep(0, 1)
        assertEquals(1, viewModel.uiState.value.session?.step)
        assertEquals(emptyMap<String, List<Int>>(), viewModel.uiState.value.session?.doneSteps)
    }

    @Test
    fun `a step of another recipe of the queue is reached too, ticking nothing`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        viewModel.next()

        viewModel.openStep(1, 2)
        settle { viewModel.uiState.value.recipe?.id == "r-b" }

        val session = viewModel.uiState.value.session!!
        assertEquals(1, session.recipeIndex)
        assertEquals(2, session.step)
        assertEquals("r-b", viewModel.uiState.value.recipe?.id)
        assertEquals(emptyMap<String, List<Int>>(), session.doneSteps)
    }

    @Test
    fun `a step that does not exist is not opened`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        viewModel.next()
        val before = viewModel.uiState.value.session

        viewModel.openStep(0, 9)
        viewModel.openStep(5, 1)
        viewModel.openStep(-1, 1)

        assertEquals(before, viewModel.uiState.value.session)
    }

    @Test
    fun `the progress counts the ticked steps over every step of the meal`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        assertEquals(7, viewModel.uiState.value.totalSteps)
        assertEquals(0, viewModel.uiState.value.doneCount)

        repeat(3) { viewModel.next() }

        assertEquals(2, viewModel.uiState.value.doneCount)
        assertEquals(7, viewModel.uiState.value.totalSteps)
        assertEquals(mapOf(REFERENCE to 5, "r-b" to 2), viewModel.uiState.value.stepCounts)
    }

    @Test
    fun `the next recipe follows the last step with its preparation`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        repeat(6) { viewModel.next() }

        val session = viewModel.uiState.value.session!!
        assertEquals(CookingPhase.COOKING, session.phase)
        assertEquals("r-b", session.current?.recipeId)
        assertEquals(0, session.step)
        assertEquals(listOf(1, 2, 3, 4, 5), session.doneSteps[REFERENCE])
    }

    @Test
    fun `the last step of the last recipe closes on Bon appetit with the time spent`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        repeat(6) { viewModel.next() }
        repeat(3) { viewModel.next() } // → B's two steps → Bon appétit
        settle { viewModel.uiState.value.recipe != null }

        val session = viewModel.uiState.value.session!!
        assertEquals(CookingPhase.DONE, session.phase)
        assertEquals(true, session.finishedAt >= session.startedAt)
        assertEquals(1, session.minutesCooked)
        assertEquals(listOf(1, 2), session.doneSteps["r-b"])
    }

    // --- The activity status (MAG-615)

    /** A Cuisine in progress on meal-1: recipe A at step 3 with step 1 and 2 ticked, for 6, in the middle of the afternoon. */
    private fun seedMeal(step: Int = 3, recipeIndex: Int = 0) = status.seed(
        "meal",
        "meal-1",
        """{"queue":[{"recipeId":"r-ref","servings":6},{"recipeId":"r-b","servings":6}],"recipeIndex":$recipeIndex,"step":$step,""" +
            """"doneSteps":{"r-ref":[1,2]},"guests":6,"phase":"cooking","startedAt":1234567}""",
    )

    private suspend fun TestScope.written(count: Int) = settle { status.writes.size >= count }

    @Test
    fun `opening with no status starts as before and writes nothing`() = runTest(testDispatcher) {
        val viewModel = newViewModel(mealHandle())
        ready(viewModel)
        advanceUntilIdle()

        assertEquals(0, viewModel.state.session?.step)
        assertEquals(1, status.gets)
        assertTrue(status.writes.isEmpty())
    }

    @Test
    fun `the same origin resumes exactly - step, ticks, guests, phase and start time`() = runTest(testDispatcher) {
        mealJson = mealWith(listOf(REFERENCE, "r-b"), guests = 4)
        seedMeal()
        val viewModel = newViewModel(mealHandle())
        ready(viewModel)

        val session = viewModel.state.session!!
        assertEquals(3, session.step)
        assertEquals(0, session.recipeIndex)
        assertEquals(mapOf(REFERENCE to listOf(1, 2)), session.doneSteps)
        assertEquals(6, session.guests)
        assertEquals(listOf(6, 6), session.queue.map { it.servings })
        assertEquals(CookingPhase.COOKING, session.phase)
        assertEquals(1234567L, session.startedAt)
        assertEquals("the meal itself is not read again", 0, requests.count { it.url.encodedPath == "/api/meals/meal-1" })
        assertEquals("the recipes are read for the guests of the status", setOf("6"), guestsRequested().toSet())
        assertTrue("opening writes nothing", status.writes.isEmpty())
    }

    @Test
    fun `a recipe left on Pour combien resumes there`() = runTest(testDispatcher) {
        status.seed("recipe", "r-a", """{"queue":[{"recipeId":"r-a","servings":3}],"recipeIndex":0,"step":0,"doneSteps":{},"guests":3,"phase":"servings","startedAt":99}""")
        val viewModel = newViewModel(recipeHandle())
        ready(viewModel)

        val session = viewModel.state.session!!
        assertEquals(CookingPhase.SERVINGS, session.phase)
        assertEquals(3, session.guests)
        assertEquals(99L, session.startedAt)
        assertEquals("Recette r-a", viewModel.state.recipe?.name)
    }

    @Test
    fun `a Cuisine left by a first view model is resumed by the next one`() = runTest(testDispatcher) {
        val handle = mealHandle()
        val first = newViewModel(handle)
        ready(first)
        first.setGuests(6)
        settle { !first.state.refreshing && first.state.recipe?.ingredients?.firstOrNull()?.quantity == 300f }
        repeat(4) { first.next() } // preparation → step 1 → step 2 → recipe B's preparation → B's step 1
        settle { status.session()?.let { it.recipeIndex == 1 && it.step == 1 } == true }

        val second = newViewModel(handle)
        ready(second)

        val resumed = second.state.session!!
        assertEquals(first.state.session, resumed)
        assertEquals("r-b", resumed.current?.recipeId)
        assertEquals(1, resumed.step)
        assertEquals(6, resumed.guests)
        assertEquals("Recette r-b", second.state.recipe?.name)
    }

    @Test
    fun `a rotation resumes on the same step with the same steps ticked`() = runTest(testDispatcher) {
        val handle = referenceHandle()
        val first = newViewModel(handle)
        ready(first)
        repeat(4) { first.next() }
        first.openStep(0, 5)
        assertEquals(mapOf(REFERENCE to listOf(1, 2, 3)), first.state.session?.doneSteps)
        settle { status.session()?.step == 5 }

        val second = newViewModel(handle)
        ready(second)

        assertEquals(first.state.session, second.state.session)
        assertEquals(5, second.state.session?.step)
        assertEquals(3, second.state.doneCount)
    }

    @Test
    fun `a status this version cannot read is started over`() = runTest(testDispatcher) {
        status.seed("meal", "meal-1", """{"queue":"nonsense"}""")
        val viewModel = newViewModel(mealHandle())
        ready(viewModel)

        assertEquals(0, viewModel.state.session?.step)
        assertEquals(CookingLoad.READY, viewModel.state.load)
    }

    @Test
    fun `an unreachable status shows the error, and Reessayer reads it again`() = runTest(testDispatcher) {
        status.failing = true
        val viewModel = newViewModel(mealHandle())
        settle { viewModel.state.load == CookingLoad.ERROR }
        assertEquals(CookingLoad.ERROR, viewModel.state.load)
        assertNull("nothing is started over a status that could not be read", viewModel.state.session)

        status.failing = false
        viewModel.retry()
        ready(viewModel)

        assertEquals(CookingLoad.READY, viewModel.state.load)
    }

    @Test
    fun `Suivant writes the step, its ticks and the phase, as the admin reads them`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        viewModel.next()
        viewModel.next()
        written(1)
        settle { status.session()?.step == 2 }

        val state = status.state!!
        assertEquals("meal" to "meal-1", status.origin)
        assertEquals("cooking", state.getValue("phase").jsonPrimitive.content)
        assertEquals(2, state.getValue("step").jsonPrimitive.int)
        assertEquals(0, state.getValue("recipeIndex").jsonPrimitive.int)
        assertEquals(4, state.getValue("guests").jsonPrimitive.int)
        assertEquals("[1]", state.getValue("doneSteps").jsonObject.getValue(REFERENCE).toString())
        assertEquals(listOf(REFERENCE, "r-b"), state.getValue("queue").jsonArray.map { it.jsonObject.getValue("recipeId").jsonPrimitive.content })
        assertEquals(nowMs, state.getValue("startedAt").jsonPrimitive.long)
        assertFalse("the end is not in the state", "finishedAt" in state)
    }

    @Test
    fun `Precedent writes the step it goes back to`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        repeat(3) { viewModel.next() }
        settle { status.session()?.step == 3 }

        viewModel.previous()
        settle { status.session()?.step == 2 }

        assertEquals(2, status.session()?.step)
    }

    @Test
    fun `a step touched in the list is written`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)

        viewModel.openStep(0, 4)
        settle { status.session()?.step == 4 }

        assertEquals(4, status.session()?.step)
        assertEquals(emptyMap<String, List<Int>>(), status.session()?.doneSteps)
    }

    @Test
    fun `the guests and Continuer are written`() = runTest(testDispatcher) {
        val viewModel = newViewModel(recipeHandle())
        ready(viewModel)

        viewModel.setGuests(2)
        settle { status.session()?.guests == 2 }
        assertEquals(CookingPhase.SERVINGS, status.session()?.phase)

        viewModel.confirmServings()
        settle { status.session()?.phase == CookingPhase.COOKING }

        assertEquals("recipe" to "r-a", status.origin)
        assertEquals(2, status.session()?.guests)
        assertEquals(listOf(2), status.session()?.queue?.map { it.servings })
    }

    @Test
    fun `a recipe passed over is written`() = runTest(testDispatcher) {
        val viewModel = newViewModel(mealHandle()) { id -> if (id == "r-a") 0 else 2 }
        ready(viewModel)

        viewModel.skipRecipe()
        settle { status.session()?.recipeIndex == 1 }

        assertEquals(1, status.session()?.recipeIndex)
    }

    @Test
    fun `a quick run of changes is coalesced, the last one lands`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)

        repeat(5) { viewModel.next() }
        settle { status.session()?.step == 5 }
        advanceUntilIdle()

        assertEquals(5, status.session()?.step)
        assertTrue("fewer writes than changes", status.writes.size < 5)
    }

    @Test
    fun `Repas servi ends the status instead of writing the end`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        repeat(6) { viewModel.next() }
        repeat(3) { viewModel.next() }
        settle { status.origin == null && status.writes.lastOrNull() == "delete" }

        assertEquals(CookingPhase.DONE, viewModel.state.session?.phase)
        assertEquals("delete", status.writes.last())
        assertNull(status.origin)
        assertTrue("done is never written", status.puts.none { it.getValue("phase").jsonPrimitive.content == "done" })
    }

    @Test
    fun `another origin opens the choice, nothing is replaced`() = runTest(testDispatcher) {
        seedMeal(step = 3)
        val viewModel = newViewModel(recipeHandle())
        settle { viewModel.state.load == CookingLoad.CONFLICT }
        advanceUntilIdle()

        val conflict = viewModel.state.conflict!!
        assertEquals(CookingOrigin(CookingOrigin.Kind.MEAL, "meal-1"), conflict.session.origin)
        assertEquals(CookingOrigin.Kind.RECIPE, conflict.launchKind)
        assertEquals("Recette r-ref · étape 3/5", conflict.detail)
        assertEquals("meal-1", status.origin?.second)
        assertTrue(status.writes.isEmpty())
    }

    @Test
    fun `Reprendre on the choice goes back to the Cuisine in progress`() = runTest(testDispatcher) {
        mealJson = mealWith(listOf(REFERENCE, "r-b"), guests = 4)
        seedMeal(step = 3)
        val viewModel = newViewModel(recipeHandle())
        settle { viewModel.state.load == CookingLoad.CONFLICT }

        viewModel.resumeInProgress()
        ready(viewModel)

        assertNull(viewModel.state.conflict)
        assertEquals(CookingLoad.READY, viewModel.state.load)
        assertEquals(CookingOrigin(CookingOrigin.Kind.MEAL, "meal-1"), viewModel.state.session?.origin)
        assertEquals(3, viewModel.state.session?.step)
        assertEquals(REFERENCE, viewModel.state.recipe?.id)
        assertTrue(status.writes.isEmpty())
    }

    @Test
    fun `Recommencer on the choice ends the one in progress and starts this one new`() = runTest(testDispatcher) {
        seedMeal(step = 3)
        val viewModel = newViewModel(recipeHandle())
        settle { viewModel.state.load == CookingLoad.CONFLICT }

        viewModel.replaceInProgress()
        ready(viewModel)
        settle { status.writes == listOf("delete") }

        assertEquals(listOf("delete"), status.writes)
        assertNull(status.origin)
        assertNull(viewModel.state.conflict)
        assertEquals(CookingOrigin(CookingOrigin.Kind.RECIPE, "r-a"), viewModel.state.session?.origin)
        assertEquals(CookingPhase.SERVINGS, viewModel.state.session?.phase)
    }

    @Test
    fun `Recommencer on the choice cancels the timers still running`() = runTest(testDispatcher) {
        server.seed("Eau", REFERENCE, 1, 600)
        seedMeal(step = 3)
        val viewModel = newViewModel(recipeHandle())
        settle { viewModel.state.load == CookingLoad.CONFLICT && viewModel.state.timersLoaded }
        timersRead(viewModel, 1)

        viewModel.replaceInProgress()
        settle { server.patches.size == 1 }

        assertTrue(viewModel.state.timers.isEmpty())
        assertEquals(listOf("cancel"), server.patches.map { it.second })
    }

    @Test
    fun `Recommencer goes back to the preparation, stops the timers and writes nothing yet`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        atStep4(viewModel)
        settle { status.session()?.step == 4 }
        status.writes.clear()
        nowMs += 5_000

        viewModel.restart()
        settle { server.patches.size == 2 && status.writes == listOf("delete") }
        advanceUntilIdle()

        val session = viewModel.state.session!!
        assertEquals(CookingPhase.COOKING, session.phase)
        assertEquals(0, session.step)
        assertEquals(0, session.recipeIndex)
        assertEquals(emptyMap<String, List<Int>>(), session.doneSteps)
        assertEquals(nowMs, session.startedAt)
        assertEquals(listOf("cancel", "cancel"), server.patches.map { it.second })
        assertTrue(viewModel.state.timers.isEmpty())
        assertEquals("the status ends, nothing is written until the next change", listOf("delete"), status.writes)
        assertNull(status.origin)
    }

    @Test
    fun `after Recommencer the next change writes the Cuisine again`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        repeat(3) { viewModel.next() }
        settle { status.session()?.step == 3 }

        viewModel.restart()
        settle { status.origin == null }
        viewModel.next()
        settle { status.session()?.step == 1 }

        assertEquals(1, status.session()?.step)
        assertEquals(emptyMap<String, List<Int>>(), status.session()?.doneSteps)
    }

    // --- The timers (MAG-582)

    private val CookingViewModel.state get() = uiState.value

    private fun event(row: FakeTimerServer.Row) =
        MercureEvent(data = """{"@id":"/api/cooking/timers/${row.id}",""" + server.json(row).drop(1))

    private suspend fun TestScope.timersRead(viewModel: CookingViewModel, count: Int) {
        settle { viewModel.state.timers.size == count }
    }

    /** Step 1, « Lancer et continuer »: the water's timer runs and step 2 is on screen. */
    private suspend fun TestScope.waterStarted(viewModel: CookingViewModel) {
        ready(viewModel)
        viewModel.next()
        viewModel.startAndNext()
        settle { viewModel.state.session?.step == 2 }
    }

    /** On to step 4, with the water and the pasta started in the background. */
    private suspend fun TestScope.atStep4(viewModel: CookingViewModel) {
        waterStarted(viewModel)
        viewModel.next()
        viewModel.startAndNext()
        settle { viewModel.state.session?.step == 4 }
    }

    @Test
    fun `Lancer et continuer starts the step's timer, then goes to the next step`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        waterStarted(viewModel)

        assertEquals(1, server.posts.size)
        assertTrue(server.posts.single().contains("\"label\":\"Eau\""))
        assertTrue(server.posts.single().contains("\"durationSeconds\":600"))
        assertTrue(server.posts.single().contains("\"stepPosition\":1"))
        val timer = viewModel.state.timers.single()
        assertEquals("Eau", timer.label)
        assertEquals(600, viewModel.state.remainingOf(timer))
    }

    @Test
    fun `Lancer et continuer on a step whose timer already runs only goes on`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        waterStarted(viewModel)

        viewModel.previous()
        viewModel.startAndNext()
        settle { viewModel.state.session?.step == 2 }

        assertEquals(2, viewModel.state.session?.step)
        assertEquals("no second timer", 1, server.posts.size)
    }

    @Test
    fun `a refused start leaves the step where it is and says why`() = runTest(testDispatcher) {
        server.refuseStart = true
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        viewModel.next()

        viewModel.startAndNext()
        settle { viewModel.state.timerError != null }

        assertEquals(1, viewModel.state.session?.step)
        assertEquals(CookingViewModel.START_FAILED, viewModel.state.timerError)
        assertTrue(viewModel.state.timers.isEmpty())
        assertFalse(viewModel.state.starting)
    }

    @Test
    fun `each tick counts down from what the API said`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        waterStarted(viewModel)

        nowMs += 61_000
        viewModel.tick()

        assertEquals(539, viewModel.state.remainingOf(viewModel.state.timers.single()))
    }

    @Test
    fun `Demarrer starts a step that is not in the background, without leaving it`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        atStep4(viewModel)

        viewModel.toggleTimer()
        timersRead(viewModel, 3)

        assertEquals(4, viewModel.state.session?.step)
        assertEquals("Légumes", viewModel.state.timerOf(4)?.label)
        assertEquals(480, viewModel.state.timerOf(4)?.let(viewModel.state::remainingOf))
    }

    @Test
    fun `Pause keeps the rest and Reprendre goes on from it`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        atStep4(viewModel)
        viewModel.toggleTimer()
        timersRead(viewModel, 3)
        nowMs += 80_000
        viewModel.tick()

        viewModel.toggleTimer()
        settle { viewModel.state.timerOf(4)?.status == com.maggie.app.data.model.CookingTimerStatus.PAUSED }
        nowMs += 30_000
        viewModel.tick()
        assertEquals("paused, it does not count", 400, viewModel.state.timerOf(4)?.let(viewModel.state::remainingOf))

        viewModel.toggleTimer()
        settle { viewModel.state.timerOf(4)?.status == com.maggie.app.data.model.CookingTimerStatus.RUNNING }
        assertEquals(400, viewModel.state.timerOf(4)?.let(viewModel.state::remainingOf))
        assertEquals(listOf("pause", "resume"), server.patches.map { it.second })
    }

    @Test
    fun `plus and minus one minute move the step's timer`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        atStep4(viewModel)
        viewModel.toggleTimer()
        timersRead(viewModel, 3)

        viewModel.addMinutes(1)
        settle { viewModel.state.timerOf(4)?.remainingSeconds == 540 }
        viewModel.addMinutes(-1)
        settle { viewModel.state.timerOf(4)?.remainingSeconds == 480 }

        assertEquals(480, viewModel.state.timerOf(4)?.let(viewModel.state::remainingOf))
        assertEquals(listOf("add", "add"), server.patches.map { it.second })
    }

    @Test
    fun `minus one on the last minute rings it at once, its step's line lit and nothing else moved`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        atStep4(viewModel)
        viewModel.toggleTimer()
        timersRead(viewModel, 3)
        nowMs += 450_000
        viewModel.tick()

        viewModel.addMinutes(-1)
        settle { viewModel.state.ringing.isNotEmpty() }

        assertEquals("Légumes", viewModel.state.ringing.single().label)
        assertEquals("Légumes : c'est prêt", doneTitle(viewModel.state.ringing.single()))
        assertEquals(4, viewModel.state.session?.step)
        assertFalse("ringing ticks nothing", viewModel.state.session!!.isDone(REFERENCE, 4))
    }

    @Test
    fun `Suivant is not held back while the timer of an awaited step runs`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        waterStarted(viewModel)
        viewModel.next()
        assertEquals(3, viewModel.state.session?.step)
        assertEquals("the water still runs", 600, viewModel.state.remainingOf(viewModel.state.timers.single()))

        viewModel.next()

        assertEquals(4, viewModel.state.session?.step)
        assertEquals(1, viewModel.state.timers.size)
    }

    @Test
    fun `Lancer et continuer goes on without ticking the step it started`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        waterStarted(viewModel)

        assertEquals(2, viewModel.state.session?.step)
        assertFalse(viewModel.state.session!!.isDone(REFERENCE, 1))
        assertEquals(0, viewModel.state.doneCount)
    }

    @Test
    fun `a timer rung through Mercure lights its step and changes nothing in the session`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        waterStarted(viewModel)
        viewModel.next()
        val before = viewModel.state.session

        timerEvents.emit(event(server.ring(server.rows.values.single().id)))
        settle { viewModel.state.ringing.isNotEmpty() }

        assertEquals("Eau", viewModel.state.ringing.single().label)
        assertEquals(1, viewModel.state.timerFor(REFERENCE, 1)?.stepPosition)
        assertEquals(before, viewModel.state.session)
        assertEquals(3, viewModel.state.session?.step)
        assertEquals("only step 2, left by Suivant, is ticked", 1, viewModel.state.doneCount)
    }

    @Test
    fun `touching the line of a rung timer opens its step and acknowledges it`() = runTest(testDispatcher) {
        val rung = server.seed("Eau", REFERENCE, 1, 600).also { nowMs += 1; server.ring(it.id) }
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        timersRead(viewModel, 1)
        viewModel.next()
        viewModel.next()
        assertEquals(1, viewModel.state.ringing.size)
        val ticked = viewModel.state.session?.doneSteps

        viewModel.openStep(0, 1)

        assertEquals(1, viewModel.state.session?.step)
        assertEquals("the step is shown, nothing more is ticked", ticked, viewModel.state.session?.doneSteps)
        assertTrue("gone from the screen at once", viewModel.state.ringing.isEmpty())
        settle { server.patches.isNotEmpty() }
        assertEquals(listOf(rung.id to "dismiss"), server.patches)
        assertEquals("dismissed", server.rows.getValue(rung.id).status)
    }

    @Test
    fun `a step whose rung timer was acknowledged does not offer Lancer et continuer again`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        waterStarted(viewModel)
        nowMs += 1
        timerEvents.emit(event(server.ring(server.rows.values.single().id)))
        settle { viewModel.state.ringing.isNotEmpty() }
        viewModel.openStep(0, 1)
        settle { viewModel.state.timerFor(REFERENCE, 1) == null }
        assertEquals(1, server.posts.size)

        assertTrue("the step stays launched", viewModel.state.hasLaunched(1))
        viewModel.startAndNext()
        advanceUntilIdle()

        assertEquals("no second timer", 1, server.posts.size)
        assertEquals("the step is ticked and the cook goes on", 2, viewModel.state.session?.step)
        assertTrue(1 in viewModel.state.session?.doneSteps?.get(REFERENCE).orEmpty())
    }

    @Test
    fun `touching another step leaves a rung timer lit`() = runTest(testDispatcher) {
        server.seed("Eau", REFERENCE, 1, 600).also { nowMs += 1; server.ring(it.id) }
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        timersRead(viewModel, 1)

        viewModel.openStep(0, 2)
        advanceUntilIdle()

        assertEquals(2, viewModel.state.session?.step)
        assertEquals(1, viewModel.state.ringing.size)
        assertTrue(server.patches.isEmpty())
    }

    @Test
    fun `each timer is found by its recipe and step, several at once`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        atStep4(viewModel)

        assertEquals(600, viewModel.state.timerFor(REFERENCE, 1)?.let(viewModel.state::remainingOf))
        assertEquals(720, viewModel.state.timerFor(REFERENCE, 3)?.let(viewModel.state::remainingOf))
        assertNull(viewModel.state.timerFor(REFERENCE, 2))
        assertNull(viewModel.state.timerFor("r-b", 1))
    }

    @Test
    fun `a count that reached zero reads the API again, every two seconds, until it rang`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        waterStarted(viewModel)
        val before = server.gets

        nowMs += 600_000
        viewModel.tick()
        settle { server.gets == before + 1 }
        nowMs += 1_000
        viewModel.tick()
        advanceUntilIdle()
        assertEquals("not twice within two seconds", before + 1, server.gets)

        server.ring(server.rows.values.single().id)
        nowMs += 1_000
        viewModel.tick()
        settle { viewModel.state.ringing.isNotEmpty() }

        assertEquals(before + 2, server.gets)
        assertEquals("Eau", viewModel.state.ringing.single().label)
    }

    @Test
    fun `dismissing a rung timer takes it off the screen and the next one stays lit`() = runTest(testDispatcher) {
        val first = server.seed("Eau", REFERENCE, 1, 600).also { nowMs += 1; server.ring(it.id) }
        nowMs += 1_000
        val second = server.seed("Pâtes", REFERENCE, 3, 720).also { server.ring(it.id) }
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        timersRead(viewModel, 2)
        assertEquals(listOf(first.id, second.id), viewModel.state.ringing.map { it.id })
        assertTrue(viewModel.announce(first.id))
        assertFalse("rung once", viewModel.announce(first.id))

        viewModel.dismiss(first.id)

        assertEquals(listOf(second.id), viewModel.state.ringing.map { it.id })
        settle { server.patches.isNotEmpty() }
        assertEquals(listOf(first.id to "dismiss"), server.patches)
        assertEquals("dismissed", server.rows.getValue(first.id).status)
    }

    @Test
    fun `a timer cancelled or dismissed elsewhere leaves the screen`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        waterStarted(viewModel)
        val row = server.rows.values.single().also { it.status = "cancelled" }

        timerEvents.emit(event(row))
        settle { viewModel.state.timers.isEmpty() }

        assertTrue(viewModel.state.timers.isEmpty())
    }

    @Test
    fun `a rotation while two timers run finds the same timers with the same rest and starts nothing`() = runTest(testDispatcher) {
        val handle = referenceHandle()
        val first = newViewModel(handle)
        atStep4(first)
        nowMs += 65_000
        first.tick()
        val posts = server.posts.size
        val rests = first.state.timers.associate { it.label to first.state.remainingOf(it) }
        assertEquals(mapOf("Eau" to 535, "Pâtes" to 655), rests)

        val second = newViewModel(handle)
        ready(second)
        timersRead(second, 2)

        assertEquals(first.state.timers.map { it.id }, second.state.timers.map { it.id })
        assertEquals(rests, second.state.timers.associate { it.label to second.state.remainingOf(it) })
        assertEquals("no POST on the way back", posts, server.posts.size)
        assertEquals(4, second.state.session?.step)
    }

    private companion object {
        const val REFERENCE = "r-ref"
        const val REFERENCE_STEPS =
            """{"position":1,"text":"Mettre l'eau à bouillir","durationMinutes":10,"background":true,"utensils":[{"id":"u1","name":"Casserole"}]},""" +
                """{"position":2,"text":"Couper les légumes"},""" +
                """{"position":3,"text":"Cuire les pâtes","durationMinutes":12,"background":true,"waitsFor":[1]},""" +
                """{"position":4,"text":"Faire revenir les légumes","durationMinutes":8},""" +
                """{"position":5,"text":"Égoutter, mélanger","waitsFor":[3,4]}"""
    }
}
