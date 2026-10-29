package com.maggie.app.ui.screens.digest

import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.data.model.AgendaHomeState
import com.maggie.app.data.model.AudioAttachment
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.CuisineHomeMeal
import com.maggie.app.data.model.CuisineHomeSlot
import com.maggie.app.data.model.CuisineHomeState
import com.maggie.app.data.model.FinanceHomeState
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.HomeTask
import com.maggie.app.data.model.HomeTasks
import com.maggie.app.data.model.Weather
import com.maggie.app.data.repository.AgendaCardRepository
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.data.repository.CuisineCardRepository
import com.maggie.app.data.repository.FinanceCardRepository
import com.maggie.app.data.repository.TasksCardRepository
import com.maggie.app.data.repository.TripRepository
import com.maggie.app.data.repository.WeatherRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** The Digest du matin (MAG-710): everything read at once, a failed source empties only its card. */
@OptIn(ExperimentalCoroutinesApi::class)
class DigestViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val chat = mockk<ChatRepository>()
    private val weather = mockk<WeatherRepository>()
    private val agenda = mockk<AgendaCardRepository>()
    private val trips = mockk<TripRepository>()
    private val tasks = mockk<TasksCardRepository>()
    private val cuisine = mockk<CuisineCardRepository>()
    private val finance = mockk<FinanceCardRepository>()

    private val day = LocalDate.of(2026, 10, 27)
    private val clock = { Instant.parse("2026-10-27T06:30:00Z") }
    private val message = ChatMessage(id = "m1", role = "assistant", content = "[warmly] Bonjour, voici votre journée.")
    private val other = ChatMessage(id = "m9", role = "user", content = "avant")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { chat.loadMessageContext("m1") } returns listOf(other, message)
        coEvery { weather.read(day) } returns Result.success(Weather(maxC = 9.0, condition = "partly_cloudy"))
        coEvery { agenda.read(HomeDay.TODAY, day) } returns Result.success(
            AgendaHomeState("2026-10-27", listOf(AgendaHomeEvent(id = "e1", summary = "Dentiste"))),
        )
        coEvery { trips.getTrips(day) } returns Result.success(emptyList())
        coEvery { tasks.read(HomeDay.TODAY, day, LocalTime.of(7, 30)) } returns Result.success(
            HomeTasks(tasks = listOf(HomeTask("t1", "Appeler Paul")), total = 1),
        )
        coEvery { cuisine.read(day) } returns Result.success(
            CuisineHomeState(listOf(CuisineHomeSlot("lunch", listOf(CuisineHomeMeal("ml", "Soupe", 2, listOf("Soupe")))), CuisineHomeSlot("dinner"))),
        )
        coEvery { finance.read() } returns Result.success(FinanceHomeState(accountCount = 1))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(id: String? = "m1") = DigestViewModel(id, chat, weather, agenda, trips, tasks, cuisine, finance, clock)

    @Test
    fun `everything loads in one read and every card has something to say`() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertFalse(ui.isLoading)
        assertEquals(day, ui.date)
        assertEquals(message, ui.message)
        assertNull(ui.voice)
        assertTrue(ui.showsWeather && ui.showsAgenda && ui.showsTasks && ui.showsCuisine && ui.showsFinance)
    }

    @Test
    fun `a weather in error leaves the card out and the others stay`() = runTest(dispatcher) {
        coEvery { weather.read(day) } returns Result.failure(RuntimeException("no city"))
        val viewModel = viewModel()

        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertNull(ui.weather)
        assertFalse(ui.showsWeather)
        assertTrue(ui.showsAgenda && ui.showsTasks)
    }

    @Test
    fun `a card with nothing to say is hidden`() = runTest(dispatcher) {
        coEvery { agenda.read(HomeDay.TODAY, day) } returns Result.success(AgendaHomeState("2026-10-27"))
        coEvery { tasks.read(HomeDay.TODAY, day, LocalTime.of(7, 30)) } returns Result.success(HomeTasks())
        coEvery { cuisine.read(day) } returns Result.success(CuisineHomeState(listOf(CuisineHomeSlot("lunch"), CuisineHomeSlot("dinner"))))
        coEvery { finance.read() } returns Result.success(FinanceHomeState())
        val viewModel = viewModel()

        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertTrue(ui.showsWeather)
        assertFalse(ui.showsAgenda || ui.showsTasks || ui.showsCuisine || ui.showsFinance)
    }

    @Test
    fun `a source that fails empties its own card only`() = runTest(dispatcher) {
        coEvery { tasks.read(HomeDay.TODAY, day, LocalTime.of(7, 30)) } returns Result.failure(RuntimeException("down"))
        coEvery { trips.getTrips(day) } returns Result.failure(RuntimeException("down"))
        val viewModel = viewModel()

        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertFalse(ui.isLoading)
        assertFalse(ui.showsTasks)
        assertTrue(ui.trips.isEmpty())
        assertTrue(ui.showsAgenda && ui.showsCuisine && ui.showsFinance && ui.showsWeather)
    }

    @Test
    fun `a message that cannot be read leaves the cards alone`() = runTest(dispatcher) {
        coEvery { chat.loadMessageContext("m1") } throws RuntimeException("offline")
        val viewModel = viewModel()

        advanceUntilIdle()

        assertNull(viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.showsWeather)
    }

    @Test
    fun `the vocal sent next to the written digest is found, before or after it`() = runTest(dispatcher) {
        val vocal = ChatMessage(id = "m0", role = "assistant", content = "[warmly] Bonjour", audio = AudioAttachment(durationMs = 9_000))
        coEvery { chat.loadMessageContext("m1") } returns listOf(vocal, message)
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(vocal, viewModel.uiState.value.voice)
        assertEquals(message, viewModel.uiState.value.message)

        coEvery { chat.loadMessageContext("m0") } returns listOf(vocal, message)
        val fromTheVocal = viewModel("m0")
        advanceUntilIdle()
        assertEquals(vocal, fromTheVocal.uiState.value.voice)
        assertEquals(message, fromTheVocal.uiState.value.message)
    }

    @Test
    fun `without a link there is no message`() = runTest(dispatcher) {
        val viewModel = viewModel(id = null)

        advanceUntilIdle()

        assertNull(viewModel.uiState.value.message)
    }
}
