package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.FinanceTiles
import com.maggie.app.data.model.IndependenceCounter
import com.maggie.app.data.model.IndependenceRente
import com.maggie.app.data.model.PlanningTile
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.repository.FinanceDashboardRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The independence aspect of Planification (MAG-518): the counter of the dashboard's tile as the
 * content of a tab. Four things to say — no rente declared, nothing to measure yet, how far the
 * rentes go, and that they go all the way — and never a bare percentage.
 */
@RunWith(AndroidJUnit4::class)
class IndependenceContentTest {

    @get:Rule
    val compose = ScreenRule()

    private val loyers = IndependenceRente(categoryId = "c1", categoryName = "Loyers perçus", monthlyCents = 6000, sharePercent = 75)

    private fun open(state: IndependenceUiState, onDeclare: () -> Unit = {}, onRetry: () -> Unit = {}) {
        compose.setContent { IndependenceBody(state, onDeclare, onRetry) }
    }

    private fun counter(vararg changes: (IndependenceCounter) -> IndependenceCounter) =
        changes.fold(IndependenceCounter(hasPassiveIncomeCategories = true, isMeasurable = true)) { acc, change -> change(acc) }

    @Test
    fun `without a rente it says how to declare one and offers to do it`() {
        var declared = 0
        open(IndependenceUiState(counter = IndependenceCounter()), onDeclare = { declared++ })

        compose.onNodeWithText("Aucune rente déclarée").assertIsDisplayed()
        compose.onNodeWithText("Déclarer une rente").assertIsDisplayed().performClick()

        assertEquals(1, declared)
        compose.onNodeWithTag(UiTags.INDEPENDENCE_PERCENT).assertDoesNotExist()
    }

    @Test
    fun `when it cannot be measured it says how many months it needs, and shows no percentage`() {
        open(IndependenceUiState(counter = IndependenceCounter(hasPassiveIncomeCategories = true, sampleMonths = 3)))

        compose.onNodeWithText(
            "Pas encore de train de vie mesuré : il se calcule sur les 3 mois complets précédents.",
        ).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INDEPENDENCE_PERCENT).assertDoesNotExist()
        compose.onNodeWithText("Déclarer une rente").assertDoesNotExist()
    }

    @Test
    fun `measured, it shows the percentage, the bar, both terms, the gap and the next milestone`() {
        open(
            IndependenceUiState(
                counter = counter(
                    { it.copy(coveragePercent = 80, lifestyleCents = 10000, passiveIncomeCents = 8000, gapCents = 2000) },
                    { it.copy(nextMilestonePercent = 100, nextMilestoneGapCents = 2000, byCategory = listOf(loyers)) },
                ),
            ),
        )

        compose.onNodeWithTag(UiTags.INDEPENDENCE_PERCENT).assertIsDisplayed()
        compose.onNodeWithText("80 %").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INDEPENDENCE_PROGRESS).assertIsDisplayed()
        // Amounts go through formatCents: the separator is the JVM's locale.
        compose.onNodeWithText(
            "${formatCents(8000)} de rentes sur ${formatCents(10000)} de train de vie, mesurés sur 3 mois",
        ).assertIsDisplayed()
        compose.onNodeWithText(
            "Il manque ${formatCents(2000)} par mois — prochain palier 100 %, à ${formatCents(2000)} près",
        ).assertIsDisplayed()
        compose.onNodeWithText("Loyers perçus").assertIsDisplayed()
        compose.onNodeWithText("${formatCents(6000)} / mois", substring = true).assertIsDisplayed()
        compose.onNodeWithText("75 %", substring = true).assertIsDisplayed()
    }

    @Test
    fun `measured without a next milestone, the gap stands alone`() {
        open(IndependenceUiState(counter = counter({ it.copy(coveragePercent = 40, gapCents = 5000) })))

        compose.onNodeWithTag(UiTags.INDEPENDENCE_GAP).assertIsDisplayed()
        compose.onNodeWithText("Il manque ${formatCents(5000)} par mois").assertIsDisplayed()
    }

    @Test
    fun `when the rentes cover the lifestyle it says so instead of a gap`() {
        open(
            IndependenceUiState(
                counter = counter({ it.copy(coveragePercent = 120, lifestyleCents = 10000, passiveIncomeCents = 12000, isReached = true) }),
            ),
        )

        compose.onNodeWithText("120 %").assertIsDisplayed()
        compose.onNodeWithText("Les rentes couvrent le train de vie.").assertIsDisplayed()
        compose.onNodeWithText("Il manque", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a failed load says so and can be retried`() {
        var retries = 0
        open(IndependenceUiState(error = "Service indisponible"), onRetry = { retries++ })

        compose.onNodeWithText("Service indisponible").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INDEPENDENCE_RETRY).performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `the counter is read from the planning tile of the dashboard of the current month`() {
        val repository = mockk<FinanceDashboardRepository>()
        coEvery { repository.getDashboard(2026, 10) } returns Result.success(
            FinanceDashboard(
                year = 2026,
                month = 10,
                tiles = FinanceTiles(planning = PlanningTile(independence = counter({ it.copy(coveragePercent = 55, gapCents = 4500) }))),
            ),
        )
        val viewModel = IndependenceViewModel(repository) { LocalDate.of(2026, 10, 16) }

        compose.setContent { IndependenceContent(viewModel, onDeclareRente = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("55 %").assertIsDisplayed()
        compose.onNodeWithText("Il manque ${formatCents(4500)} par mois").assertIsDisplayed()
    }

    @Test
    fun `a dashboard that fails leaves the content in its error state`() {
        val repository = mockk<FinanceDashboardRepository>()
        coEvery { repository.getDashboard(any(), any()) } returns Result.failure(IllegalStateException("Service indisponible"))
        val viewModel = IndependenceViewModel(repository) { LocalDate.of(2026, 10, 16) }

        compose.setContent { IndependenceContent(viewModel, onDeclareRente = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("Service indisponible").assertIsDisplayed()
    }
}
