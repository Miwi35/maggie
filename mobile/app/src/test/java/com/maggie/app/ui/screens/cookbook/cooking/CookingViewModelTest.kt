package com.maggie.app.ui.screens.cookbook.cooking

import androidx.lifecycle.SavedStateHandle
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.local.dao.RecipeDao
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
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

    /** Where « Quitter » sends its cancellations: the test's own scheduler. */
    private var timerScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(testDispatcher)

    private fun newViewModel(handle: SavedStateHandle, stepsOf: (String) -> Int = { 2 }): CookingViewModel {
        val engine = MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            val timerAnswer = if (path.startsWith("/api/cooking/timers")) {
                server.handle(request.method, path, (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString())
            } else {
                null
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

    @Test
    fun `a step that waits for a timed step is held until Cest pret`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)

        repeat(3) { viewModel.next() }
        assertEquals(3, viewModel.uiState.value.session?.step)
        assertEquals(listOf(1), viewModel.uiState.value.pending)

        viewModel.next()
        assertEquals("Suivant is held back while the step waits", 3, viewModel.uiState.value.session?.step)

        viewModel.ready()
        assertEquals(emptyList<Int>(), viewModel.uiState.value.pending)
        assertEquals(listOf(1), viewModel.uiState.value.session?.ready)

        viewModel.next()
        assertEquals(4, viewModel.uiState.value.session?.step)
    }

    @Test
    fun `the last step waits for the pasta and the vegetables, and Cest pret lifts both`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        repeat(3) { viewModel.next() }
        viewModel.ready()
        repeat(2) { viewModel.next() }

        assertEquals(5, viewModel.uiState.value.session?.step)
        assertEquals(listOf(3, 4), viewModel.uiState.value.pending)

        viewModel.ready()

        assertEquals(emptyList<Int>(), viewModel.uiState.value.pending)
        assertEquals(listOf(1, 3, 4), viewModel.uiState.value.session?.ready)
    }

    @Test
    fun `Precedent is never held back by a wait`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        repeat(3) { viewModel.next() }

        viewModel.previous()

        assertEquals(2, viewModel.uiState.value.session?.step)
        assertEquals(emptyList<Int>(), viewModel.uiState.value.pending)
    }

    @Test
    fun `Cest pret does nothing when nothing is awaited`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        val before = viewModel.uiState.value.session

        viewModel.ready()
        viewModel.next()
        viewModel.ready()

        assertEquals(before?.copy(step = 1), viewModel.uiState.value.session)
    }

    @Test
    fun `the next recipe follows the last step with its preparation and nothing forced`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        repeat(3) { viewModel.next() }
        viewModel.ready()
        repeat(2) { viewModel.next() }
        viewModel.ready()
        viewModel.next()

        val session = viewModel.uiState.value.session!!
        assertEquals(CookingPhase.COOKING, session.phase)
        assertEquals("r-b", session.current?.recipeId)
        assertEquals(0, session.step)
        assertEquals(emptyList<Int>(), session.ready)
    }

    @Test
    fun `the last step of the last recipe closes on Bon appetit with the time spent`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        repeat(3) { viewModel.next() }
        viewModel.ready()
        repeat(2) { viewModel.next() }
        viewModel.ready()
        repeat(4) { viewModel.next() } // → B's preparation → its two steps → Bon appétit
        settle { viewModel.uiState.value.recipe != null }

        val session = viewModel.uiState.value.session!!
        assertEquals(CookingPhase.DONE, session.phase)
        assertEquals(true, session.finishedAt >= session.startedAt)
        assertEquals(1, session.minutesCooked)
    }

    @Test
    fun `a rotation resumes on the same step with its wait lifted`() = runTest(testDispatcher) {
        val handle = referenceHandle()
        val first = newViewModel(handle)
        ready(first)
        repeat(3) { first.next() }
        first.ready()
        repeat(2) { first.next() }
        assertEquals(5, first.uiState.value.session?.step)

        val second = newViewModel(handle)
        ready(second)

        assertEquals(first.uiState.value.session, second.uiState.value.session)
        assertEquals(5, second.uiState.value.session?.step)
        assertEquals("only the steps not yet forced still wait", listOf(3, 4), second.uiState.value.pending)
        assertEquals(listOf(1), second.uiState.value.session?.ready)
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
        viewModel.ready()
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
    fun `minus one on the last minute rings it at once and its alert shows`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        atStep4(viewModel)
        viewModel.toggleTimer()
        timersRead(viewModel, 3)
        nowMs += 450_000
        viewModel.tick()

        viewModel.addMinutes(-1)
        settle { viewModel.state.alert != null }

        assertEquals("Légumes", viewModel.state.alert?.label)
        assertEquals("Légumes : c'est prêt", doneTitle(viewModel.state.alert!!))
        assertTrue("its step is ready", 4 in viewModel.state.session!!.ready)
    }

    @Test
    fun `a wait says encore with the longest rest of what it waits for, nothing before it starts`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        waterStarted(viewModel)
        viewModel.next()
        assertEquals(listOf(1), viewModel.state.pending)
        nowMs += 10_000
        viewModel.tick()
        assertEquals(590, viewModel.state.awaitedLeft)

        viewModel.ready()
        viewModel.startAndNext()
        settle { viewModel.state.session?.step == 4 }
        viewModel.next()
        assertEquals(listOf(3, 4), viewModel.state.pending)
        assertEquals("the pasta runs, the vegetables are not started", 720, viewModel.state.awaitedLeft)
    }

    @Test
    fun `a timer rung through Mercure shows its alert and frees the step that waits for it`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        waterStarted(viewModel)
        viewModel.next()
        assertEquals(listOf(1), viewModel.state.pending)

        timerEvents.emit(event(server.ring(server.rows.values.single().id)))
        settle { viewModel.state.alert != null }

        assertEquals("Eau", viewModel.state.alert?.label)
        assertEquals(emptyList<Int>(), viewModel.state.pending)
        assertEquals(listOf(1), viewModel.state.session?.ready)
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
        settle { viewModel.state.alert != null }

        assertEquals(before + 2, server.gets)
        assertEquals("Eau", viewModel.state.alert?.label)
    }

    @Test
    fun `OK dismisses the timer, its alert leaves and the next one comes`() = runTest(testDispatcher) {
        val first = server.seed("Eau", REFERENCE, 1, 600).also { nowMs += 1; server.ring(it.id) }
        nowMs += 1_000
        val second = server.seed("Pâtes", REFERENCE, 3, 720).also { server.ring(it.id) }
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        timersRead(viewModel, 2)
        assertEquals(first.id, viewModel.state.alert?.id)
        assertTrue(viewModel.announce(first.id))
        assertFalse("rung once", viewModel.announce(first.id))

        viewModel.dismiss(first.id)

        assertEquals(second.id, viewModel.state.alert?.id)
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

    @Test
    fun `a running timer asks before quitting, and Quitter cancels every timer on screen`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        atStep4(viewModel)
        assertTrue(viewModel.state.confirmQuit)
        assertEquals("2 minuteurs tournent encore (Eau, Pâtes) : ils s'arrêteront.", quitDetail(viewModel.state.timers))

        viewModel.quit()
        settle { server.patches.size == 2 }

        assertTrue(viewModel.state.timers.isEmpty())
        assertEquals(listOf("cancel", "cancel"), server.patches.map { it.second })
        assertTrue(server.rows.values.all { it.status == "cancelled" })
    }

    @Test
    fun `nothing running, nothing to confirm`() = runTest(testDispatcher) {
        val viewModel = newViewModel(referenceHandle())
        ready(viewModel)
        timersRead(viewModel, 0)

        assertFalse(viewModel.state.confirmQuit)
        assertNull(quitDetail(viewModel.state.timers))
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
