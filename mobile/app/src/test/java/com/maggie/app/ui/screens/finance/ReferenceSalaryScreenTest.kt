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
import com.maggie.app.data.model.DashboardScore
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.MonthlyReview
import com.maggie.app.data.model.ReferenceSalary
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.repository.MonthlyReviewRepository
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
 * The reference salary on the dashboard and in the monthly review (MAG-275).
 *
 * The labels are right or wrong in `ReferenceSalaryTest`; what is checked here
 * is that the card is drawn, says where the figure comes from, and — unknown —
 * never shows an amount and points at the screen that fills it in.
 */
@RunWith(AndroidJUnit4::class)
class ReferenceSalaryScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun openDashboard(reference: ReferenceSalary, onOpen: (String) -> Unit = {}) {
        val dashboard = FinanceDashboard(
            year = 2026,
            month = 10,
            score = DashboardScore(referenceSalary = reference),
        )
        compose.setContent {
            FinanceDashboardScreen(
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
    fun `a declared salary is shown with its source`() {
        openDashboard(ReferenceSalary(monthlyCents = 300000, source = "declared"))

        compose.onNodeWithText("Salaire de référence").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${formatCents(300000)} / mois").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("déclaré sur le matelas").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a measured salary is shown as measured over twelve months`() {
        openDashboard(ReferenceSalary(monthlyCents = 495000, source = "measured"))

        compose.onNodeWithText("${formatCents(495000)} / mois").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("mesuré sur 12 mois").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an unknown salary says non renseigné, links to the cushion and shows no amount`() {
        var opened: String? = null
        openDashboard(ReferenceSalary()) { opened = it }

        compose.onNodeWithTag(UiTags.REFERENCE_SALARY_CARD).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("non renseigné").performScrollTo().assertIsDisplayed()
        // Other cards legitimately show 0 here (an empty balance): the card itself must not.
        compose.onNode(
            hasTestTag(UiTags.REFERENCE_SALARY_CARD) and
                hasAnyDescendant(hasText(formatCents(0), substring = true)),
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
