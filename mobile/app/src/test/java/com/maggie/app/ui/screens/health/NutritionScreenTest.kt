package com.maggie.app.ui.screens.health

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
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
import com.maggie.app.data.model.FoodIntake
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
 * « Santé › Nutrition » as drawn (MAG-748): the bilan first, the seven days, the macros, then the journal with its
 * planned meal and « Mangé ». Today is Saturday 7 November 2026, 12:30 in Paris.
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

    private fun viewModel(
        balance: DailyBalance = planned,
        intakes: List<FoodIntake> = listOf(apple),
    ): NutritionViewModel {
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
}
