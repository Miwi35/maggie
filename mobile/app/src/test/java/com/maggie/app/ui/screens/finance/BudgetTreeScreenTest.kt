package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.BudgetLine
import com.maggie.app.data.model.BudgetStatus
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.OneOffBudgetLine
import com.maggie.app.data.model.OutsideMonthlyBudget
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.repository.BudgetRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.TransactionRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The envelope tree on the phone: folded at first, unfolded by touch, read-only (MAG-418). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BudgetTreeScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val transactionRepository = mockk<TransactionRepository>()

    private val loisirs = BudgetLine(
        id = "env-loisirs", categoryId = "cat-loisirs", categoryName = "Loisirs", categoryPath = "Loisirs",
        amountCents = 30000, year = 2026, month = 10, spentCents = 4000, consumedCents = 4000,
        remainingCents = 26000, availableCents = 26000,
    )
    private val obsydian = BudgetLine(
        id = "env-obsydian", categoryId = "cat-obsydian", categoryName = "Obsydian",
        categoryPath = "Loisirs › Bars › Obsydian", parentEnvelopeId = "env-loisirs",
        amountCents = 10000, year = 2026, month = 10, spentCents = 4000, consumedCents = 4000,
        remainingCents = 6000, availableCents = 6000,
    )

    private fun open(toQualify: ToSortPile? = null, onOpenToQualify: () -> Unit = {}) {
        val budgetRepository = mockk<BudgetRepository>()
        coEvery { budgetRepository.getBudgetStatus(any(), any()) } returns Result.success(
            BudgetStatus(
                year = 2026,
                month = 10,
                budgets = listOf(loisirs, obsydian),
                outsideMonthlyBudget = OutsideMonthlyBudget(
                    oneOff = listOf(
                        OneOffBudgetLine(
                            id = "env-hellfest", name = "Hellfest 2026", amountCents = 40000,
                            spentCents = 8900, consumedCents = 8900, remainingCents = 31100, availableCents = 31100,
                        ),
                    ),
                ),
            ),
        )
        val categoryRepository = mockk<CategoryRepository>()
        coEvery { categoryRepository.getCategories() } returns Result.success(
            listOf(
                Category(id = "cat-loisirs", name = "Loisirs"),
                Category(id = "cat-bars", name = "Bars", parent = "/api/categories/cat-loisirs"),
                Category(id = "cat-obsydian", name = "Obsydian", parent = "/api/categories/cat-bars"),
            ),
        )
        val authRepository = mockk<AuthRepository>()
        coEvery { authRepository.getUserId() } returns "user-1"
        val mercureService = mockk<MercureService>()
        every { mercureService.subscribe(any()) } returns emptyFlow()

        val viewModel = BudgetViewModel(
            budgetRepository, categoryRepository, transactionRepository, mercureService, authRepository,
            LocalDate.of(2026, 10, 15),
        )
        compose.setContent {
            BudgetScreen(viewModel = viewModel, onBack = {}, toQualify = toQualify, onOpenToQualify = onOpenToQualify)
        }
        compose.waitForIdle()
    }

    @Test
    fun `Loisirs unfolds into Bars - Obsydian and folds back`() {
        open()

        compose.onNodeWithText("Loisirs").assertIsDisplayed()
        compose.onNodeWithText("Bars › Obsydian").assertDoesNotExist()

        compose.onNodeWithContentDescription("Déplier Loisirs").performClick()
        compose.onNodeWithText("Bars › Obsydian").assertIsDisplayed()

        compose.onNodeWithContentDescription("Replier Loisirs").performClick()
        compose.onNodeWithText("Bars › Obsydian").assertDoesNotExist()
    }

    @Test
    fun `the pill says how many lines wait to be qualified and opens them`() {
        var opened = 0
        open(toQualify = ToSortPile(total = 3, totalCents = 6520), onOpenToQualify = { opened++ })

        compose.onNodeWithTag(UiTags.TO_QUALIFY_PILL).assertIsDisplayed()
        compose.onNodeWithText("À qualifier · 3 · ${formatCents(6520)}").assertExists()

        compose.onNodeWithTag(UiTags.TO_QUALIFY_PILL).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `no pill when nothing waits to be qualified`() {
        open(toQualify = ToSortPile(total = 0))

        compose.onNodeWithTag(UiTags.TO_QUALIFY_PILL).assertDoesNotExist()
    }

    @Test
    fun `the one-off Hellfest sits apart from the month`() {
        open()

        compose.onNodeWithText("Hors budget mensuel").assertExists()
        compose.onNodeWithText("Hellfest 2026").assertExists()
    }

    @Test
    fun `the phone offers neither a new envelope nor a deletion`() {
        open()

        compose.onNodeWithContentDescription("Nouvelle enveloppe").assertDoesNotExist()
        compose.onNodeWithContentDescription("Supprimer").assertDoesNotExist()
        compose.onNodeWithText("Les enveloppes se règlent sur la tablette ou le web.").assertExists()
    }

    @Test
    fun `touching Loisirs lists the month's lines`() {
        coEvery { transactionRepository.getEnvelopeSpends(any(), 2026, 10) } returns Result.success(
            listOf(Transaction(id = "t-1", label = "OBSYDIAN BAR", amountCents = -4000, bookedAt = "2026-10-10T00:00:00+00:00")),
        )
        open()

        compose.onNodeWithText("Loisirs").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("OBSYDIAN BAR").assertIsDisplayed()
    }

    @Test
    fun `an envelope with no line says so`() {
        coEvery { transactionRepository.getEnvelopeSpends(any(), any(), any()) } returns Result.success(emptyList())
        open()

        compose.onNodeWithText("Loisirs").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Aucune dépense sur cette période.").assertIsDisplayed()
    }
}
