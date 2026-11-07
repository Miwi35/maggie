package com.maggie.app.ui.screens.health

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.HealthDay
import com.maggie.app.data.repository.HealthDayRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** « Santé › Journal » (MAG-750): one test per transition — load, its failure, day changed, scale, note, refusal, Mercure. */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<HealthDayRepository>()
    private val authRepository = mockk<AuthRepository>()
    private val mercureService = mockk<MercureService>()
    private val pushes = MutableSharedFlow<MercureEvent>()
    // Saturday 7 November 2026, 21:30 in Paris.
    private val clock = Clock.fixed(Instant.parse("2026-11-07T20:30:00Z"), ZoneId.of("Europe/Paris"))

    private val today = HealthDay(id = "d7", date = "2026-11-07", weightKg = 71.6, weighedAt = "2026-11-07T07:12:00+01:00", steps = 8000)
    private val friday = HealthDay(id = "d6", date = "2026-11-06", mood = 6, fatigue = 5, note = "Bonne journée")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.getDay("2026-11-07") } returns Result.success(today)
        coEvery { repository.getDay("2026-11-06") } returns Result.success(friday)
        coEvery { repository.getDays(any(), any()) } returns Result.success(listOf(friday, today))
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns pushes
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = JournalViewModel(repository, mercureService, authRepository, clock)

    @Test
    fun `loading, then today's entry and the journal's last thirty days`() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasLoaded)
        assertEquals(LocalDate.parse("2026-11-07"), viewModel.uiState.value.date)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.hasLoaded)
        assertEquals(today, state.entry)
        assertEquals(listOf(friday, today), state.days)
        assertNull(state.error)
        coVerify { repository.getDay("2026-11-07") }
        coVerify { repository.getDays("2026-10-09", "2026-11-07") }
    }

    @Test
    fun `a failed load says so in French and shows no entry`() = runTest {
        coEvery { repository.getDay(any()) } returns Result.failure(RuntimeException("offline"))
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.hasLoaded)
        assertNull(state.entry)
        assertEquals(JournalViewModel.MESSAGE_LOAD, state.error)
    }

    @Test
    fun `a past day chosen shows what the journal holds at once, then the API's entry`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        val fresh = friday.copy(steps = 6400)
        val answer = CompletableDeferred<Result<HealthDay>>()
        coEvery { repository.getDay("2026-11-06") } coAnswers { answer.await() }

        viewModel.selectDate(LocalDate.parse("2026-11-06"))

        assertEquals(LocalDate.parse("2026-11-06"), viewModel.uiState.value.date)
        assertEquals(friday, viewModel.uiState.value.entry)
        answer.complete(Result.success(fresh))
        advanceUntilIdle()
        assertEquals(fresh, viewModel.uiState.value.entry)
    }

    @Test
    fun `the arrows move a day, never past today`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.nextDay()
        assertEquals(LocalDate.parse("2026-11-07"), viewModel.uiState.value.date)

        viewModel.previousDay()
        advanceUntilIdle()
        assertEquals(LocalDate.parse("2026-11-06"), viewModel.uiState.value.date)
        assertEquals(friday, viewModel.uiState.value.entry)

        viewModel.nextDay()
        advanceUntilIdle()
        assertEquals(LocalDate.parse("2026-11-07"), viewModel.uiState.value.date)
        assertEquals(today, viewModel.uiState.value.entry)
    }

    @Test
    fun `a pastille of Moral shows at once and sends the PATCH, the API's entry replaces it`() = runTest {
        val saved = today.copy(mood = 7, notedBy = "user", notedAt = "2026-11-07T21:30:00+01:00")
        coEvery { repository.setMood("2026-11-07", 7) } returns Result.success(saved)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.pickMood(7)
        assertEquals(7, viewModel.uiState.value.entry!!.mood)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.setMood("2026-11-07", 7) }
        assertEquals(saved, viewModel.uiState.value.entry)
        assertEquals(saved, viewModel.uiState.value.days.last())
    }

    @Test
    fun `a pastille of Fatigue sends the PATCH, and the one already chosen empties the scale`() = runTest {
        coEvery { repository.setFatigue("2026-11-07", 4) } returns Result.success(today.copy(fatigue = 4))
        coEvery { repository.setFatigue("2026-11-07", null) } returns Result.success(today)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.pickFatigue(4)
        advanceUntilIdle()
        assertEquals(4, viewModel.uiState.value.entry!!.fatigue)

        viewModel.pickFatigue(4)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.setFatigue("2026-11-07", null) }
        assertNull(viewModel.uiState.value.entry!!.fatigue)
    }

    @Test
    fun `two pastilles tapped in a row are sent one after the other, and the entry ends with both`() = runTest {
        val moodAnswer = CompletableDeferred<Result<HealthDay>>()
        coEvery { repository.setMood("2026-11-07", 7) } coAnswers { moodAnswer.await() }
        coEvery { repository.setFatigue("2026-11-07", 4) } returns Result.success(today.copy(mood = 7, fatigue = 4))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.pickMood(7)
        viewModel.pickFatigue(4)
        advanceUntilIdle()

        // The fatigue waits for the mood's answer; both show meanwhile.
        coVerify(exactly = 0) { repository.setFatigue(any(), any()) }
        assertEquals(7, viewModel.uiState.value.entry!!.mood)
        assertEquals(4, viewModel.uiState.value.entry!!.fatigue)

        moodAnswer.complete(Result.success(today.copy(mood = 7)))
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.setFatigue("2026-11-07", 4) }
        assertEquals(7, viewModel.uiState.value.entry!!.mood)
        assertEquals(4, viewModel.uiState.value.entry!!.fatigue)
    }

    @Test
    fun `a scale refused puts the previous value back with the API's reason`() = runTest {
        coEvery { repository.setMood(any(), any()) } returns Result.failure(ApiRefusalException(400, "mood: must be between 1 and 10."))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.pickMood(7)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.entry!!.mood)
        assertEquals("mood: must be between 1 and 10.", viewModel.uiState.value.error)
    }

    @Test
    fun `a scale that fails without a reason says so in French`() = runTest {
        coEvery { repository.setMood(any(), any()) } returns Result.failure(RuntimeException("offline"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.pickMood(3)
        advanceUntilIdle()

        assertEquals(JournalViewModel.MESSAGE_SCALE, viewModel.uiState.value.error)
    }

    @Test
    fun `Enregistrer sends the note trimmed and says it is saved`() = runTest {
        val saved = today.copy(note = "Grosse séance")
        coEvery { repository.setNote("2026-11-07", "Grosse séance") } returns Result.success(saved)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.saveNote("  Grosse séance ")
        assertTrue(viewModel.uiState.value.savingNote)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        coVerify(exactly = 1) { repository.setNote("2026-11-07", "Grosse séance") }
        assertFalse(state.savingNote)
        assertEquals("Grosse séance", state.entry!!.note)
        assertEquals(JournalViewModel.MESSAGE_NOTE_SAVED, state.message)
    }

    @Test
    fun `a blank note empties it`() = runTest {
        coEvery { repository.setNote("2026-11-06", null) } returns Result.success(friday.copy(note = null))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectDate(LocalDate.parse("2026-11-06"))
        advanceUntilIdle()

        viewModel.saveNote("   ")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.setNote("2026-11-06", null) }
        assertNull(viewModel.uiState.value.entry!!.note)
    }

    @Test
    fun `a note that fails keeps the one saved and says so`() = runTest {
        coEvery { repository.setNote(any(), any()) } returns Result.failure(RuntimeException("offline"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.saveNote("Grosse séance")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.savingNote)
        assertNull(state.entry!!.note)
        assertEquals(JournalViewModel.MESSAGE_NOTE, state.error)
    }

    @Test
    fun `a day pushed on the health-days topic reads the entry again`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        val fromMaggie = today.copy(mood = 8, notedBy = "maggie", notedAt = "2026-11-07T21:42:00+01:00")
        coEvery { repository.getDay("2026-11-07") } returns Result.success(fromMaggie)

        pushes.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        verify { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.HEALTH_DAYS)) }
        assertEquals(fromMaggie, viewModel.uiState.value.entry)
    }
}
