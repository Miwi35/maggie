package com.maggie.app.ui.screens.cookbook.meals

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealSlot
import com.maggie.app.data.repository.MealRepository
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The week of meals, and the day each one lands on — MAG-251.
 *
 * The screen used to ask for a window of *instants* and read the day back out
 * of `startAt` through the device's time zone. Two conversions for a value that
 * is a day: a meal planned for Wednesday came back as Tuesday at 22:00 UTC and
 * was grouped under Tuesday wherever the device sat east of Greenwich.
 *
 * There is nothing to convert now. The three days below are a summer-time one,
 * a winter-time one and the day the clocks go back, and all three have to group
 * under themselves — which they do for the uninteresting reason that no offset
 * is involved any more. That is the claim.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MealsWeekViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mealRepository: MealRepository
    private val mercureEvents = MutableSharedFlow<MercureEvent>()
    private lateinit var mercure: MercureService
    private lateinit var auth: AuthRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mealRepository = mockk()
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(emptyList())
        mercure = mockk()
        every { mercure.subscribe(any()) } returns mercureEvents
        auth = mockk()
        coEvery { auth.getUserId() } returns "user-1"
    }

    private fun newViewModel() = MealsWeekViewModel(mealRepository, mercure, auth)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun aMeal(day: String, slot: MealSlot = MealSlot.LUNCH) =
        Meal(id = "meal-$day-$slot", summary = "Déjeuner", date = day, slot = slot)

    @Test
    fun `asks for the week as a range of days, both ends included`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        val weekStart = viewModel.uiState.value.weekStart

        coVerify { mealRepository.getMeals(weekStart.toString(), weekStart.plusDays(6).toString()) }
    }

    @Test
    fun `groups each meal under its own day, whatever the season`() = runTest(testDispatcher) {
        val days = listOf("2026-10-07", "2026-12-09", "2026-10-25")
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(days.map { aMeal(it) })

        val viewModel = newViewModel()
        advanceUntilIdle()

        val grouped = viewModel.uiState.value.mealsByDaySlot
        days.forEach { day ->
            val meals = grouped[LocalDate.parse(day)]?.get(MealSlot.LUNCH)
            assertEquals("the meal of $day is not under $day", listOf(aMeal(day)), meals)
        }
    }

    @Test
    fun `keeps lunch and dinner of the same day apart`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(
            listOf(aMeal("2026-10-07", MealSlot.LUNCH), aMeal("2026-10-07", MealSlot.DINNER)),
        )

        val viewModel = newViewModel()
        advanceUntilIdle()

        val day = viewModel.uiState.value.mealsByDaySlot[LocalDate.parse("2026-10-07")]
        assertEquals(1, day?.get(MealSlot.LUNCH)?.size)
        assertEquals(1, day?.get(MealSlot.DINNER)?.size)
    }

    @Test
    fun `moving a week re-asks for that week`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        val weekStart = viewModel.uiState.value.weekStart

        viewModel.nextWeek()
        advanceUntilIdle()

        assertEquals(weekStart.plusWeeks(1), viewModel.uiState.value.weekStart)
        coVerify {
            mealRepository.getMeals(weekStart.plusWeeks(1).toString(), weekStart.plusWeeks(1).plusDays(6).toString())
        }
    }

    @Test
    fun `a failed load leaves the error on the state and stops loading`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.failure(RuntimeException("réseau coupé"))

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals("réseau coupé", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a successful load clears a previous error`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.failure(RuntimeException("réseau coupé"))
        val viewModel = newViewModel()
        advanceUntilIdle()

        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(aMeal("2026-10-07")))
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `deleting a meal takes it off the week without a reload`() = runTest(testDispatcher) {
        val meals = listOf(aMeal("2026-10-07"), aMeal("2026-10-07", MealSlot.DINNER))
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(meals)
        coEvery { mealRepository.deleteMeal(any()) } returns Result.success(Unit)
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.deleteMeal(meals[0])
        assertEquals(meals[0].id, viewModel.uiState.value.deletingMealId)
        advanceUntilIdle()

        val day = viewModel.uiState.value.mealsByDaySlot[LocalDate.parse("2026-10-07")]
        assertEquals(emptyList<Meal>(), day?.get(MealSlot.LUNCH))
        assertEquals(1, day?.get(MealSlot.DINNER)?.size)
        assertNull(viewModel.uiState.value.deletingMealId)
        assertNull(viewModel.uiState.value.deleteError)
        coVerify(exactly = 1) { mealRepository.deleteMeal(meals[0].id) }
    }

    @Test
    fun `a meal saved from its sheet shows its new title without a reload`() = runTest(testDispatcher) {
        val meals = listOf(aMeal("2026-10-07"), aMeal("2026-10-07", MealSlot.DINNER))
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(meals)
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.mealSaved(meals[0].copy(summary = "Dimanche en famille", customTitle = "Dimanche en famille"))

        val day = viewModel.uiState.value.mealsByDaySlot[LocalDate.parse("2026-10-07")]
        assertEquals("Dimanche en famille", day?.get(MealSlot.LUNCH)?.single()?.summary)
        assertEquals("Déjeuner", day?.get(MealSlot.DINNER)?.single()?.summary)
        coVerify(exactly = 1) { mealRepository.getMeals(any(), any()) }
    }

    @Test
    fun `a meal deleted from its sheet leaves the week without a reload`() = runTest(testDispatcher) {
        val meals = listOf(aMeal("2026-10-07"), aMeal("2026-10-07", MealSlot.DINNER))
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(meals)
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.mealDeleted(meals[0].id)

        val day = viewModel.uiState.value.mealsByDaySlot[LocalDate.parse("2026-10-07")]
        assertEquals(emptyList<Meal>(), day?.get(MealSlot.LUNCH))
        assertEquals(1, day?.get(MealSlot.DINNER)?.size)
        coVerify(exactly = 1) { mealRepository.getMeals(any(), any()) }
    }

    @Test
    fun `a refused deletion keeps the meal and says why`() = runTest(testDispatcher) {
        val meal = aMeal("2026-10-07")
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(meal))
        coEvery { mealRepository.deleteMeal(any()) } returns Result.failure(RuntimeException("403"))
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.deleteMeal(meal)
        advanceUntilIdle()

        val day = viewModel.uiState.value.mealsByDaySlot[LocalDate.parse("2026-10-07")]
        assertEquals(listOf(meal), day?.get(MealSlot.LUNCH))
        assertEquals("403", viewModel.uiState.value.deleteError)
        assertNull(viewModel.uiState.value.deletingMealId)

        viewModel.dismissDeleteError()
        assertNull(viewModel.uiState.value.deleteError)
    }

    @Test
    fun `a second delete while one is in flight sends nothing`() = runTest(testDispatcher) {
        val meal = aMeal("2026-10-07")
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(meal))
        coEvery { mealRepository.deleteMeal(any()) } returns Result.success(Unit)
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.deleteMeal(meal)
        viewModel.deleteMeal(meal)
        advanceUntilIdle()

        coVerify(exactly = 1) { mealRepository.deleteMeal(meal.id) }
    }

    @Test
    fun `a change announced on the meals topic reloads the week without the spinner`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(aMeal("2026-10-07")))

        mercureEvents.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.mealsByDaySlot[LocalDate.parse("2026-10-07")]?.get(MealSlot.LUNCH)?.size)
        assertEquals(false, viewModel.uiState.value.isLoading)
        coVerify { mercure.subscribe("/users/user-1/api/meals/{id}") }
    }
}
