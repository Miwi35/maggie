package com.maggie.app.ui.screens.finance

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.EnvelopeAdjustment
import com.maggie.app.data.model.EnvelopeAdjustments
import com.maggie.app.data.model.REVIEW_STEP_BALANCE
import com.maggie.app.data.model.REVIEW_STEP_DONE
import com.maggie.app.data.model.REVIEW_STEP_REVIEW
import com.maggie.app.data.model.ReviewProgress
import com.maggie.app.data.model.ReviewQueue
import com.maggie.app.data.model.ReviewQueueItem
import com.maggie.app.data.model.SortResult
import com.maggie.app.data.model.SortSuggestion
import com.maggie.app.data.model.UpcomingProjection
import com.maggie.app.data.repository.BudgetRepository
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.ReviewRepository
import com.maggie.app.data.repository.TransactionRepository
import com.maggie.app.data.repository.UpcomingRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The review activity as it is drawn (MAG-456): the header, one line at a time with its answers, the footer,
 * the end screen — and the tags `22-finance-review-activity` addresses it by. The transitions themselves are
 * `ReviewActivityViewModelTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class ReviewActivityScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val month = "2026-10"
    private val review = mockk<ReviewRepository>()
    private val transactions = mockk<TransactionRepository>()

    private fun line(id: String, label: String, suggestion: SortSuggestion? = null) = ReviewQueueItem(
        id = id,
        label = label,
        amountCents = -1250,
        bookedAt = "2026-10-01",
        accountName = "Compte courant",
        judgeable = true,
        suggestion = suggestion,
    )

    private fun viewModel(
        step: String = REVIEW_STEP_REVIEW,
        items: List<ReviewQueueItem> = listOf(line("t1", "ACTIVITE UN 1111"), line("t2", "ACTIVITE DEUX 2222")),
    ): ReviewActivityViewModel {
        val categories = mockk<CategoryRepository>()
        val budget = mockk<BudgetRepository>()
        val upcoming = mockk<UpcomingRepository>()
        coEvery { categories.getCategories() } returns Result.success(listOf(Category(id = "cat-loisirs", name = "Loisirs")))
        coEvery { budget.getEnvelopeAdjustments(month) } returns
            Result.success(EnvelopeAdjustments(envelopes = listOf(EnvelopeAdjustment(envelopeId = "e1", categoryId = "c1"))))
        coEvery { upcoming.getUpcoming() } returns Result.success(UpcomingProjection())
        coEvery { review.getProgress(month) } returns Result.success(ReviewProgress(month = month, step = step))
        coEvery { review.getQueue(month) } returns Result.success(ReviewQueue(month = month, items = items))
        coEvery { review.markHandled(month, any()) } answers { Result.success(ReviewProgress(month = month)) }
        coEvery { review.moveTo(month, any()) } answers { Result.success(ReviewProgress(month = month, step = secondArg())) }
        coEvery { transactions.sortToCategory(any(), any(), any()) } returns Result.success(SortResult(success = true))
        return ReviewActivityViewModel(
            review,
            transactions,
            categories,
            mockk<CategorizationRuleRepository>(relaxed = true),
            budget,
            upcoming,
            today = LocalDate.of(2026, 10, 25),
        )
    }

    private fun show(model: ReviewActivityViewModel, onQuit: () -> Unit = {}, onOpenMaggie: () -> Unit = {}, twoColumns: Boolean = false) {
        compose.setContent {
            ReviewActivityScreen(
                viewModel = model,
                onQuit = onQuit,
                onOpenMaggie = onOpenMaggie,
                twoColumns = twoColumns,
                balance = { Text("Contenu du bilan") },
                envelopes = { Text("Contenu des enveloppes") },
                upcoming = { Text("Contenu à venir") },
                projects = { Text("Contenu des projets") },
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `the Revue step shows one line, its counter, the next step and Passer for a line with nothing to validate`() {
        show(viewModel())

        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_STEP_TITLE).assertTextEquals("Revue")
        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_NEXT_STEP).assertTextEquals("Ensuite… Bilan")
        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_LABEL).assertTextEquals("ACTIVITE UN 1111")
        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_COUNTER).assertTextEquals("1 / 2")
        compose.onNodeWithText("ACTIVITE DEUX 2222").assertDoesNotExist()
        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_NEXT).assertTextEquals("Passer")
        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_PREVIOUS).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.reviewSegment(REVIEW_STEP_REVIEW)).assertExists()
    }

    @Test
    fun `Passer writes the line as handled and brings the next one`() {
        show(viewModel())

        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_NEXT).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_LABEL).assertTextEquals("ACTIVITE DEUX 2222")
        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_COUNTER).assertTextEquals("2 / 2")
        coVerify(exactly = 1) { review.markHandled(month, "t1") }
    }

    @Test
    fun `a line without suggestion is qualified through the category search`() {
        show(viewModel())

        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_CHANGE).performScrollTo().performClick()
        compose.onNodeWithText("Loisirs").performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { transactions.sortToCategory("t1", "cat-loisirs", false) }
        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_LABEL).assertTextEquals("ACTIVITE DEUX 2222")
    }

    @Test
    fun `a suggestion is offered with Accepter and Changer, side by side on a tablet`() {
        val suggestion = SortSuggestion(categoryId = "cat-loisirs", categoryPath = "Loisirs", reason = "Règle « contient ACTIVITE »")
        show(viewModel(items = listOf(line("t1", "ACTIVITE UN 1111", suggestion))), twoColumns = true)

        compose.onNodeWithText("Maggie propose").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_NEXT).assertTextEquals("Valider")
        compose.onNodeWithText("Règle « contient ACTIVITE »").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_ACCEPT).performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { transactions.sortToCategory("t1", "cat-loisirs", false) }
    }

    @Test
    fun `a spend offers its satisfaction`() {
        show(viewModel())

        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_KEEP).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_AVOIDABLE).assertExists()
    }

    @Test
    fun `the other steps draw the content they are given, with Suivant`() {
        show(viewModel(step = REVIEW_STEP_BALANCE))

        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_STEP_TITLE).assertTextEquals("Bilan")
        compose.onNodeWithText("Contenu du bilan").assertIsDisplayed()
        compose.onNodeWithText("Suivant").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_NEXT).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Contenu des enveloppes").assertIsDisplayed()
    }

    @Test
    fun `quit and Maggie call their callbacks`() {
        var quit = 0
        var maggie = 0
        show(viewModel(), onQuit = { quit++ }, onOpenMaggie = { maggie++ })

        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_MAGGIE).performClick()
        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_QUIT).performClick()

        assertEquals(1, maggie)
        assertEquals(1, quit)
    }

    @Test
    fun `a month already reviewed shows the end screen, which restarts from Bilan`() {
        show(viewModel(step = REVIEW_STEP_DONE))

        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_DONE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_NEXT).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.REVIEW_ACTIVITY_RESTART).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Contenu du bilan").assertIsDisplayed()
        coVerify(exactly = 1) { review.moveTo(month, REVIEW_STEP_BALANCE) }
    }
}
