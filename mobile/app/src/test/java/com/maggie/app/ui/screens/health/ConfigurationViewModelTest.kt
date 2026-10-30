package com.maggie.app.ui.screens.health

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.health.FakeHealthConnectGateway
import com.maggie.app.data.health.FakeHealthSyncScheduler
import com.maggie.app.data.health.HealthConnectAvailability
import com.maggie.app.data.health.HealthConnectPermissions
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.ActivityLevel
import com.maggie.app.data.model.HealthProfile
import com.maggie.app.data.repository.HealthProfileRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** « Santé › Configuration » (MAG-707): one test per transition. Today is in 2026. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<HealthProfileRepository>()
    private val authRepository = mockk<AuthRepository>()
    private val mercureService = mockk<MercureService>()
    private val pushes = MutableSharedFlow<MercureEvent>()
    private val clock = Clock.fixed(Instant.parse("2026-10-30T08:00:00Z"), ZoneId.of("Europe/Paris"))
    private val sent = mutableListOf<JsonObject>()
    private val gateway = FakeHealthConnectGateway()
    private val scheduler = FakeHealthSyncScheduler()

    /** The ticket's figures: Homme, 38 ans, 178 cm, 72 kg, Sédentaire → 1 648 and 1 980 kcal. */
    private val profile = HealthProfile(
        id = "01PROFILE",
        sex = "male",
        birthYear = 1988,
        age = 38,
        heightCm = 178,
        weightKg = 72.0,
        activityLevel = "sedentary",
        mode = "computed",
        bmrKcal = 1648,
        needKcal = 1980,
        factor = 1.2,
    )

    /** The profile as the fake API holds it, each write laid over the last. */
    private lateinit var server: HealthProfile

    @Before
    fun setup() {
        server = profile
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.getProfile() } returns Result.success(profile)
        coEvery { repository.update(any()) } answers {
            val changes = firstArg<JsonObject>()
            sent += changes
            server = apply(server, changes)
            Result.success(server)
        }
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns pushes
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** What the API would answer: the fields written, the figures recomputed. */
    private fun apply(base: HealthProfile, changes: JsonObject): HealthProfile {
        var p = base
        changes.forEach { (key, value) ->
            val v = (value as JsonPrimitive).content
            p = when (key) {
                "heightCm" -> p.copy(heightCm = v.toInt())
                "birthYear" -> p.copy(birthYear = v.toInt(), age = 2026 - v.toInt())
                "weightKg" -> p.copy(weightKg = v.toDouble())
                "mode" -> p.copy(mode = v)
                "enteredNeedKcal" -> p.copy(enteredNeedKcal = v.toInt())
                "activityLevel" -> p.copy(activityLevel = v)
                "stepGoal" -> p.copy(stepGoal = v.toInt())
                "stepSource" -> p.copy(stepSource = v)
                else -> p
            }
        }
        return p.recomputed()
    }

    private fun TestScope.loaded(): ConfigurationViewModel {
        val viewModel = ConfigurationViewModel(repository, mercureService, authRepository, gateway, scheduler, clock)
        advanceUntilIdle()
        return viewModel
    }

    /** A fake API that answers each write only when the test says so, and counts the writes it is busy with. */
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private var busy = 0
    private var mostBusy = 0

    private fun gatedServer() {
        coEvery { repository.update(any()) } coAnswers {
            val changes = firstArg<JsonObject>()
            sent += changes
            busy++
            mostBusy = maxOf(mostBusy, busy)
            val gate = CompletableDeferred<Unit>().also { gates += it }
            gate.await()
            busy--
            server = apply(server, changes)
            Result.success(server)
        }
    }

    private fun ConfigurationViewModel.shown() = uiState.value.profile!!

    @Test
    fun `loading, then the profile with its need and basal metabolism`() = runTest {
        val viewModel = ConfigurationViewModel(repository, mercureService, authRepository, gateway, scheduler, clock)
        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasLoaded)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasLoaded)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(1980, viewModel.shown().needKcal)
        assertEquals(1648, viewModel.shown().bmrKcal)
    }

    @Test
    fun `a profile never written has no need, and the first tap starts from 30 years`() = runTest {
        coEvery { repository.getProfile() } returns Result.success(HealthProfile())
        val viewModel = loaded()
        assertNull(viewModel.shown().id)
        assertNull(viewModel.shown().needKcal)
        assertNull(viewModel.shown().bmrKcal)

        viewModel.stepAge(+1)

        assertEquals(31, viewModel.shown().age)
        assertNull("still no need without sex, height and weight", viewModel.shown().needKcal)
    }

    @Test
    fun `a load that fails says so`() = runTest {
        coEvery { repository.getProfile() } returns Result.failure(RuntimeException("boom"))

        val viewModel = loaded()

        assertNull(viewModel.uiState.value.profile)
        assertFalse(viewModel.uiState.value.hasLoaded)
        assertEquals(ConfigurationViewModel.MESSAGE_LOAD, viewModel.uiState.value.error)
    }

    @Test
    fun `quick taps on a stepper show at once and send one PATCH of its field after 600 ms`() = runTest {
        val viewModel = loaded()

        viewModel.stepHeight(+1)
        advanceTimeBy(300)
        viewModel.stepHeight(+1)
        runCurrent()
        assertEquals(180, viewModel.shown().heightCm)
        assertEquals("the card follows at once", 1660, viewModel.shown().bmrKcal)
        assertTrue("nothing sent before the pause", sent.isEmpty())

        advanceTimeBy(PROFILE_WRITE_DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(listOf(JsonObject(mapOf("heightCm" to JsonPrimitive(180)))), sent)
        assertEquals(180, viewModel.shown().heightCm)
    }

    @Test
    fun `the age is written as the birth year`() = runTest {
        val viewModel = loaded()

        viewModel.stepAge(-1)
        advanceUntilIdle()

        assertEquals(listOf(JsonObject(mapOf("birthYear" to JsonPrimitive(1989)))), sent)
        assertEquals(37, viewModel.shown().age)
    }

    @Test
    fun `the weight steps a kilo from the value shown and is sent as weightKg`() = runTest {
        server = profile.copy(weightKg = 72.4)
        coEvery { repository.getProfile() } returns Result.success(server)
        val viewModel = loaded()

        viewModel.stepWeight(+1)
        assertEquals(73.4, viewModel.shown().weightKg!!, 0.001)
        advanceUntilIdle()

        assertEquals(listOf(JsonObject(mapOf("weightKg" to JsonPrimitive(73.4)))), sent)
    }

    @Test
    fun `Calculé to Saisi types the need shown, rounded to 50, and back`() = runTest {
        val viewModel = loaded()

        viewModel.setEntered(true)
        advanceUntilIdle()
        assertEquals(JsonObject(mapOf("mode" to JsonPrimitive("entered"), "enteredNeedKcal" to JsonPrimitive(2000))), sent.last())
        assertTrue(viewModel.shown().isEntered)
        assertEquals(2000, viewModel.shown().needKcal)

        viewModel.stepEnteredNeed(-1)
        advanceUntilIdle()
        assertEquals(JsonObject(mapOf("enteredNeedKcal" to JsonPrimitive(1950))), sent.last())
        assertEquals(1950, viewModel.shown().needKcal)

        viewModel.setEntered(false)
        advanceUntilIdle()
        assertEquals(JsonObject(mapOf("mode" to JsonPrimitive("computed"))), sent.last())
        assertFalse(viewModel.shown().isEntered)
        assertEquals(1980, viewModel.shown().needKcal)
    }

    @Test
    fun `an activity chip is written at once and moves the need`() = runTest {
        val viewModel = loaded()

        viewModel.setActivityLevel(ActivityLevel.MODERATE)
        runCurrent()

        assertEquals(JsonObject(mapOf("activityLevel" to JsonPrimitive("moderate"))), sent.single())
        assertEquals(2550, viewModel.shown().needKcal)
        assertEquals(1.55, viewModel.shown().factor!!, 0.001)
    }

    @Test
    fun `the step goal steps by 500`() = runTest {
        val viewModel = loaded()

        viewModel.stepStepGoal(+1)
        viewModel.stepStepGoal(+1)
        advanceUntilIdle()

        assertEquals(listOf(JsonObject(mapOf("stepGoal" to JsonPrimitive(9000)))), sent)
    }

    @Test
    fun `a refused write says so and puts the API's value back`() = runTest {
        coEvery { repository.update(any()) } returns Result.failure(RuntimeException("400"))
        val viewModel = loaded()

        viewModel.stepHeight(+1)
        assertEquals(179, viewModel.shown().heightCm)
        advanceUntilIdle()

        assertEquals(178, viewModel.shown().heightCm)
        assertEquals(1648, viewModel.shown().bmrKcal)
        assertEquals(ConfigurationViewModel.MESSAGE_SAVE, viewModel.uiState.value.error)
    }

    @Test
    fun `it follows the health-profile topic, whose payload replaces the profile`() = runTest {
        val viewModel = loaded()
        verify { mercureService.subscribe("/users/user-1/api/health-profile/{id}") }

        pushes.emit(
            MercureEvent(
                data = """{"@id":"/api/health-profile/01PROFILE","id":"01PROFILE","sex":"male","birthYear":1988,"age":38,""" +
                    """"heightCm":178,"weightKg":71.0,"activityLevel":"sedentary","mode":"computed","enteredNeedKcal":null,""" +
                    """"bmrKcal":1638,"needKcal":1970,"factor":1.2,"stepGoal":8000,"suggestionWindowStart":"08:00",""" +
                    """"suggestionWindowEnd":"20:00","sessionMinutes":20,"stepSource":"none"}""",
            ),
        )
        advanceUntilIdle()

        assertEquals(71.0, viewModel.shown().weightKg!!, 0.001)
        assertEquals(1970, viewModel.shown().needKcal)
        coVerify(exactly = 1) { repository.getProfile() }
    }

    @Test
    fun `two choices made within one request's latency are sent one after the other`() = runTest {
        coEvery { repository.getProfile() } returns Result.success(HealthProfile())
        server = HealthProfile()
        val viewModel = loaded()
        gatedServer()

        viewModel.setSex("female")
        viewModel.setActivityLevel(ActivityLevel.MODERATE)
        runCurrent()
        assertEquals("the second waits for the first's answer", 1, sent.size)

        gates[0].complete(Unit)
        runCurrent()
        assertEquals(2, sent.size)
        gates[1].complete(Unit)
        advanceUntilIdle()

        assertEquals(1, mostBusy)
        assertEquals(
            listOf(
                JsonObject(mapOf("sex" to JsonPrimitive("female"))),
                JsonObject(mapOf("activityLevel" to JsonPrimitive("moderate"))),
            ),
            sent,
        )
    }

    @Test
    fun `the answer to an older write does not replace what the last one shows`() = runTest {
        val viewModel = loaded()
        gatedServer()

        viewModel.setActivityLevel(ActivityLevel.MODERATE)
        viewModel.setActivityLevel(ActivityLevel.ACTIVE)
        runCurrent()
        assertEquals("active", viewModel.shown().activityLevel)

        gates[0].complete(Unit)
        runCurrent()
        assertEquals("the first answer (moderate) is not shown", "active", viewModel.shown().activityLevel)

        gates[1].complete(Unit)
        advanceUntilIdle()
        assertEquals("active", viewModel.shown().activityLevel)
        assertEquals("active", server.activityLevel)
    }

    @Test
    fun `an edit waiting for its pause is sent when the view model is cleared`() = runTest {
        val store = ViewModelStore()
        val built = ConfigurationViewModel(repository, mercureService, authRepository, gateway, scheduler, clock)
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = built as T
            },
        )[ConfigurationViewModel::class.java]
        advanceUntilIdle()

        viewModel.stepHeight(+1)
        viewModel.stepStepGoal(+1)
        advanceTimeBy(100)
        store.clear()
        advanceUntilIdle()

        assertEquals(
            listOf(JsonObject(mapOf("heightCm" to JsonPrimitive(179), "stepGoal" to JsonPrimitive(8500)))),
            sent,
        )
        assertEquals(179, server.heightCm)
    }

    @Test
    fun `a write already on its way is not cancelled when the view model is cleared`() = runTest {
        val store = ViewModelStore()
        val built = ConfigurationViewModel(repository, mercureService, authRepository, gateway, scheduler, clock)
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = built as T
            },
        )[ConfigurationViewModel::class.java]
        advanceUntilIdle()
        gatedServer()

        viewModel.setActivityLevel(ActivityLevel.MODERATE)
        runCurrent()
        store.clear()
        gates[0].complete(Unit)
        advanceUntilIdle()

        assertEquals("moderate", server.activityLevel)
    }

    // — « Source des pas » and Health Connect (MAG-708)

    @Test
    fun `every read permission granted shows Health Connect as connected`() = runTest {
        val viewModel = loaded()

        assertEquals(HealthConnectState.CONNECTED, viewModel.uiState.value.healthConnect)
    }

    @Test
    fun `a read permission refused shows Non connecté, and nothing is synced`() = runTest {
        gateway.granted = HealthConnectPermissions.READ - HealthConnectPermissions.READ_WEIGHT

        val viewModel = loaded()

        assertEquals(HealthConnectState.NOT_PERMITTED, viewModel.uiState.value.healthConnect)
        assertFalse(viewModel.uiState.value.healthConnect.isConnected)
        assertEquals(0, scheduler.now)
    }

    @Test
    fun `Health Connect not installed or too old is not connected either`() = runTest {
        gateway.available = HealthConnectAvailability.NEEDS_UPDATE

        val viewModel = loaded()

        assertEquals(HealthConnectState.NOT_INSTALLED, viewModel.uiState.value.healthConnect)
    }

    @Test
    fun `the permissions granted connect, set the steps' source to Health Connect and ask for a sync now`() = runTest {
        gateway.granted = emptySet()
        val viewModel = loaded()

        viewModel.onPermissionsResult(HealthConnectPermissions.ALL)
        advanceUntilIdle()

        assertEquals(HealthConnectState.CONNECTED, viewModel.uiState.value.healthConnect)
        assertEquals(listOf(JsonObject(mapOf("stepSource" to JsonPrimitive("health_connect")))), sent)
        assertEquals(HealthProfile.STEP_SOURCE_HEALTH_CONNECT, viewModel.uiState.value.profile?.stepSource)
        assertEquals(1, scheduler.now)
    }

    @Test
    fun `the permission screen closed without the reads stays Non connecté and writes nothing`() = runTest {
        gateway.granted = emptySet()
        val viewModel = loaded()

        viewModel.onPermissionsResult(setOf(HealthConnectPermissions.READ_STEPS))
        advanceUntilIdle()

        assertEquals(HealthConnectState.NOT_PERMITTED, viewModel.uiState.value.healthConnect)
        assertTrue(sent.isEmpty())
        assertEquals(0, scheduler.now)
    }

    @Test
    fun `back on the screen after the settings, the permissions are read again`() = runTest {
        val viewModel = loaded()
        gateway.granted = emptySet()

        viewModel.checkHealthConnect()
        advanceUntilIdle()

        assertEquals(HealthConnectState.NOT_PERMITTED, viewModel.uiState.value.healthConnect)
    }
}
