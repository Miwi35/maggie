package com.maggie.app.ui.screens.health

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.BodyMeasurement
import com.maggie.app.data.model.DailyBalance
import com.maggie.app.data.model.DailyEnergy
import com.maggie.app.data.model.HealthDay
import com.maggie.app.data.repository.BodyMeasurementRepository
import com.maggie.app.data.repository.DailyEnergyRepository
import com.maggie.app.data.repository.HealthDayRepository
import com.maggie.app.data.repository.NutritionRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.HealthSection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * « Santé › Suivi » as drawn (MAG-706): the breadcrumb, the period selector, the Poids card, Pas and Énergie (MAG-746),
 * the bilan (MAG-748) and « Moral · fatigue » (MAG-750). Today is Wednesday 21 October 2026.
 */
@RunWith(AndroidJUnit4::class)
class SuiviScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val repository = mockk<BodyMeasurementRepository>()
    private val energyRepository = mockk<DailyEnergyRepository>()
    private val nutritionRepository = mockk<NutritionRepository>()
    private val healthDayRepository = mockk<HealthDayRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:15:30Z"), ZoneId.of("Europe/Paris"))

    private val today = BodyMeasurement("today", "2026-10-21T07:12:00+02:00", weightKg = 72.4, bodyFatPercent = 21.3)
    private val monday = BodyMeasurement("monday", "2026-10-19T07:05:00+02:00", weightKg = 72.8, bodyFatPercent = 21.6)

    private fun viewModel(
        measurements: List<BodyMeasurement> = listOf(today, monday),
        energy: List<DailyEnergy> = listOf(
            DailyEnergy("2026-10-21", steps = 8000, activeKcal = 400, bmrKcal = 1648, workoutKcal = 0, stepsKcal = 14, expendedKcal = 1662),
        ),
        balance: DailyBalance = DailyBalance("2026-10-21", eatenKcal = 1500, plannedKcal = 480, expendedKcal = 1662, balanceKcal = 318),
        healthDays: List<HealthDay> = listOf(
            HealthDay(id = "d1", date = "2026-10-19", mood = 6, fatigue = 5),
            HealthDay(id = "d2", date = "2026-10-21", mood = 7, fatigue = 4),
        ),
    ): SuiviViewModel {
        coEvery { healthDayRepository.getDays(any(), any()) } returns Result.success(healthDays)
        coEvery { repository.getBodyMeasurements(any()) } returns Result.success(measurements)
        coEvery { nutritionRepository.getDailyBalance(any()) } returns Result.success(balance)
        coEvery { energyRepository.getDailyEnergy(any(), any()) } returns Result.success(energy)
        val auth = mockk<AuthRepository>()
        coEvery { auth.getUserId() } returns "user-1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        return SuiviViewModel(repository, energyRepository, nutritionRepository, healthDayRepository, mercure, auth, clock)
    }

    private fun waitForCard() =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.WEIGHT_CARD).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForBilan() =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.NUTRITION_BALANCE).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForMood() =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.MOOD_VALUE).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `the Pas and Énergie cards show today's figures from the API`() {
        compose.setContent { SuiviScreen(viewModel()) }
        waitForCard()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.ENERGY_TOTAL).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag(UiTags.STEPS_VALUE).assertTextEquals("8 000")
        compose.onNodeWithTag(UiTags.ENERGY_TOTAL).assertTextEquals("1 662 kcal")
        compose.onNodeWithTag(UiTags.ENERGY_BASE).assertTextContains("Base 1 648")
    }

    @Test
    fun `without a single day of steps the Pas card is empty`() {
        compose.setContent { SuiviScreen(viewModel(energy = listOf(DailyEnergy("2026-10-21")))) }
        waitForCard()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.STEPS_EMPTY).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Aucune donnée de pas").assertIsDisplayed()
    }

    @Test
    fun `the header is Santé, Suivi, and its menu lists Suivi, Nutrition, then Sport`() {
        val picked = mutableListOf<HealthSection>()
        compose.setContent { SuiviScreen(viewModel(), onSelectSection = { picked += it }) }
        waitForCard()

        compose.onNodeWithText("Santé").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()
        assertTopToBottom(
            "Suivi" to compose.onNodeWithTag(UiTags.breadcrumbMenu("suivi")),
            "Nutrition" to compose.onNodeWithTag(UiTags.breadcrumbMenu("nutrition")),
            "Sport" to compose.onNodeWithTag(UiTags.breadcrumbMenu("sport")),
        )
        compose.onNodeWithTag(UiTags.breadcrumbMenu("sport")).performClick()

        assertEquals(listOf(HealthSection.SPORT), picked)
    }

    @Test
    fun `the bilan comes first, then the period selector, 30 days chosen, then the Poids card with today's values`() {
        compose.setContent { SuiviScreen(viewModel()) }
        waitForCard()
        waitForBilan()

        assertTopToBottom(
            "bilan" to compose.onNodeWithTag(UiTags.NUTRITION_BALANCE),
            "periods" to compose.onNodeWithTag(UiTags.SUIVI_PERIODS),
            "card" to compose.onNodeWithTag(UiTags.WEIGHT_CARD),
        )
        listOf("7 j", "30 j", "90 j", "1 an").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithTag(UiTags.suiviPeriod("30")).assertIsSelected()
        compose.onNodeWithText("Poids").assertIsDisplayed()
        // The Pas and Énergie cards name the same day: the Poids card comes first.
        compose.onAllNodesWithText("Aujourd’hui")[0].assertIsDisplayed()
        compose.onNodeWithText("72,4 kg").assertIsDisplayed()
        compose.onNodeWithText("— 72,6 kg").assertIsDisplayed()
        compose.onNodeWithText("· 21,3 % MG").assertIsDisplayed()
        compose.onNodeWithText("22 sept.").assertIsDisplayed()
        compose.onNodeWithText("21 oct.").assertIsDisplayed()
    }

    @Test
    fun `the fourth card, Moral · fatigue, comes last and shows today's mood and fatigue (MAG-750)`() {
        compose.setContent { SuiviScreen(viewModel()) }
        waitForCard()
        waitForMood()

        compose.onNodeWithText("Moral · fatigue").assertExists()
        compose.onNodeWithTag(UiTags.MOOD_VALUE).assertTextContains("Moral 7")
        compose.onNodeWithTag(UiTags.FATIGUE_VALUE).assertTextContains("Fatigue 4")
        assertTopToBottom(
            "energy" to compose.onNodeWithTag(UiTags.ENERGY_CARD),
            "mood" to compose.onNodeWithTag(UiTags.MOOD_CARD),
        )
    }

    @Test
    fun `a tap on a day of Pas shows that day on the Moral · fatigue card too`() {
        val viewModel = viewModel()
        compose.setContent { SuiviScreen(viewModel) }
        waitForCard()
        waitForMood()

        compose.runOnIdle { viewModel.selectDay(java.time.LocalDate.parse("2026-10-19")) }

        compose.onNodeWithTag(UiTags.MOOD_VALUE).assertTextContains("Moral 6")
        compose.onNodeWithTag(UiTags.FATIGUE_VALUE).assertTextContains("Fatigue 5")
    }

    @Test
    fun `without a mood or a fatigue in the period the card says so, with no line and no verdict`() {
        compose.setContent { SuiviScreen(viewModel(healthDays = emptyList())) }
        waitForCard()

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.MOOD_EMPTY).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Rien de noté sur la période").assertExists()
    }

    @Test
    fun `a tap on a column shows that day's values`() {
        compose.setContent { SuiviScreen(viewModel()) }
        waitForCard()

        compose.onNodeWithContentDescription("lun. 19 oct. : 72,8 kg, tendance 72,8 kg").performClick()

        compose.onAllNodesWithText("lun. 19 oct.")[0].assertIsDisplayed()
        compose.onNodeWithText("72,8 kg").assertIsDisplayed()
        compose.onNodeWithText("· 21,6 % MG").assertIsDisplayed()
        // A day without a weighing says so.
        compose.onNodeWithContentDescription("mar. 20 oct. : pas de pesée, tendance 72,8 kg").performClick()
        compose.onNodeWithTag(UiTags.WEIGHT_VALUE).assertIsDisplayed()
        compose.onNodeWithText("· — MG").assertIsDisplayed()
    }

    @Test
    fun `another period changes the columns and their labels`() {
        compose.setContent { SuiviScreen(viewModel()) }
        waitForCard()

        compose.onNodeWithTag(UiTags.suiviPeriod("365")).performClick()
        compose.onNodeWithText("Octobre 2026").assertIsDisplayed()
        compose.onNodeWithText("nov. 25").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.suiviPeriod("90")).performClick()
        compose.onNodeWithText("Sem. du 15 oct.").assertIsDisplayed()
    }

    @Test
    fun `a long press on a weighed day offers Écarter, which sets its weighing aside`() {
        coEvery { repository.setExcluded("today", true) } returns Result.success(today.copy(excluded = true))
        compose.setContent { SuiviScreen(viewModel()) }
        waitForCard()

        compose.onNodeWithTag(UiTags.weightColumn(29)).performTouchInput { longClick() }
        compose.onNodeWithText("Écarter").performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Pesée écartée").fetchSemanticsNodes().isNotEmpty() }
        coVerify { repository.setExcluded("today", true) }
    }

    @Test
    fun `a failed load offers to try again`() {
        coEvery { repository.getBodyMeasurements(any()) } returns Result.failure(RuntimeException("offline"))
        coEvery { energyRepository.getDailyEnergy(any(), any()) } returns Result.success(emptyList())
        val auth = mockk<AuthRepository>().also { coEvery { it.getUserId() } returns "user-1" }
        val mercure = mockk<MercureService>().also { every { it.subscribe(any()) } returns emptyFlow() }
        coEvery { nutritionRepository.getDailyBalance(any()) } returns Result.failure(RuntimeException("offline"))
        coEvery { healthDayRepository.getDays(any(), any()) } returns Result.success(emptyList())
        compose.setContent { SuiviScreen(SuiviViewModel(repository, energyRepository, nutritionRepository, healthDayRepository, mercure, auth, clock)) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.SUIVI_EMPTY).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pesées indisponibles").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet holds the period selector in its header and the Poids card in the left column`() {
        compose.setContent { SuiviScreen(viewModel()) }
        waitForCard()

        compose.onNodeWithTag(UiTags.SUIVI_PERIODS).assertIsDisplayed()
        compose.onNodeWithText("72,4 kg").assertIsDisplayed()
        val card = compose.onNodeWithTag(UiTags.WEIGHT_CARD).fetchSemanticsNode().boundsInRoot
        val periods = compose.onNodeWithTag(UiTags.SUIVI_PERIODS).fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag(UiTags.SUIVI).fetchSemanticsNode().boundsInRoot
        // The card fills the left half; the selector sits at the right of the header, above the content.
        assert(card.right <= root.center.x + 1f) { "card $card overflows the left column of $root" }
        assert(periods.bottom <= root.top + 1f) { "periods $periods are not in the header" }
        waitForBilan()
        // The bilan atop the right column, the Poids card's neighbour.
        val bilan = compose.onNodeWithTag(UiTags.NUTRITION_BALANCE).fetchSemanticsNode().boundsInRoot
        assert(bilan.left >= root.center.x - 1f) { "bilan $bilan is not in the right column of $root" }
    }

    @Test
    fun `the bilan card shows today's balance and its figures, and a tap opens Nutrition (MAG-748)`() {
        val picked = mutableListOf<HealthSection>()
        compose.setContent { SuiviScreen(viewModel(), onSelectSection = { picked += it }) }
        waitForCard()
        waitForBilan()

        compose.onNodeWithTag(UiTags.NUTRITION_BALANCE_VALUE, useUnmergedTree = true).assertTextEquals("+318 kcal")
        compose.onNodeWithTag(UiTags.NUTRITION_BALANCE_FIGURES, useUnmergedTree = true).assertTextEquals("Mangé 1 500 · Prévu 480 · Dépensé 1 662 kcal")
        compose.onNodeWithTag(UiTags.NUTRITION_BALANCE).performClick()

        assertEquals(listOf(HealthSection.NUTRITION), picked)
    }

    @Test
    fun `without a profile the bilan card shows eaten and planned and leads to Configuration`() {
        val picked = mutableListOf<HealthSection>()
        val noProfile = DailyBalance("2026-10-21", eatenKcal = 500, plannedKcal = 300)
        compose.setContent { SuiviScreen(viewModel(balance = noProfile), onSelectSection = { picked += it }) }
        waitForCard()
        waitForBilan()

        compose.onNodeWithTag(UiTags.NUTRITION_BALANCE_VALUE, useUnmergedTree = true).assertTextEquals("800 kcal")
        compose.onNodeWithTag(UiTags.NUTRITION_BALANCE_FIGURES, useUnmergedTree = true).assertTextEquals("Mangé 500 · Prévu 300 kcal")
        compose.onNodeWithText("Complétez votre profil pour voir le bilan").performClick()

        assertEquals(listOf(HealthSection.CONFIGURATION), picked)
    }
}
