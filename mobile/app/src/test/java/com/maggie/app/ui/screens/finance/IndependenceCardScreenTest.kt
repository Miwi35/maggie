package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.IndependenceCounter
import com.maggie.app.data.model.IndependenceRente
import com.maggie.app.data.model.formatCents
import com.maggie.app.screentest.FakeLoadedFinanceDashboard
import com.maggie.app.screentest.ScreenRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The independence counter on the dashboard (MAG-46).
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
        FinanceDashboard(year = 2026, month = 10, independence = counter)

    private fun open(counter: IndependenceCounter) {
        compose.setContent {
            FinanceDashboardScreen(
                viewModel = FakeLoadedFinanceDashboard(dashboardWith(counter)).viewModel,
                onBack = {},
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

    /**
     * A rente declared and no month measured yet is a different state: the
     * hint must say how the window works, not how to declare a rente again.
     */
    @Test
    fun `with a rente but no history the card explains the window`() {
        open(IndependenceCounter(hasPassiveIncomeCategories = true))

        compose.onNodeWithText("Pas encore de train de vie mesuré")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Le compteur se calcule sur les 3 mois complets précédents.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** No rente declared is something to do, not a 0 % to read. */
    @Test
    fun `without a declared rente the card says where to start`() {
        open(IndependenceCounter(lifestyleCents = 10000, isMeasurable = true))

        compose.onNodeWithText("Aucune catégorie déclarée comme rente")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("0 % du train de vie — il manque ${formatCents(0)} par mois")
            .assertDoesNotExist()
    }
}
