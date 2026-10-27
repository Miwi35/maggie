package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.FinanceTiles
import com.maggie.app.data.model.IndependenceCounter
import com.maggie.app.data.model.CoveragePoint
import com.maggie.app.data.model.IndependenceProjection
import com.maggie.app.data.model.IndependenceRente
import com.maggie.app.data.model.InvestmentRentes
import com.maggie.app.data.model.PlacementRentes
import com.maggie.app.data.model.RenteSources
import com.maggie.app.data.model.PlanningTile
import com.maggie.app.data.model.formatCents
import com.maggie.app.screentest.FakeUpcoming
import com.maggie.app.screentest.FakeLoadedFinanceDashboard
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The independence counter, in the Planification tile of the dashboard (MAG-46, MAG-420).
 *
 * A percentage is right or wrong in `IndependenceCounterTest` next door; what
 * cannot be said there is that the card is drawn at all, and that it carries
 * the two terms the percentage is made of — a ratio shown alone cannot be
 * checked by the person reading it.
 */
@RunWith(AndroidJUnit4::class)
class IndependenceCardScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun dashboardWith(counter: IndependenceCounter) =
        FinanceDashboard(
            year = 2026,
            month = 10,
            tiles = FinanceTiles(planning = PlanningTile(independence = counter)),
        )

    private fun open(counter: IndependenceCounter) {
        compose.setContent {
            FinanceDashboardScreen(
                upcomingViewModel = FakeUpcoming().viewModel,
                viewModel = FakeLoadedFinanceDashboard(dashboardWith(counter)).viewModel,
                onOpen = {},
            )
        }
    }

    @Test
    fun `the card shows the coverage, both its terms and the rentes behind it`() {
        open(
            IndependenceCounter(
                coveragePercent = 80,
                lifestyleCents = 10000,
                passiveIncomeCents = 8000,
                gapCents = 2000,
                isMeasurable = true,
                hasPassiveIncomeCategories = true,
                byCategory = listOf(
                    IndependenceRente(
                        categoryId = "1",
                        categoryName = "Loyers perçus",
                        monthlyCents = 6000,
                        sharePercent = 75,
                    ),
                ),
            ),
        )

        compose.onNodeWithText("Indépendance financière").performScrollTo().assertIsDisplayed()
        // Amounts are built with formatCents, not written out: the separator
        // is the JVM's locale, and a literal would pass on one machine only.
        compose.onNodeWithText("80 % du train de vie — il manque ${formatCents(2000)} par mois")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(
            "${formatCents(8000)} de rentes sur ${formatCents(10000)} de train de vie, " +
                "mesurés sur 3 mois",
        )
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Loyers perçus").performScrollTo().assertIsDisplayed()
    }

    /** A rente declared and no month measured yet: the state is said by the summary alone, no sentence under it. */
    @Test
    fun `with a rente but no history the card says so without explaining the window`() {
        open(IndependenceCounter(hasPassiveIncomeCategories = true))

        compose.onNodeWithText("Pas encore de train de vie mesuré")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onAllNodesWithText("Le compteur se calcule", substring = true).assertCountEquals(0)
    }

    /** No rente declared is something to do: a plain link to the categories, no sentence. */
    @Test
    fun `without a declared rente the card offers the link and no explanation`() {
        open(IndependenceCounter(lifestyleCents = 10000, isMeasurable = true))

        compose.onNodeWithText("Aucune catégorie déclarée comme rente")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onAllNodesWithText("Une rente se déclare", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Déclarer une rente").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0 % du train de vie — il manque ${formatCents(0)} par mois")
            .assertDoesNotExist()
    }

    private fun measurable(projection: IndependenceProjection, isReached: Boolean = false) = IndependenceCounter(
        coveragePercent = if (isReached) 120 else 40,
        lifestyleCents = 10000,
        passiveIncomeCents = if (isReached) 12000 else 4000,
        isMeasurable = true,
        hasPassiveIncomeCategories = true,
        isReached = isReached,
        projection = projection,
    )

    @Test
    fun `the card gives the date N, the curve and the dated milestones`() {
        open(
            measurable(
                IndependenceProjection(
                    startMonth = "2026-10",
                    nDate = "2045-09",
                    curve = listOf(CoveragePoint("2026-10", 0.4), CoveragePoint("2045-09", 1.0)),
                    milestones = mapOf("25" to "2026-10", "50" to "2028-03", "75" to "2036-06", "100" to "2045-09"),
                ),
            ),
        )

        compose.onNodeWithText("Date N · septembre 2045").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INDEPENDENCE_CURVE, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INDEPENDENCE_MILESTONES, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("25 % · atteint").assertExists()
        compose.onNodeWithText("50 % · mars 2028").assertExists()
        compose.onNodeWithText("75 % · juin 2036").assertExists()
        compose.onNodeWithText("100 % · septembre 2045").assertExists()
    }

    @Test
    fun `when N is not reached within 20 years the card states it without advice`() {
        open(
            measurable(
                IndependenceProjection(
                    startMonth = "2026-10",
                    curve = listOf(CoveragePoint("2026-10", 0.4), CoveragePoint("2046-09", 0.7)),
                    milestones = mapOf("25" to "2026-10", "50" to "2031-02", "75" to null, "100" to null),
                ),
            ),
        )

        compose.onNodeWithText("N non atteinte d'ici 20 ans").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("75 % · non atteint d'ici 20 ans").assertExists()
        compose.onNodeWithText("100 % · non atteint d'ici 20 ans").assertExists()
        compose.onNodeWithText("50 % · février 2031").assertExists()
    }

    @Test
    fun `once the rentes cover the train de vie the card says so and every step is reached`() {
        open(
            measurable(
                IndependenceProjection(
                    startMonth = "2026-10",
                    nDate = "2026-10",
                    curve = listOf(CoveragePoint("2026-10", 1.0)),
                    milestones = mapOf("25" to "2026-10", "50" to "2026-10", "75" to "2026-10", "100" to "2026-10"),
                ),
                isReached = true,
            ),
        )

        compose.onNodeWithText("Les rentes couvrent déjà le train de vie").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("100 % · atteint").assertExists()
        compose.onNodeWithText("Date N", substring = true).assertDoesNotExist()
    }

    @Test
    fun `without a measured train de vie the card shows no date, curve or milestone`() {
        open(
            IndependenceCounter(
                hasPassiveIncomeCategories = true,
                projection = IndependenceProjection(startMonth = "2026-10"),
            ),
        )

        compose.onNodeWithText("Pas encore de train de vie mesuré").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INDEPENDENCE_DATE_N).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.INDEPENDENCE_CURVE, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.INDEPENDENCE_MILESTONES, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the card lists the simulated rents behind the projection, labelled as a simulation`() {
        open(
            measurable(
                IndependenceProjection(startMonth = "2026-10", nDate = "2045-09"),
            ).copy(
                renteSources = RenteSources(
                    placements = PlacementRentes(count = 3, monthlyRentCents = 31500),
                    investments = InvestmentRentes(count = 1, monthlyRentCents = 16000),
                ),
            ),
        )

        compose.onNodeWithText("Sources des rentes à venir").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Placements · 3 · ≈ 315\u00A0€ / mois à terme").assertExists()
        compose.onNodeWithText("Investissements · 1 · ≈ 160\u00A0€ / mois de loyer").assertExists()
        compose.onNodeWithText("Simulation · retrait de 4 % par an sur le capital placé").assertExists()
    }

    @Test
    fun `with investments only the caption of the 4 percent withdrawal is not drawn`() {
        open(
            measurable(IndependenceProjection(startMonth = "2026-10", nDate = "2045-09")).copy(
                renteSources = RenteSources(investments = InvestmentRentes(count = 1, monthlyRentCents = 16000)),
            ),
        )

        compose.onNodeWithText("Investissements · 1 · ≈ 160\u00A0€ / mois de loyer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Placements · ", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Simulation", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the sources are drawn even when no train de vie is measured yet`() {
        open(
            IndependenceCounter(
                hasPassiveIncomeCategories = true,
                renteSources = RenteSources(placements = PlacementRentes(count = 1, monthlyRentCents = 23807)),
            ),
        )

        compose.onNodeWithText("Pas encore de train de vie mesuré").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sources des rentes à venir").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Placements · 1 · ≈ 238\u00A0€ / mois à terme").assertExists()
    }

    @Test
    fun `without a placement or an investment the section is not drawn`() {
        open(measurable(IndependenceProjection(startMonth = "2026-10", nDate = "2045-09")))

        compose.onNodeWithText("Date N · septembre 2045").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INDEPENDENCE_SOURCES, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Sources des rentes à venir").assertDoesNotExist()
        compose.onNodeWithText("Simulation", substring = true).assertDoesNotExist()
    }
}
