package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.ConsumedVsReference
import com.maggie.app.data.model.BudgetTile
import com.maggie.app.data.model.ExpectedSalary
import com.maggie.app.data.model.FinanceTiles
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.MonthlyReview
import com.maggie.app.data.model.ReferenceSalary
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.repository.MonthlyReviewRepository
import com.maggie.app.screentest.FakeMonthBalance
import com.maggie.app.screentest.FakeUpcoming
import com.maggie.app.screentest.FakeLoadedFinanceDashboard
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
 * The reference salary in the Budget tile of the dashboard and in the monthly review (MAG-275, MAG-420).
 *
 * The labels are right or wrong in `ReferenceSalaryTest`; what is checked here
 * is that the card is drawn, says where the figure comes from, and — unknown —
 * never shows an amount and points at the screen that fills it in.
 */
@RunWith(AndroidJUnit4::class)
class ReferenceSalaryScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun openDashboard(expected: ExpectedSalary?, onOpen: (String) -> Unit = {}) {
        val dashboard = FinanceDashboard(
            year = 2026,
            month = 10,
            tiles = FinanceTiles(budget = BudgetTile(expectedSalary = expected)),
        )
        compose.setContent {
            FinanceDashboardScreen(
                monthBalanceViewModel = FakeMonthBalance().viewModel,
                upcomingViewModel = FakeUpcoming().viewModel,
                viewModel = FakeLoadedFinanceDashboard(dashboard).viewModel,
                onBack = {},
                onOpen = onOpen,
            )
        }
    }

    private fun openReview(review: MonthlyReview) {
        val repository = mockk<MonthlyReviewRepository>()
        coEvery { repository.getReview(any(), any()) } returns Result.success(review)
        compose.setContent {
            MonthlyReviewScreen(
                viewModel = MonthlyReviewViewModel(repository, LocalDate.of(2026, 10, 13)),
                onBack = {},
            )
        }
    }

    @Test
    fun `a declared salary is shown with its source and the day it is expected`() {
        openDashboard(ExpectedSalary(monthlyCents = 300000, source = "declared", expectedOn = "2026-10-28"))

        compose.onNodeWithText("Salaire attendu").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${formatCents(300000)} · le 28 octobre").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("déclaré sur le matelas").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a measured salary is shown as measured over twelve months`() {
        openDashboard(ExpectedSalary(monthlyCents = 495000, source = "measured"))

        compose.onNodeWithText(formatCents(495000)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("mesuré sur 12 mois").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an undeclared salary says so, links to the cushion and shows no amount`() {
        var opened: String? = null
        openDashboard(null) { opened = it }

        compose.onNodeWithTag(UiTags.FINANCE_TILE_EXPECTED_SALARY, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("salaire non déclaré").performScrollTo().assertIsDisplayed()
        // Other parts of the tile legitimately show 0 here (an empty balance): the salary itself must not.
        compose.onNode(
            hasTestTag(UiTags.FINANCE_TILE_EXPECTED_SALARY) and
                hasAnyDescendant(hasText(formatCents(0), substring = true)),
            useUnmergedTree = true,
        ).assertDoesNotExist()

        compose.onNodeWithTag(UiTags.REFERENCE_SALARY_LINK).performScrollTo().performClick()
        assertEquals("cushion", opened)
    }

    @Test
    fun `the review shows the share of the reference salary consumed`() {
        openReview(
            MonthlyReview(
                referenceSalary = ReferenceSalary(monthlyCents = 495000, source = "measured"),
                consumedVsReference = ConsumedVsReference(consumedCents = 247500, percentOfReference = 50),
            ),
        )

        compose.onNodeWithTag(UiTags.REFERENCE_SHARE).assertIsDisplayed()
        compose.onNodeWithText(
            "50 % du salaire de référence consommés (${formatCents(495000)}, mesuré sur 12 mois)",
        ).assertIsDisplayed()
    }

    @Test
    fun `the review shows nothing about the reference when it is unknown`() {
        openReview(MonthlyReview(consumedVsReference = ConsumedVsReference(consumedCents = 110000)))

        compose.onNodeWithText("Revue mensuelle").assertIsDisplayed()
        compose.onAllNodes(hasTestTag(UiTags.REFERENCE_SHARE)).assertCountEquals(0)
    }
}
