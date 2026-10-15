package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.api.EnvelopeCreateRequest
import com.maggie.app.data.api.EnvelopeUpdateRequest
import com.maggie.app.data.model.BudgetLine
import com.maggie.app.data.model.BudgetStatus
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.model.Season
import com.maggie.app.data.repository.BudgetRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The tablet's Budgets screen: the tree of categories beside the editor of the one touched (MAG-419). */
@RunWith(AndroidJUnit4::class)
class BudgetConfigurationScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val budgetRepository = mockk<BudgetRepository>()

    private val winter = Season(11, 2, 4500)

    private fun open(showsDetailPane: Boolean) {
        coEvery { budgetRepository.getBudgetStatus(any(), any()) } returns Result.success(
            BudgetStatus(
                year = 2026,
                month = 11,
                budgets = listOf(
                    BudgetLine(
                        id = "env-loisirs", categoryId = "cat-loisirs", categoryName = "Loisirs", categoryPath = "Loisirs",
                        amountCents = 30000, year = 2026, month = 11, consumedCents = 12000, remainingCents = 18000,
                    ),
                    BudgetLine(
                        // November is in the winter season: the line carries 45 €, the envelope stores 20 €.
                        id = "env-cinema", categoryId = "cat-cinema", categoryName = "Cinéma",
                        categoryPath = "Loisirs › Cinéma", parentEnvelopeId = "env-loisirs",
                        amountCents = 4500, year = 2026, month = 11, season = winter, consumedCents = 5000,
                        isOverspent = true, remainingCents = -500,
                    ),
                ),
            ),
        )
        coEvery { budgetRepository.getEnvelope("env-cinema") } returns Result.success(
            Envelope(
                id = "env-cinema", amountCents = 2000, year = 2026, month = 11,
                category = "/api/categories/cat-cinema", seasonalAmounts = listOf(winter),
            ),
        )
        coEvery { budgetRepository.updateEnvelope(any(), any()) } returns Result.success(Envelope(id = "env-cinema"))
        coEvery { budgetRepository.createEnvelope(any()) } returns
            Result.success(Envelope(id = "env-obsydian", amountCents = 10000, year = 2026, month = 11))
        val categoryRepository = mockk<CategoryRepository>()
        coEvery { categoryRepository.getCategories() } returns Result.success(
            listOf(
                Category(id = "cat-loisirs", name = "Loisirs"),
                Category(id = "cat-cinema", name = "Cinéma", parent = "/api/categories/cat-loisirs"),
                Category(id = "cat-bars", name = "Bars", parent = "/api/categories/cat-loisirs"),
                Category(id = "cat-obsydian", name = "Obsydian", parent = "/api/categories/cat-bars"),
            ),
        )

        val viewModel = BudgetViewModel(budgetRepository, categoryRepository, LocalDate.of(2026, 11, 15))
        compose.setContent {
            BudgetScreen(viewModel = viewModel, onBack = {}, showsDetailPane = showsDetailPane)
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `the tablet draws the tree with and without envelopes, and asks to touch a category`() {
        open(showsDetailPane = true)

        compose.onNodeWithTag(UiTags.BUDGET_TREE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.budgetNode("Loisirs")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.budgetNode("Obsydian")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.budgetNode("Obsydian")).assertTextContains("Sans enveloppe")
        compose.onNodeWithTag(UiTags.budgetNode("Cinéma")).assertTextContains("Dépassement", substring = true)
        compose.onNodeWithTag(UiTags.BUDGET_OUTSIDE_MONTHLY).assertIsDisplayed()
        compose.onNodeWithText("Nouvelle ponctuelle").assertIsDisplayed()
        compose.onNodeWithText("Touchez une catégorie pour régler son enveloppe.").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BUDGET_EDITOR).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `touching Obsydian opens the create editor, and creating sends the category and the period`() {
        open(showsDetailPane = true)

        compose.onNodeWithTag(UiTags.budgetNode("Obsydian")).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.BUDGET_EDITOR).assertIsDisplayed()
        compose.onNodeWithText("Créer une enveloppe").assertIsDisplayed()
        compose.onNodeWithTag("budget_editor_amount").performTextInput("100")
        compose.onNodeWithText("Créer").performScrollTo().performClick()
        compose.waitForIdle()

        coVerify {
            budgetRepository.createEnvelope(
                EnvelopeCreateRequest(
                    category = "/api/categories/cat-obsydian", amountCents = 10000, year = 2026, mode = "monthly", month = 11,
                ),
            )
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `touching Cinema shows the stored base amount and seasons, not the season's amount`() {
        open(showsDetailPane = true)

        compose.onNodeWithTag(UiTags.budgetNode("Cinéma")).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("budget_editor_amount").assertTextContains("20")
        compose.onNodeWithTag(UiTags.BUDGET_SEASONS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Enregistrer").performScrollTo().performClick()
        compose.waitForIdle()

        // The base amount goes back as it was, not the 45 € of the season the month is in.
        coVerify {
            budgetRepository.updateEnvelope(
                "env-cinema",
                EnvelopeUpdateRequest(amountCents = 2000, mode = "monthly", month = 11, seasonalAmounts = listOf(winter)),
            )
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a threshold typed in the editor is written with the envelope`() {
        open(showsDetailPane = true)

        compose.onNodeWithTag(UiTags.budgetNode("Cinéma")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("alert_threshold_field").performScrollTo().performTextClearance()
        compose.onNodeWithTag("alert_threshold_field").performTextInput("90")
        compose.onNodeWithText("Enregistrer").performScrollTo().performClick()
        compose.waitForIdle()

        coVerify {
            budgetRepository.updateEnvelope(
                "env-cinema",
                EnvelopeUpdateRequest(
                    amountCents = 2000, mode = "monthly", month = 11,
                    seasonalAmounts = listOf(winter), alertThresholdPercent = 90,
                ),
            )
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the phone keeps the consultation, no tree and no editor pane`() {
        open(showsDetailPane = false)

        compose.onNodeWithText("Cinéma").assertExists()
        compose.onNodeWithTag(UiTags.BUDGET_TREE).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.budgetNode("Obsydian")).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.BUDGET_EDITOR).assertDoesNotExist()
        compose.onNode(hasText("Sans enveloppe")).assertDoesNotExist()
    }
}
