package com.maggie.app.ui.screens.health

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.FoodIntakeWriteRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.DailyBalance
import com.maggie.app.data.model.FoodIntake
import com.maggie.app.data.model.PlannedMeal
import com.maggie.app.data.repository.NutritionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import java.time.ZoneId

/** « Santé › Nutrition » (MAG-748): one test per transition — load, « Mangé », its refusal, delete, add, Mercure. */
@OptIn(ExperimentalCoroutinesApi::class)
class NutritionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<NutritionRepository>()
    private val authRepository = mockk<AuthRepository>()
    private val mercureService = mockk<MercureService>()
    private val pushes = MutableSharedFlow<MercureEvent>()
    // Saturday 7 November 2026, 12:30 in Paris.
    private val clock = Clock.fixed(Instant.parse("2026-11-07T11:30:00Z"), ZoneId.of("Europe/Paris"))

    private val lunch = PlannedMeal("meal-1", "lunch", "Bol MAG-748", kcal = 515, proteinG = 20.0, carbsG = 50.0, fatG = 25.0)
    private val planned = DailyBalance(
        "2026-11-07", eatenKcal = 78, plannedKcal = 515, expendedKcal = 2195, balanceKcal = -1602,
        proteinG = 20.4, carbsG = 68.5, fatG = 25.3, plannedMeals = listOf(lunch),
    )
    private val eaten = planned.copy(eatenKcal = 593, plannedKcal = 0, plannedMeals = emptyList())
    private val apple = FoodIntake("apple", "2026-11-07", "2026-11-07T10:00:00+01:00", label = "Pomme", kcal = 78, source = "manual")
    private val bowl = FoodIntake("bowl", "2026-11-07", "2026-11-07T12:30:00+01:00", mealId = "meal-1", label = "Bol MAG-748", kcal = 515, source = "meal")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.getDailyBalance(any()) } returns Result.success(planned)
        coEvery { repository.getFoodIntakes(any()) } returns Result.success(listOf(apple))
        coEvery { repository.getDailyBalances(any(), any()) } returns Result.success(listOf(planned))
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns pushes
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = NutritionViewModel(repository, mercureService, authRepository, clock)

    @Test
    fun `loading, then today's bilan, journal and seven days`() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasLoaded)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.hasLoaded)
        assertEquals(planned, state.balance)
        assertEquals(listOf("apple"), state.intakes.map { it.id })
        assertEquals(listOf(planned), state.week)
        assertNull(state.error)
        coVerify { repository.getDailyBalance("2026-11-07") }
        coVerify { repository.getFoodIntakes("2026-11-07") }
        coVerify { repository.getDailyBalances("2026-11-01", "2026-11-07") }
    }

    @Test
    fun `a failed load says so in French and shows no bilan`() = runTest {
        coEvery { repository.getDailyBalance(any()) } returns Result.failure(RuntimeException("offline"))
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.hasLoaded)
        assertNull(state.balance)
        assertEquals(NutritionViewModel.MESSAGE_LOAD, state.error)
    }

    @Test
    fun `Mangé records the meal and reads the day again, the kcal move from planned to eaten`() = runTest {
        coEvery { repository.eatPlannedMeal("meal-1") } returns Result.success(bowl)
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.getDailyBalance(any()) } returns Result.success(eaten)
        coEvery { repository.getFoodIntakes(any()) } returns Result.success(listOf(apple, bowl))

        viewModel.eat("meal-1")
        assertTrue("meal-1" in viewModel.uiState.value.eating)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        coVerify(exactly = 1) { repository.eatPlannedMeal("meal-1") }
        assertEquals(593, state.balance!!.eatenKcal)
        assertEquals(0, state.balance!!.plannedKcal)
        assertTrue(state.balance!!.plannedMeals.isEmpty())
        assertEquals(listOf("apple", "bowl"), state.intakes.map { it.id })
        assertEquals(NutritionViewModel.MESSAGE_EATEN, state.message)
        assertTrue(state.eating.isEmpty())
    }

    @Test
    fun `a second tap while Mangé is on its way sends nothing`() = runTest {
        coEvery { repository.eatPlannedMeal("meal-1") } returns Result.success(bowl)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.eat("meal-1")
        viewModel.eat("meal-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.eatPlannedMeal("meal-1") }
    }

    @Test
    fun `a refused Mangé shows the API's reason and adds no row`() = runTest {
        coEvery { repository.eatPlannedMeal("meal-1") } returns
            Result.failure(ApiRefusalException(400, "mealId: this meal is already in the journal."))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.eat("meal-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("mealId: this meal is already in the journal.", state.error)
        assertEquals(listOf("apple"), state.intakes.map { it.id })
        assertNull(state.message)
        assertTrue(state.eating.isEmpty())
    }

    @Test
    fun `a Mangé that fails without a reason says so in French`() = runTest {
        coEvery { repository.eatPlannedMeal("meal-1") } returns Result.failure(RuntimeException("offline"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.eat("meal-1")
        advanceUntilIdle()

        assertEquals(NutritionViewModel.MESSAGE_EAT, viewModel.uiState.value.error)
    }

    @Test
    fun `delete takes the entry out at once, then reads the day again`() = runTest {
        coEvery { repository.deleteFoodIntake("apple") } returns Result.success(Unit)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.delete("apple")
        assertTrue(viewModel.uiState.value.intakes.isEmpty())
        coEvery { repository.getFoodIntakes(any()) } returns Result.success(emptyList())
        advanceUntilIdle()

        coVerify { repository.deleteFoodIntake("apple") }
        assertTrue(viewModel.uiState.value.intakes.isEmpty())
        assertEquals(NutritionViewModel.MESSAGE_DELETED, viewModel.uiState.value.message)
    }

    @Test
    fun `a refused delete puts the entry back and says so`() = runTest {
        coEvery { repository.deleteFoodIntake("apple") } returns Result.failure(RuntimeException("offline"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.delete("apple")
        advanceUntilIdle()

        assertEquals(listOf("apple"), viewModel.uiState.value.intakes.map { it.id })
        assertEquals(NutritionViewModel.MESSAGE_DELETE, viewModel.uiState.value.error)
    }

    @Test
    fun `add sends the food eaten now, closes the dialog and reads the day again`() = runTest {
        val sent = FoodIntakeWriteRequest("2026-11-07T12:30:00+01:00", "Yaourt", 90, proteinG = 4.5, carbsG = null, fatG = null)
        val yogurt = FoodIntake("yogurt", "2026-11-07", sent.eatenAt, label = "Yaourt", kcal = 90, source = "manual")
        coEvery { repository.createFoodIntake(sent) } returns Result.success(yogurt)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.openAdd()
        assertTrue(viewModel.uiState.value.addOpen)
        coEvery { repository.getFoodIntakes(any()) } returns Result.success(listOf(apple, yogurt))

        viewModel.add(" Yaourt ", 90, 4.5, null, null)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        coVerify(exactly = 1) { repository.createFoodIntake(sent) }
        assertFalse(state.addOpen)
        assertFalse(state.isSaving)
        assertEquals(listOf("apple", "yogurt"), state.intakes.map { it.id })
        assertEquals(NutritionViewModel.MESSAGE_ADDED, state.message)
    }

    @Test
    fun `a refused add keeps the dialog open with the API's reason`() = runTest {
        coEvery { repository.createFoodIntake(any()) } returns Result.failure(ApiRefusalException(400, "kcal: must be between 0 and 10000."))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.openAdd()

        viewModel.add("Yaourt", 90, null, null, null)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.addOpen)
        assertFalse(state.isSaving)
        assertEquals("kcal: must be between 0 and 10000.", state.error)
    }

    @Test
    fun `a food intake pushed on its topic reads the day again`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        val fromMaggie = FoodIntake("soup", "2026-11-07", "2026-11-07T12:00:00+01:00", label = "Soupe", kcal = 120, source = "conversation")
        coEvery { repository.getFoodIntakes(any()) } returns Result.success(listOf(apple, fromMaggie))

        pushes.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        verify { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.FOOD_INTAKES)) }
        assertEquals(listOf("apple", "soup"), viewModel.uiState.value.intakes.map { it.id })
    }
}
