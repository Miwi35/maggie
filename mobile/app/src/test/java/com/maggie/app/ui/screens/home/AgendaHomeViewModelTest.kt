package com.maggie.app.ui.screens.home

import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.repository.HomeCardRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** The Agenda card's state (MAG-562): one test per transition — read, another day, refresh, failure, fiche opened and closed. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgendaHomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<HomeCardRepository>()
    private val changes = MutableSharedFlow<Unit>()
    private var elapsed = 1_000L

    private fun answer(vararg ids: String, date: String = "2026-10-14", now: String? = "2026-10-14T14:12:00+02:00"): JsonObject {
        val events = ids.joinToString(",") {
            """{"id":"$it","summary":"Événement $it","startAt":"2026-10-14T15:00:00+02:00","endAt":"2026-10-14T16:00:00+02:00","timeZone":"Europe/Paris"}"""
        }
        val nowJson = now?.let { "\"$it\"" } ?: "null"
        return Json.parseToJsonElement("""{"module":"agenda","date":"$date","now":$nowJson,"events":[$events]}""") as JsonObject
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.changes("agenda") } returns changes
        coEvery { repository.state("agenda", HomeDay.TODAY) } returns Result.success(answer("a", "b"))
        coEvery { repository.state("agenda", HomeDay.TOMORROW) } returns Result.success(answer("t", date = "2026-10-15", now = null))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = AgendaHomeViewModel(repository, elapsedRealtime = { elapsed })

    @Test
    fun `it is loading until the first answer`() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertTrue(viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.state)
    }

    @Test
    fun `showing a day reads it and keeps the rows`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertFalse(ui.loading)
        assertFalse(ui.error)
        assertEquals(listOf("a", "b"), ui.state?.events?.map { it.id })
    }

    @Test
    fun `another day drops the rows of the first and reads the new one`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        viewModel.show(HomeDay.TOMORROW)

        assertNull(viewModel.uiState.value.state)
        assertTrue(viewModel.uiState.value.loading)
        advanceUntilIdle()
        assertEquals(listOf("t"), viewModel.uiState.value.state?.events?.map { it.id })
        assertNull(viewModel.nowAt(viewModel.uiState.value))
    }

    @Test
    fun `an answer that comes back after the day changed is ignored`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.show(HomeDay.TODAY)
        viewModel.show(HomeDay.TOMORROW)
        advanceUntilIdle()

        assertEquals(HomeDay.TOMORROW, viewModel.uiState.value.day)
        assertEquals(listOf("t"), viewModel.uiState.value.state?.events?.map { it.id })
    }

    @Test
    fun `Mercure says the card moved, it is read again without blanking it`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()
        coEvery { repository.state("agenda", HomeDay.TODAY) } returns Result.success(answer("a", "b", "c"))

        changes.emit(Unit)
        assertEquals(listOf("a", "b"), viewModel.uiState.value.state?.events?.map { it.id })
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), viewModel.uiState.value.state?.events?.map { it.id })
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `a failing read shows the error, retry reads again`() = runTest(dispatcher) {
        coEvery { repository.state("agenda", HomeDay.TODAY) } returns Result.failure(RuntimeException("503"))
        val viewModel = viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loading)

        coEvery { repository.state("agenda", HomeDay.TODAY) } returns Result.success(answer("a"))
        viewModel.retry()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.error)
        assertEquals(listOf("a"), viewModel.uiState.value.state?.events?.map { it.id })
    }

    @Test
    fun `a refresh that fails keeps what the card shows`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()
        coEvery { repository.state("agenda", HomeDay.TODAY) } returns Result.failure(RuntimeException("503"))

        changes.emit(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.error)
        assertEquals(listOf("a", "b"), viewModel.uiState.value.state?.events?.map { it.id })
    }

    @Test
    fun `touching an event opens its fiche, closing it shuts it`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        viewModel.open("b")
        assertEquals("Événement b", viewModel.uiState.value.opened?.summary)

        viewModel.close()
        assertNull(viewModel.uiState.value.opened)
    }

    @Test
    fun `the fiche follows a refresh and vanishes with a deleted event`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()
        viewModel.open("b")

        coEvery { repository.state("agenda", HomeDay.TODAY) } returns Result.success(answer("a"))
        changes.emit(Unit)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.opened)
    }

    @Test
    fun `the line moves on with the time elapsed since the read`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()
        val ui = viewModel.uiState.value
        assertEquals(Instant.parse("2026-10-14T12:12:00Z"), viewModel.nowAt(ui))

        elapsed += 5 * 60_000

        assertEquals(Instant.parse("2026-10-14T12:17:00Z"), viewModel.nowAt(ui))
        assertNotNull(ui.state)
    }

    @Test
    fun `nothing is read before a day is shown`() = runTest(dispatcher) {
        viewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.state(any(), any()) }
    }
}
