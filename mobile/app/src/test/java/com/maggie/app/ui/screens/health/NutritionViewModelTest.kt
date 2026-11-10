package com.maggie.app.ui.screens.health

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.FoodIntakeWriteRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.DailyBalance
import com.maggie.app.data.model.FastingStatus
import com.maggie.app.data.model.FoodIntake
import com.maggie.app.data.model.HealthDay
import com.maggie.app.data.model.HealthProfile
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * « Santé › Nutrition » (MAG-748): one test per transition — load, « Mangé », its refusal, delete, add, Mercure; the
 * fast (MAG-770): fasting, open, closed, no protocol, the sheet saved or refused, the profile pushed.
 */
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

    // The window opened at 12:10 with the lunch, 16:8 from noon: it closes at 20:00, 7 h 30 from now.
    private val open = FastingStatus(
        "2026-11-07", protocol = "16:8", windowStart = "12:00", windowEnd = "20:00",
        openedAt = "2026-11-07T12:10:00+01:00", lastIntakeAt = "2026-11-07T12:10:00+01:00", state = FastingStatus.STATE_OPEN,
        nightFastMinutes = 965,
    )
    private val nights = listOf(HealthDay(id = "d-1", date = "2026-11-06", nightFastMinutes = 980), HealthDay(id = "d-2", date = "2026-11-07", nightFastMinutes = 965))

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.getDailyBalance(any()) } returns Result.success(planned)
        coEvery { repository.getFoodIntakes(any()) } returns Result.success(listOf(apple))
        coEvery { repository.getDailyBalances(any(), any()) } returns Result.success(listOf(planned))
        coEvery { repository.getFasting(any()) } returns Result.success(open)
        coEvery { repository.getHealthDays(any(), any()) } returns Result.success(nights)
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

    private fun TestScope.loaded(status: FastingStatus): NutritionViewModel {
        coEvery { repository.getFasting(any()) } returns Result.success(status)
        val viewModel = viewModel()
        advanceUntilIdle()
        return viewModel
    }

    private fun NutritionViewModel.card(): FastingView? = uiState.value.fasting?.let { fastingView(it, clock.instant(), zone) }

    @Test
    fun `the fast is read with the day, and the fourteen nights`() = runTest {
        val viewModel = loaded(open)

        assertEquals(open, viewModel.uiState.value.fasting)
        assertEquals(nights, viewModel.uiState.value.fastingDays)
        coVerify { repository.getFasting("2026-11-07") }
        coVerify { repository.getHealthDays("2026-10-25", "2026-11-07") }
    }

    @Test
    fun `fasting before the window reads Jeûne, the time until it opens`() = runTest {
        val fasting = FastingStatus(
            "2026-11-07", protocol = "16:8", windowStart = "13:00", windowEnd = "21:00",
            lastIntakeAt = "2026-11-06T20:30:00+01:00", state = FastingStatus.STATE_FASTING,
        )
        val card = loaded(fasting).card()!!

        assertFalse(card.open)
        assertEquals(FastingView.TITLE_FASTING, card.title)
        assertEquals("0 h 30", formatFastDuration(card.remainingMinutes))
        assertEquals(1f, card.progress)
        assertEquals("13 h 00 → 21 h 00", card.span)
    }

    @Test
    fun `the window open reads Fenêtre ouverte, the time until it closes`() = runTest {
        val card = loaded(open).card()!!

        assertTrue(card.open)
        assertEquals(FastingView.TITLE_OPEN, card.title)
        assertEquals("7 h 30", formatFastDuration(card.remainingMinutes))
        assertEquals(20f / 480f, card.progress, 0.001f)
        assertEquals("12 h 10 → 20 h 00", card.span)
        assertEquals("Jeûne de la nuit · 16 h 05", card.nightFast)
    }

    @Test
    fun `the window closed reads Jeûne, the time until tomorrow's window`() = runTest {
        val closed = FastingStatus(
            "2026-11-07", protocol = "20:4", windowStart = "08:00", windowEnd = "12:00",
            openedAt = "2026-11-07T08:15:00+01:00", lastIntakeAt = "2026-11-07T11:50:00+01:00", state = FastingStatus.STATE_CLOSED,
        )
        val card = loaded(closed).card()!!

        assertFalse(card.open)
        assertEquals("19 h 30", formatFastDuration(card.remainingMinutes))
        assertEquals(40f / (20 * 60), card.progress, 0.001f)
        assertEquals("8 h 00 → 12 h 00", card.span)
    }

    @Test
    fun `no protocol, no card`() = runTest {
        val none = FastingStatus("2026-11-07")
        val viewModel = loaded(none)

        assertEquals(none, viewModel.uiState.value.fasting)
        assertNull(viewModel.card())
    }

    @Test
    fun `Enregistrer sends the protocol and the window, closes the sheet and reads the fast again`() = runTest {
        val viewModel = loaded(open)
        val longer = open.copy(protocol = "18:6", windowEnd = "18:00")
        coEvery { repository.updateFasting("18:6", "12:00") } coAnswers {
            coEvery { repository.getFasting(any()) } returns Result.success(longer)
            Result.success(HealthProfile(fastingProtocol = "18:6", fastingWindowStart = "12:00"))
        }
        viewModel.openFastingSheet()
        assertTrue(viewModel.uiState.value.fastingSheetOpen)

        viewModel.saveFasting("18:6", "12:00")
        assertTrue(viewModel.uiState.value.fastingSaving)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        coVerify(exactly = 1) { repository.updateFasting("18:6", "12:00") }
        assertFalse(state.fastingSheetOpen)
        assertFalse(state.fastingSaving)
        assertEquals(longer, state.fasting)
        assertEquals(NutritionViewModel.MESSAGE_FASTING_SAVED, state.message)
    }

    @Test
    fun `a refused Enregistrer keeps the sheet open with the API's reason`() = runTest {
        val viewModel = loaded(open)
        coEvery { repository.updateFasting(any(), any()) } returns
            Result.failure(ApiRefusalException(400, "fastingProtocol: must be one of 14:10, 16:8, 18:6, 20:4."))
        viewModel.openFastingSheet()

        viewModel.saveFasting("18:6", "12:00")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.fastingSheetOpen)
        assertFalse(state.fastingSaving)
        assertEquals("fastingProtocol: must be one of 14:10, 16:8, 18:6, 20:4.", state.error)
        assertEquals(open, state.fasting)
    }

    @Test
    fun `an Enregistrer that fails without a reason says so in French`() = runTest {
        val viewModel = loaded(open)
        coEvery { repository.updateFasting(any(), any()) } returns Result.failure(RuntimeException("offline"))

        viewModel.saveFasting("18:6", "12:00")
        advanceUntilIdle()

        assertEquals(NutritionViewModel.MESSAGE_FASTING_SAVE, viewModel.uiState.value.error)
    }

    @Test
    fun `a profile pushed on its topic reads the fast again`() = runTest {
        val viewModel = loaded(open)
        val stopped = FastingStatus("2026-11-07")
        coEvery { repository.getFasting(any()) } returns Result.success(stopped)

        pushes.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        verify { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.HEALTH_PROFILE)) }
        assertNotNull(viewModel.uiState.value.fasting)
        assertNull(viewModel.card())
    }
}
