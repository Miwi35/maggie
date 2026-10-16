package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.SortResult
import com.maggie.app.data.model.SortSuggestion
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.model.TransactionToSort
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.TransactionRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The card as it is drawn, and the tags `13-finance-to-sort` addresses it by (MAG-423).
 *
 * `ToSortViewModelTest` says what each answer does to the state; this one says that the
 * suggestion and its reason reach the screen, that the buttons send what the card shows,
 * and that « Autre catégorie » offers the whole path of a heading, three levels deep.
 */
@RunWith(AndroidJUnit4::class)
class ToSortScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val carrefour = TransactionToSort(
        id = "tx-1",
        label = "CARREFOUR MARKET 1234",
        amountCents = -5320,
        bookedAt = "2026-10-12",
        accountName = "Courant",
        suggestion = SortSuggestion("cat-courses", "Courses", "rule", "Règle « contient CARREFOUR »"),
    )

    private val categories = listOf(
        Category(id = "cat-courses", name = "Courses"),
        Category(id = "cat-loisirs", name = "Loisirs"),
        Category(id = "cat-bars", name = "Bars", parent = "/api/categories/cat-loisirs"),
        Category(id = "cat-obsydian", name = "Obsydian", parent = "/api/categories/cat-bars"),
    )

    private val transactions = mockk<TransactionRepository>()

    private fun viewModel(pile: List<TransactionToSort> = listOf(carrefour)): ToSortViewModel {
        val categoryRepository = mockk<CategoryRepository>()
        coEvery { categoryRepository.getCategories() } returns Result.success(categories)
        coEvery { transactions.getToSort() } returns Result.success(ToSortPile(pile.size, pile))
        coEvery { transactions.sortToCategory(any(), any(), any()) } returns Result.success(SortResult(success = true))

        return ToSortViewModel(transactions, categoryRepository)
    }

    @Test
    fun `the card says where the line would go and why`() {
        val model = viewModel()
        compose.setContent { ToSortScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithTag(UiTags.TO_SORT_CARD).assertIsDisplayed()
        compose.onNodeWithText("CARREFOUR MARKET 1234").assertIsDisplayed()
        compose.onNodeWithText("→ Courses").assertIsDisplayed()
        compose.onNodeWithText("Règle « contient CARREFOUR »").assertIsDisplayed()
        compose.onNodeWithText("1 ligne à trier").assertIsDisplayed()
    }

    @Test
    fun `validating with the switch on asks for the rule as well`() {
        val model = viewModel()
        compose.setContent { ToSortScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithTag(UiTags.TO_SORT_ALWAYS).performClick()
        compose.onNodeWithTag(UiTags.TO_SORT_VALIDATE).performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { transactions.sortToCategory("tx-1", "cat-courses", true) }
    }

    @Test
    fun `another category is found by its whole path`() {
        val model = viewModel()
        compose.setContent { ToSortScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithTag(UiTags.TO_SORT_OTHER).performClick()
        compose.onNodeWithText("Loisirs › Bars › Obsydian").assertIsDisplayed()
        compose.onNodeWithText("Loisirs › Bars › Obsydian").performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { transactions.sortToCategory("tx-1", "cat-obsydian", false) }
    }

    @Test
    fun `an empty pile says so`() {
        val model = viewModel(pile = emptyList())
        compose.setContent { ToSortScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithTag(UiTags.TO_SORT_EMPTY).assertIsDisplayed()
        compose.onNodeWithText("Rien à trier").assertIsDisplayed()
    }
}
