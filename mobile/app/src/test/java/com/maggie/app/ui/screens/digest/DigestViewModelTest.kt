package com.maggie.app.ui.screens.digest

import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.data.model.AgendaHomeState
import com.maggie.app.data.model.AudioAttachment
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.CuisineHomeMeal
import com.maggie.app.data.model.CuisineHomeSlot
import com.maggie.app.data.model.CuisineHomeState
import com.maggie.app.data.model.DigestBlock
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.HomeTask
import com.maggie.app.data.model.HomeTasks
import com.maggie.app.data.model.Weather
import com.maggie.app.data.repository.AgendaCardRepository
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.data.repository.CuisineCardRepository
import com.maggie.app.data.repository.TasksCardRepository
import com.maggie.app.data.repository.TripRepository
import com.maggie.app.data.repository.WeatherRepository
import io.mockk.coEvery
import io.mockk.coVerify
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
        coEvery { chat.markDigestOpened(any()) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(id: String? = "m1") = DigestViewModel(id, chat, weather, agenda, trips, tasks, cuisine, clock)

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
        assertTrue(ui.showsWeather && ui.showsAgenda && ui.showsTasks && ui.showsCuisine)
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
        val viewModel = viewModel()

        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertTrue(ui.showsWeather)
        assertFalse(ui.showsAgenda || ui.showsTasks || ui.showsCuisine)
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
        assertTrue(ui.showsAgenda && ui.showsCuisine && ui.showsWeather)
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
    fun `a single digest message is both the text and the vocal`() = runTest(dispatcher) {
        val single = ChatMessage(id = "m1", role = "assistant", content = "Bonjour monsieur.", audio = AudioAttachment(durationMs = 9_000))
        val other = ChatMessage(id = "m0", role = "user", content = "Merci")
        coEvery { chat.loadMessageContext("m1") } returns listOf(other, single)

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(single, viewModel.uiState.value.message)
        assertEquals(single, viewModel.uiState.value.voice)
    }

    private val block = DigestBlock(day = "2026-10-27", text = "Voici votre journée, monsieur.", expiresAt = "2026-10-27T14:00:00+01:00")
    private val announcement = ChatMessage(
        id = "m1",
        role = "assistant",
        content = "Votre digest du matin est disponible.",
        audio = AudioAttachment(durationMs = 72_000),
        blocks = listOf(block),
    )

    @Test
    fun `the text comes from the block, the vocal is the message, and the first opening plays and marks`() = runTest(dispatcher) {
        coEvery { chat.loadMessageContext("m1") } returns listOf(other, announcement)

        val viewModel = viewModel()
        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertEquals("Voici votre journée, monsieur.", ui.text)
        assertEquals(announcement, ui.voice)
        assertTrue(ui.autoPlay)
        coVerify(exactly = 1) { chat.markDigestOpened("m1") }
    }

    @Test
    fun `a later opening neither plays nor marks again`() = runTest(dispatcher) {
        val opened = announcement.copy(blocks = listOf(block.copy(openedAt = "2026-10-27T06:31:00Z")))
        coEvery { chat.loadMessageContext("m1") } returns listOf(other, opened)

        val viewModel = viewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.autoPlay)
        assertEquals("Voici votre journée, monsieur.", viewModel.uiState.value.text)
        coVerify(exactly = 0) { chat.markDigestOpened(any()) }
    }

    @Test
    fun `a marking that fails keeps the screen`() = runTest(dispatcher) {
        coEvery { chat.loadMessageContext("m1") } returns listOf(other, announcement)
        coEvery { chat.markDigestOpened("m1") } throws RuntimeException("offline")

        val viewModel = viewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("Voici votre journée, monsieur.", viewModel.uiState.value.text)
        assertTrue(viewModel.uiState.value.autoPlay)
    }

    @Test
    fun `a digest from before the block reads its content and does not play by itself`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals("[warmly] Bonjour, voici votre journée.", viewModel.uiState.value.text)
        assertFalse(viewModel.uiState.value.autoPlay)
        coVerify(exactly = 0) { chat.markDigestOpened(any()) }
    }

    @Test
    fun `without a link there is no message`() = runTest(dispatcher) {
        val viewModel = viewModel(id = null)

        advanceUntilIdle()

        assertNull(viewModel.uiState.value.message)
    }
}
