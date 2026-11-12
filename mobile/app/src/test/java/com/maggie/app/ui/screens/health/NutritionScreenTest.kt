package com.maggie.app.ui.screens.health

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.DailyBalance
import com.maggie.app.data.model.FastingProtocol
import com.maggie.app.data.model.FastingStatus
import com.maggie.app.data.model.FoodIntake
import com.maggie.app.data.model.HealthDay
import com.maggie.app.data.model.PlannedMeal
import com.maggie.app.data.repository.NutritionRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.HealthSection
import com.maggie.app.ui.theme.MaggieTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * « Santé › Nutrition » as drawn (MAG-748): the bilan first, the fast (MAG-770), the seven days, the macros, then the
 * journal with its planned meal and « Mangé ». Today is Saturday 7 November 2026, 12:30 in Paris.
 */
@RunWith(AndroidJUnit4::class)
class NutritionScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val repository = mockk<NutritionRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-11-07T11:30:00Z"), ZoneId.of("Europe/Paris"))

    private val lunch = PlannedMeal("meal-1", "lunch", "Bol MAG-748", kcal = 515, proteinG = 20.0, carbsG = 50.0, fatG = 25.0)
    private val planned = DailyBalance(
        "2026-11-07", eatenKcal = 78, plannedKcal = 515, expendedKcal = 2195, balanceKcal = -1602,
        proteinG = 20.4, carbsG = 68.5, fatG = 25.3, plannedMeals = listOf(lunch),
    )
    private val apple = FoodIntake("apple", "2026-11-07", "2026-11-07T10:00:00+01:00", label = "Pomme", kcal = 78, source = "manual")

    // The window opened at 12:10, 16:8 from noon: it closes at 20:00, 7 h 30 from now.
    private val open = FastingStatus(
        "2026-11-07", protocol = "16:8", windowStart = "12:00", windowEnd = "20:00",
        openedAt = "2026-11-07T12:10:00+01:00", lastIntakeAt = "2026-11-07T12:10:00+01:00", state = FastingStatus.STATE_OPEN,
        nightFastMinutes = 965,
    )

    init {
        coEvery { repository.getFasting(any()) } returns Result.success(open)
        coEvery { repository.getHealthDays(any(), any()) } returns Result.success(listOf(HealthDay(id = "d-1", date = "2026-11-07", nightFastMinutes = 965)))
    }

    private fun viewModel(
        balance: DailyBalance = planned,
        intakes: List<FoodIntake> = listOf(apple),
        fasting: FastingStatus = open,
    ): NutritionViewModel {
        coEvery { repository.getFasting(any()) } returns Result.success(fasting)
        coEvery { repository.getDailyBalance(any()) } returns Result.success(balance)
        coEvery { repository.getFoodIntakes(any()) } returns Result.success(intakes)
        coEvery { repository.getDailyBalances(any(), any()) } returns Result.success(listOf(balance))
        val auth = mockk<AuthRepository>()
        coEvery { auth.getUserId() } returns "user-1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        return NutritionViewModel(repository, mercure, auth, clock)
    }

    private fun waitForBilan() =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.NUTRITION_BALANCE).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `the bilan comes first, then the seven days, the macros and the journal`() {
        compose.setContent { NutritionScreen(viewModel()) }
        waitForBilan()

        assertTopToBottom(
            "bilan" to compose.onNodeWithTag(UiTags.NUTRITION_BALANCE),
            "week" to compose.onNodeWithTag(UiTags.NUTRITION_WEEK),
            "macros" to compose.onNodeWithTag(UiTags.NUTRITION_MACROS),
            "journal" to compose.onNodeWithTag(UiTags.NUTRITION_JOURNAL),
        )
        compose.onNodeWithTag(UiTags.NUTRITION_BALANCE_VALUE).assertTextEquals("−1 602 kcal")
        compose.onNodeWithTag(UiTags.NUTRITION_BALANCE_FIGURES).assertTextEquals("Mangé 78 · Prévu 515 · Dépensé 2 195 kcal")
        compose.onNodeWithText("7 jours").assertIsDisplayed()
        compose.onNodeWithText("Protéines").assertExists()
        compose.onNodeWithText("20,4 g").assertExists()
        compose.onNodeWithText("68,5 g").assertExists()
        compose.onNodeWithText("25,3 g").assertExists()
    }

    @Test
    fun `the journal lists the intakes with their time and origin, then the planned meal with Mangé`() {
        compose.setContent { NutritionScreen(viewModel()) }
        waitForBilan()

        compose.onNodeWithText("Pomme").assertExists()
        compose.onNodeWithText("10:00 · Saisie").assertExists()
        compose.onNodeWithText("78 kcal").assertExists()
        compose.onNodeWithText("Bol MAG-748").assertExists()
        compose.onNodeWithText("Déjeuner · 515 kcal").assertExists()
        assertTopToBottom(
            "intake" to compose.onNodeWithTag(UiTags.nutritionIntake("apple")),
            "planned" to compose.onNodeWithTag(UiTags.nutritionPlanned("meal-1")),
        )
    }

    @Test
    fun `a balance within 150 kcal reads Équilibre`() {
        compose.setContent { NutritionScreen(viewModel(balance = planned.copy(balanceKcal = 100))) }
        waitForBilan()

        compose.onNodeWithTag(UiTags.NUTRITION_BALANCE_VALUE).assertTextEquals("Équilibre")
    }

    @Test
    fun `no zone of the bar is drawn in the error or attention colour`() {
        val drawn = mutableMapOf<BalanceZone, Color>()
        var error = Color.Unspecified
        var attention = Color.Unspecified
        compose.setContent {
            MaggieTheme {
                error = MaterialTheme.colorScheme.error
                attention = MaterialTheme.colorScheme.tertiary
                BalanceZone.entries.forEach { drawn[it] = balanceColor(it) }
            }
        }
        compose.waitForIdle()

        // +100 kcal is balanced, drawn in the soft balanced colour — no alert.
        assertEquals(drawn[BalanceZone.BALANCED], drawn[balanceZone(100)])
        drawn.forEach { (zone, color) ->
            assertNotEquals("$zone is drawn in the error colour", error, color)
            assertNotEquals("$zone is drawn in the attention colour", attention, color)
        }
    }

    @Test
    fun `Mangé records the planned meal and the bilan moves from planned to eaten`() {
        val eaten = planned.copy(eatenKcal = 593, plannedKcal = 0, plannedMeals = emptyList())
        val bowl = FoodIntake("bowl", "2026-11-07", "2026-11-07T12:30:00+01:00", mealId = "meal-1", label = "Bol MAG-748", kcal = 515, source = "meal")
        val viewModel = viewModel()
        coEvery { repository.eatPlannedMeal("meal-1") } coAnswers {
            coEvery { repository.getDailyBalance(any()) } returns Result.success(eaten)
            coEvery { repository.getFoodIntakes(any()) } returns Result.success(listOf(apple, bowl))
            Result.success(bowl)
        }
        compose.setContent { NutritionScreen(viewModel) }
        waitForBilan()

        compose.onNodeWithTag(UiTags.nutritionEat("meal-1")).performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Mangé 593 · Prévu 0 · Dépensé 2 195 kcal").fetchSemanticsNodes().isNotEmpty()
        }
        coVerify(exactly = 1) { repository.eatPlannedMeal("meal-1") }
        compose.onNodeWithText("12:30 · Repas").assertExists()
        assertEquals(0, compose.onAllNodesWithTag(UiTags.nutritionEat("meal-1")).fetchSemanticsNodes().size)
    }

    @Test
    fun `a refused Mangé shows the API's message and keeps the meal planned`() {
        coEvery { repository.eatPlannedMeal("meal-1") } returns
            Result.failure(ApiRefusalException(400, "mealId: this meal is already in the journal."))
        compose.setContent { NutritionScreen(viewModel()) }
        waitForBilan()

        compose.onNodeWithTag(UiTags.nutritionEat("meal-1")).performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("mealId: this meal is already in the journal.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.nutritionEat("meal-1")).assertIsEnabled()
        assertEquals(1, compose.onAllNodesWithText("Bol MAG-748").fetchSemanticsNodes().size)
    }

    @Test
    fun `the trash button deletes the intake`() {
        coEvery { repository.deleteFoodIntake("apple") } coAnswers {
            coEvery { repository.getFoodIntakes(any()) } returns Result.success(emptyList())
            Result.success(Unit)
        }
        compose.setContent { NutritionScreen(viewModel()) }
        waitForBilan()

        compose.onNodeWithTag(UiTags.nutritionIntakeDelete("apple")).performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.NUTRITION_JOURNAL_EMPTY).fetchSemanticsNodes().isNotEmpty() }
        coVerify { repository.deleteFoodIntake("apple") }
        compose.onNodeWithText("Rien de noté aujourd’hui").assertExists()
    }

    @Test
    fun `the add form waits for a label and whole kcal, then hands them over with the macros given`() {
        val saved = mutableListOf<List<Any?>>()
        // The form alone: a window of its own hangs Robolectric's idling (see MealMenuSectionTest), so « + » opening
        // it is NutritionViewModelTest's `openAdd`, and the window is the journey's.
        compose.setContent { AddFoodForm(saving = false, onSave = { l, k, p, c, f -> saved += listOf(l, k, p, c, f) }, onDismiss = {}) }

        compose.onNodeWithTag(UiTags.NUTRITION_ADD_SAVE).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.NUTRITION_ADD_LABEL).performTextInput("Yaourt")
        compose.onNodeWithTag(UiTags.NUTRITION_ADD_SAVE).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.NUTRITION_ADD_KCAL).performTextInput("90")
        compose.onNodeWithTag(UiTags.NUTRITION_ADD_PROTEIN).performTextInput("4,5")
        compose.onNodeWithTag(UiTags.NUTRITION_ADD_SAVE).assertIsEnabled().performClick()

        assertEquals(listOf(listOf<Any?>("Yaourt", 90, 4.5, null, null)), saved)
    }

    @Test
    fun `the edit form starts from the entry and hands over what was changed`() {
        val saved = mutableListOf<List<Any?>>()
        compose.setContent {
            EditFoodForm(
                apple, ZoneId.of("Europe/Paris"), saving = false,
                onSave = { _, l, q, k, p, c, f, t -> saved += listOf(l, q, k, p, c, f, t.toString()) },
                onDismiss = {},
            )
        }

        compose.onNodeWithTag(UiTags.NUTRITION_EDIT_LABEL).assertTextEquals("Aliment", "Pomme")
        compose.onNodeWithTag(UiTags.NUTRITION_EDIT_TIME).assertTextEquals("Heure (HH:mm)", "10:00")
        compose.onNodeWithTag(UiTags.NUTRITION_EDIT_QUANTITY).performTextReplacement("150")
        compose.onNodeWithTag(UiTags.NUTRITION_EDIT_KCAL).performTextReplacement("120")
        compose.onNodeWithTag(UiTags.NUTRITION_EDIT_SAVE).assertIsEnabled().performClick()

        assertEquals(listOf(listOf<Any?>("Pomme", 150.0, 120, 0.0, 0.0, 0.0, "10:00")), saved)
    }

    @Test
    fun `the edit form refuses a time that is not one`() {
        compose.setContent { EditFoodForm(apple, ZoneId.of("Europe/Paris"), saving = false, onSave = { _, _, _, _, _, _, _, _ -> }, onDismiss = {}) }

        compose.onNodeWithTag(UiTags.NUTRITION_EDIT_TIME).performTextReplacement("25:61")

        compose.onNodeWithTag(UiTags.NUTRITION_EDIT_SAVE).assertIsNotEnabled()
    }

    @Test
    fun `the add form refuses kcal that are not whole`() {
        compose.setContent { AddFoodForm(saving = false, onSave = { _, _, _, _, _ -> }, onDismiss = {}) }

        compose.onNodeWithTag(UiTags.NUTRITION_ADD_LABEL).performTextInput("Yaourt")
        compose.onNodeWithTag(UiTags.NUTRITION_ADD_KCAL).performTextInput("90,5")

        compose.onNodeWithTag(UiTags.NUTRITION_ADD_SAVE).assertIsNotEnabled()
    }

    @Test
    fun `the header offers to add a food`() {
        compose.setContent { NutritionScreen(viewModel()) }
        waitForBilan()

        compose.onNodeWithTag(UiTags.NUTRITION_ADD).assertIsDisplayed()
        compose.onNodeWithContentDescription("Ajouter un aliment").assertExists()
    }

    @Test
    fun `without a profile the bilan shows eaten and planned and the hint opens Configuration`() {
        val picked = mutableListOf<HealthSection>()
        val noProfile = planned.copy(expendedKcal = null, balanceKcal = null)
        compose.setContent { NutritionScreen(viewModel(balance = noProfile), onSelectSection = { picked += it }) }
        waitForBilan()

        compose.onNodeWithTag(UiTags.NUTRITION_BALANCE_VALUE).assertTextEquals("593 kcal")
        compose.onNodeWithTag(UiTags.NUTRITION_BALANCE_FIGURES).assertTextEquals("Mangé 78 · Prévu 515 kcal")
        compose.onNodeWithTag(UiTags.NUTRITION_PROFILE_HINT).performClick()

        assertEquals(listOf(HealthSection.CONFIGURATION), picked)
    }

    @Test
    fun `the header is Santé, Nutrition, after Suivi and Sport in its menu`() {
        val picked = mutableListOf<HealthSection>()
        compose.setContent { NutritionScreen(viewModel(), onSelectSection = { picked += it }) }
        waitForBilan()

        compose.onNodeWithText("Santé").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()
        assertTopToBottom(
            "Suivi" to compose.onNodeWithTag(UiTags.breadcrumbMenu("suivi")),
            "Sport" to compose.onNodeWithTag(UiTags.breadcrumbMenu("sport")),
            "Nutrition" to compose.onNodeWithTag(UiTags.breadcrumbMenu("nutrition")),
        )
        compose.onNodeWithTag(UiTags.breadcrumbMenu("suivi")).performClick()

        assertEquals(listOf(HealthSection.SUIVI), picked)
    }

    @Test
    fun `a failed load offers to try again`() {
        coEvery { repository.getDailyBalance(any()) } returns Result.failure(RuntimeException("offline"))
        coEvery { repository.getFoodIntakes(any()) } returns Result.success(emptyList())
        coEvery { repository.getDailyBalances(any(), any()) } returns Result.success(emptyList())
        val auth = mockk<AuthRepository>().also { coEvery { it.getUserId() } returns "user-1" }
        val mercure = mockk<MercureService>().also { every { it.subscribe(any()) } returns emptyFlow() }
        compose.setContent { NutritionScreen(NutritionViewModel(repository, mercure, auth, clock)) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.NUTRITION_EMPTY).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Bilan indisponible").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet holds the bilan on the left and the journal on the right`() {
        compose.setContent { NutritionScreen(viewModel()) }
        waitForBilan()

        val root = compose.onNodeWithTag(UiTags.NUTRITION).fetchSemanticsNode().boundsInRoot
        val bilan = compose.onNodeWithTag(UiTags.NUTRITION_BALANCE).fetchSemanticsNode().boundsInRoot
        val journal = compose.onNodeWithTag(UiTags.NUTRITION_JOURNAL).fetchSemanticsNode().boundsInRoot
        assert(bilan.right <= root.center.x + 1f) { "bilan $bilan overflows the left column of $root" }
        assert(journal.left >= root.center.x - 1f) { "journal $journal is not in the right column of $root" }
    }

    private fun waitForFasting() =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.FASTING_CARD).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `the fast sits under the bilan, its window open, the protocol, the span and the night`() {
        compose.setContent { NutritionScreen(viewModel()) }
        waitForBilan()
        waitForFasting()

        assertTopToBottom(
            "bilan" to compose.onNodeWithTag(UiTags.NUTRITION_BALANCE),
            "fasting" to compose.onNodeWithTag(UiTags.FASTING_CARD),
            "week" to compose.onNodeWithTag(UiTags.NUTRITION_WEEK),
        )
        compose.onNodeWithTag(UiTags.FASTING_STATE).assertTextEquals("Fenêtre ouverte")
        compose.onNodeWithTag(UiTags.FASTING_REMAINING).assertTextEquals("7 h 30")
        compose.onNodeWithText("restantes").assertExists()
        compose.onNodeWithText("16:8").assertExists()
        compose.onNodeWithTag(UiTags.FASTING_SPAN).assertTextEquals("12 h 10 → 20 h 00")
        compose.onNodeWithTag(UiTags.FASTING_NIGHT).assertTextEquals("Jeûne de la nuit · 16 h 05")
    }

    @Test
    fun `fasting before the window reads Jeûne`() {
        val fasting = FastingStatus(
            "2026-11-07", protocol = "16:8", windowStart = "13:00", windowEnd = "21:00",
            lastIntakeAt = "2026-11-06T20:30:00+01:00", state = FastingStatus.STATE_FASTING,
        )
        compose.setContent { NutritionScreen(viewModel(fasting = fasting)) }
        waitForFasting()

        compose.onNodeWithTag(UiTags.FASTING_STATE).assertTextEquals("Jeûne")
        compose.onNodeWithTag(UiTags.FASTING_REMAINING).assertTextEquals("0 h 30")
        compose.onNodeWithTag(UiTags.FASTING_SPAN).assertTextEquals("13 h 00 → 21 h 00")
    }

    @Test
    fun `without a protocol there is no fasting card`() {
        compose.setContent { NutritionScreen(viewModel(fasting = FastingStatus("2026-11-07"))) }
        waitForBilan()
        compose.waitForIdle()

        assertEquals(0, compose.onAllNodesWithTag(UiTags.FASTING_CARD).fetchSemanticsNodes().size)
    }

    @Test
    fun `the sheet offers the four protocols with their window, the window's start and Enregistrer`() {
        val saved = mutableListOf<Pair<String, String>>()
        // The content alone, as for the add form: a window of its own hangs Robolectric's idling.
        compose.setContent { FastingSheetContent(FastingProtocol.P16_8, "12:00", saving = false, onSave = { p, w -> saved += p to w }) }

        compose.onNodeWithText("Jeûne").assertIsDisplayed()
        listOf("14:10" to "10 h de fenêtre", "16:8" to "8 h de fenêtre", "18:6" to "6 h de fenêtre", "20:4" to "4 h de fenêtre").forEach { (key, window) ->
            compose.onNodeWithTag(UiTags.fastingProtocol(key)).assertIsDisplayed()
            compose.onNodeWithText(window).assertExists()
        }
        compose.onNodeWithTag(UiTags.fastingProtocol("16:8")).assertIsSelected()
        compose.onNodeWithText("Début de la fenêtre").assertExists()
        compose.onNodeWithTag(UiTags.FASTING_WINDOW_START).assertTextEquals("12:00")

        compose.onNodeWithTag(UiTags.fastingProtocol("18:6")).performClick()
        compose.onNodeWithTag(UiTags.fastingProtocol("18:6")).assertIsSelected()
        compose.onNodeWithTag(UiTags.FASTING_SAVE).assertTextEquals("Enregistrer").performClick()

        assertEquals(listOf("18:6" to "12:00"), saved)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet shows the fourteen nights' fasts under the fast, in the left column`() {
        compose.setContent { NutritionScreen(viewModel()) }
        waitForBilan()
        waitForFasting()

        val root = compose.onNodeWithTag(UiTags.NUTRITION).fetchSemanticsNode().boundsInRoot
        val history = compose.onNodeWithTag(UiTags.FASTING_HISTORY).fetchSemanticsNode().boundsInRoot
        assert(history.right <= root.center.x + 1f) { "history $history overflows the left column of $root" }
        assertTopToBottom(
            "fasting" to compose.onNodeWithTag(UiTags.FASTING_CARD),
            "history" to compose.onNodeWithTag(UiTags.FASTING_HISTORY),
        )
        compose.onNodeWithText("Jeûnes · 14 jours").assertExists()
    }
}
