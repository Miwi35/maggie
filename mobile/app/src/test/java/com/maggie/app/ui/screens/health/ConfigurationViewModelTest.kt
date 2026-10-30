package com.maggie.app.ui.screens.health

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.ActivityLevel
import com.maggie.app.data.model.HealthProfile
import com.maggie.app.data.repository.HealthProfileRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
                else -> p
            }
        }
        return p.recomputed()
    }

    private fun TestScope.loaded(): ConfigurationViewModel {
        val viewModel = ConfigurationViewModel(repository, mercureService, authRepository, clock)
        advanceUntilIdle()
        return viewModel
    }

    private fun ConfigurationViewModel.shown() = uiState.value.profile!!

    @Test
    fun `loading, then the profile with its need and basal metabolism`() = runTest {
        val viewModel = ConfigurationViewModel(repository, mercureService, authRepository, clock)
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
}
