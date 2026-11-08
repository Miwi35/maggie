package com.maggie.app.ui.screens.bilan

import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.data.model.AgendaHomeState
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.repository.AgendaCardRepository
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.TripRepository
import com.maggie.app.data.repository.WorkoutRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** The Demain step (MAG-755): the plan loads, an answer goes to Maggie, then tomorrow is read again. */
@OptIn(ExperimentalCoroutinesApi::class)
class BilanTomorrowViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val agenda = mockk<AgendaCardRepository>()
    private val trips = mockk<TripRepository>()
    private val meals = mockk<MealRepository>()
    private val workouts = mockk<WorkoutRepository>()
    private val chat = mockk<ChatRepository>()
    private val dentist = AgendaHomeEvent(
        id = "01HZX3Q8R5T6V7W8X9Y0Z1A2B3",
        summary = "Dentiste",
        location = "Cabinet Martin",
        locationShort = "Cabinet Martin",
        startAt = "2026-11-09T14:00:00+01:00",
        endAt = "2026-11-09T15:00:00+01:00",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { agenda.read(any(), any()) } returns Result.success(AgendaHomeState("2026-11-09", listOf(dentist)))
        coEvery { trips.getTrips(any()) } returns Result.success(emptyList())
        coEvery { meals.getMeals(any(), any()) } returns Result.success(emptyList())
        coEvery { workouts.getWorkouts() } returns Result.success(emptyList())
        coEvery { chat.sendMessage(any()) } returns listOf(ChatMessage(role = "assistant", content = "C'est noté."))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = BilanTomorrowViewModel(agenda, trips, meals, workouts, chat) { Instant.parse("2026-11-08T20:40:00Z") }

    @Test
    fun `the plan of tomorrow loads with its first question`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        val plan = vm.uiState.value.plan!!
        assertEquals("2026-11-09", plan.day.toString())
        assertEquals(TripQuestionKind.ORIGIN, plan.nextQuestion(emptySet())!!.kind)
    }

    @Test
    fun `an answer is sent to Maggie then reported as answered`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val question = vm.uiState.value.plan!!.nextQuestion(emptySet())!!
        val recorded = mutableListOf<String>()

        vm.answer(question, question.choices.first()) { recorded += it }
        advanceUntilIdle()

        coVerify { chat.sendMessage(question.choices.first().message!!) }
        assertEquals(listOf(question.key), recorded)
        assertFalse(vm.uiState.value.sending)
    }

    @Test
    fun `a failed send records nothing and says so`() = runTest(testDispatcher) {
        coEvery { chat.sendMessage(any()) } throws RuntimeException("offline")
        val vm = viewModel()
        advanceUntilIdle()
        val question = vm.uiState.value.plan!!.nextQuestion(emptySet())!!
        val recorded = mutableListOf<String>()

        vm.answer(question, question.choices.first()) { recorded += it }
        advanceUntilIdle()

        assertTrue(recorded.isEmpty())
        assertTrue(vm.uiState.value.failed)
        assertNotNull(vm.uiState.value.plan)
    }

    @Test
    fun `a spoken answer sends nothing`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val parking = TripChoice("Je précise", null)
        val question = vm.uiState.value.plan!!.nextQuestion(emptySet())!!

        vm.answer(question, parking) { }
        advanceUntilIdle()

        coVerify(exactly = 0) { chat.sendMessage(any()) }
    }
}
