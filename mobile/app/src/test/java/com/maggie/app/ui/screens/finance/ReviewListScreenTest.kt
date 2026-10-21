package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.ControlResult
import com.maggie.app.data.model.OPERATION_RULE
import com.maggie.app.data.model.RETROSPECT_AVOIDABLE
import com.maggie.app.data.model.RETROSPECT_KEEP
import com.maggie.app.data.model.ReviewPeriod
import com.maggie.app.data.model.ReviewPreset
import com.maggie.app.data.model.ReviewQueue
import com.maggie.app.data.model.ReviewQueueItem
import com.maggie.app.data.model.SortResult
import com.maggie.app.data.model.SortSuggestion
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.ContactRepository
import com.maggie.app.data.repository.PeerDebtRepository
import com.maggie.app.data.repository.ReviewRepository
import com.maggie.app.data.repository.TransactionRepository
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
 * The Revue as it is drawn, and the tags `13-finance-review-list` addresses it by (MAG-539).
 *
 * `ReviewListViewModelTest` says what each gesture does to the state; this one says that the pills, the period, the
 * groups, the state pill of each row and the fiche's buttons reach the screen, and that they send what they show.
 */
@RunWith(AndroidJUnit4::class)
class ReviewListScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val carrefour = ReviewQueueItem(
        id = "tx-1",
        label = "CARREFOUR MARKET 1234",
        amountCents = -5320,
        bookedAt = "2026-10-05",
        accountName = "Courant",
        suggestion = SortSuggestion("cat-courses", "Courses", "rule", "Règle « contient CARREFOUR »"),
    )

    private val paul = ReviewQueueItem(
        id = "tx-2",
        label = "BOULANGERIE PAUL",
        amountCents = -450,
        bookedAt = "2026-10-11",
    )

    private val netflix = ReviewQueueItem(
        id = "tx-4",
        label = "NETFLIX.COM",
        recognised = true,
        amountCents = -1399,
        bookedAt = "2026-10-08",
        judgeable = true,
        categoryId = "cat-loisirs",
        categoryPath = "Loisirs",
        qualifiedBy = OPERATION_RULE,
        qualifiedByRuleId = "rule-1",
        operationLabel = "Abonnement vidéo",
    )

    private val salary = ReviewQueueItem(
        id = "tx-5",
        label = "VIREMENT SALAIRE",
        recognised = true,
        amountCents = 250000,
        bookedAt = "2026-10-02",
        categoryId = "cat-salaire",
        categoryPath = "Revenus › Salaire",
    )

    private val categories = listOf(
        Category(id = "cat-courses", name = "Courses"),
        Category(id = "cat-loisirs", name = "Loisirs"),
        Category(id = "cat-bars", name = "Bars", parent = "/api/categories/cat-loisirs"),
        Category(id = "cat-obsydian", name = "Obsydian", parent = "/api/categories/cat-bars"),
    )

    private val reviews = mockk<ReviewRepository>()
    private val transactions = mockk<TransactionRepository>()

    private fun queue(vararg items: ReviewQueueItem) = ReviewQueue(
        month = "2026-10",
        unrecognisedCount = items.count { !it.recognised },
        unrecognisedCents = items.filter { !it.recognised }.sumOf { kotlin.math.abs(it.amountCents) },
        recognisedCount = items.count { it.recognised },
        total = items.size,
        items = items.toList(),
    )

    private fun viewModel(items: List<ReviewQueueItem> = listOf(carrefour, paul, netflix)): ReviewListViewModel {
        val categoryRepository = mockk<CategoryRepository>()
        coEvery { categoryRepository.getCategories() } returns Result.success(categories)
        coEvery { reviews.getList(any(), any(), any(), any()) } returns Result.success(queue(*items.toTypedArray()))
        coEvery { transactions.sortToCategory(any(), any(), any()) } returns Result.success(SortResult(success = true))
        coEvery { transactions.setRetrospect(any(), any()) } returns Result.success(Unit)
        coEvery { transactions.controlRecognised(any(), any()) } returns Result.success(ControlResult(1, 0))

        return ReviewListViewModel(
            reviews,
            transactions,
            categoryRepository,
            mockk<CategorizationRuleRepository>(relaxed = true),
            mockk<ContactRepository>(relaxed = true),
            mockk<PeerDebtRepository>(relaxed = true),
            today = LocalDate.of(2026, 10, 16),
        )
    }

    private fun show(
        model: ReviewListViewModel,
        preset: ReviewPreset? = null,
        onOpenTransaction: (ReviewQueueItem) -> Unit = {},
        onOpenOperation: (ReviewQueueItem) -> Unit = {},
    ) {
        compose.setContent {
            ReviewListScreen(
                viewModel = model,
                onBack = {},
                preset = preset,
                onOpenTransaction = onOpenTransaction,
                onOpenOperation = onOpenOperation,
            )
        }
        compose.waitForIdle()
    }

    /** The list shown and the [row]th line tapped open. */
    private fun showFiche(model: ReviewListViewModel, row: Int = 0, preset: ReviewPreset? = null, onOpenOperation: (ReviewQueueItem) -> Unit = {}) {
        show(model, preset, onOpenOperation = onOpenOperation)
        compose.onAllNodesWithTag(UiTags.REVIEW_LIST_ROW)[row].performClick()
        compose.waitForIdle()
    }

    // The list

    @Test
    fun `the list opens on the three pills with their counts, the month and the two groups`() {
        show(viewModel())

        compose.onNodeWithTag(UiTags.REVIEW_LIST_PILL_ALL).assertTextEquals("Toutes · 3")
        compose.onNodeWithTag(UiTags.REVIEW_LIST_PILL_UNRECOGNISED).assertTextEquals("À qualifier · 2")
        compose.onNodeWithTag(UiTags.REVIEW_LIST_PILL_RECOGNISED).assertTextEquals("Reconnues · 1")
        compose.onNodeWithText("Octobre 2026").assertIsDisplayed()
        compose.onNodeWithText("Depuis la dernière revue").assertIsDisplayed()
        compose.onNodeWithText("Toutes les dates").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_LIST_SECTION_UNRECOGNISED).assertTextEquals("Non reconnues · 2")
        compose.onNodeWithTag(UiTags.REVIEW_LIST_SECTION_RECOGNISED).assertTextEquals("Reconnues · 1")
        compose.onAllNodesWithTag(UiTags.REVIEW_LIST_ROW).assertCountEquals(3)
    }

    @Test
    fun `each row carries its label, its amount and its state pill, À qualifier or Reconnue`() {
        show(viewModel())

        compose.onNodeWithText("CARREFOUR MARKET 1234").assertIsDisplayed()
        compose.onNodeWithText("NETFLIX.COM").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.REVIEW_LIST_ROW_STATE).assertCountEquals(3)
        compose.onAllNodesWithTag(UiTags.REVIEW_LIST_ROW_STATE)[0].assertTextEquals("À qualifier")
        compose.onAllNodesWithTag(UiTags.REVIEW_LIST_ROW_STATE)[1].assertTextEquals("À qualifier")
        compose.onAllNodesWithTag(UiTags.REVIEW_LIST_ROW_STATE)[2].assertTextEquals("Reconnue")
    }

    @Test
    fun `the lines to qualify come before the recognised ones, each by ascending date`() {
        show(viewModel(listOf(netflix, paul, salary, carrefour)))

        // The recognised ones are 02/10 (salary) then 08/10 (Netflix), after the two to qualify (05/10, 11/10).
        val states = (0..3).map { compose.onAllNodesWithTag(UiTags.REVIEW_LIST_ROW_STATE)[it] }
        states[0].assertTextEquals("À qualifier")
        states[1].assertTextEquals("À qualifier")
        states[2].assertTextEquals("Reconnue")
        states[3].assertTextEquals("Reconnue")
        compose.onAllNodesWithTag(UiTags.REVIEW_LIST_ROW)[0].assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_LIST_SECTION_RECOGNISED).assertTextEquals("Reconnues · 2")
    }

    @Test
    fun `a state pill keeps the lines of that state only, the counts stay`() {
        show(viewModel())

        compose.onNodeWithTag(UiTags.REVIEW_LIST_PILL_UNRECOGNISED).performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag(UiTags.REVIEW_LIST_ROW).assertCountEquals(2)
        compose.onNodeWithTag(UiTags.REVIEW_LIST_SECTION_RECOGNISED).assertDoesNotExist()
        compose.onNodeWithText("NETFLIX.COM").assertDoesNotExist()
        compose.onNodeWithTag(UiTags.REVIEW_LIST_PILL_ALL).assertTextEquals("Toutes · 3")

        compose.onNodeWithTag(UiTags.REVIEW_LIST_PILL_RECOGNISED).performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag(UiTags.REVIEW_LIST_ROW).assertCountEquals(1)
        compose.onNodeWithTag(UiTags.REVIEW_LIST_SECTION_UNRECOGNISED).assertDoesNotExist()
        compose.onNodeWithText("NETFLIX.COM").assertIsDisplayed()
    }

    @Test
    fun `the period chips and the month arrows read the period asked`() {
        val model = viewModel()
        show(model)

        compose.onNodeWithTag(UiTags.REVIEW_LIST_MONTH_PREVIOUS).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Septembre 2026").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.REVIEW_LIST_PERIOD_SINCE_REVIEW).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.REVIEW_LIST_PERIOD_ALL).performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { reviews.getList(ReviewPeriod.MONTH, "2026-09", any(), any()) }
        coVerify(exactly = 1) { reviews.getList(ReviewPeriod.SINCE_REVIEW, "2026-09", any(), any()) }
        coVerify(exactly = 1) { reviews.getList(ReviewPeriod.ALL, "2026-09", any(), any()) }
    }

    @Test
    fun `a preset opens the list on its filters`() {
        show(viewModel(), preset = ReviewPreset.TO_SORT)

        compose.onAllNodesWithTag(UiTags.REVIEW_LIST_ROW).assertCountEquals(2)
        compose.onNodeWithText("CARREFOUR MARKET 1234").assertIsDisplayed()
        compose.onNodeWithText("NETFLIX.COM").assertDoesNotExist()
        coVerify(exactly = 1) { reviews.getList(ReviewPeriod.ALL, "2026-10", any(), any()) }
    }

    @Test
    fun `an empty list says so`() {
        show(viewModel(emptyList()))

        compose.onNodeWithTag(UiTags.REVIEW_LIST_EMPTY).assertIsDisplayed()
        compose.onNodeWithText("Aucune ligne pour ces filtres.").assertIsDisplayed()
    }

    @Test
    fun `a period that cannot be read offers to try again`() {
        val model = viewModel()
        coEvery { reviews.getList(any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(RuntimeException("offline")),
            Result.success(queue(carrefour)),
        )
        show(model)

        compose.onNodeWithText("Chargement impossible").assertIsDisplayed()
        compose.onNodeWithText("Réessayer").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag(UiTags.REVIEW_LIST_ROW).assertCountEquals(1)
    }

    // The fiche

    @Test
    fun `a line to qualify opens its fiche, with the suggestion, its reason and the three gestures`() {
        showFiche(viewModel())

        compose.onNodeWithTag(UiTags.REVIEW_FICHE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_STATE).assertTextEquals("À qualifier")
        compose.onNodeWithTag(UiTags.REVIEW_POSITION).assertTextEquals("1 / 3")
        compose.onNodeWithText("→ Courses").assertIsDisplayed()
        compose.onNodeWithText("Règle « contient CARREFOUR »").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_VALIDATE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_OTHER).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_CREATE_OPERATION).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_CONTROL).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_CORRECT).assertDoesNotExist()
    }

    @Test
    fun `a recognised line opens its fiche with its operation and the check gestures`() {
        val opened = mutableListOf<String>()
        showFiche(viewModel(), row = 2, onOpenOperation = { opened += it.id })

        compose.onNodeWithTag(UiTags.REVIEW_FICHE_STATE).assertTextEquals("Reconnue")
        compose.onNodeWithTag(UiTags.REVIEW_POSITION).assertTextEquals("3 / 3")
        compose.onNodeWithText("Abonnement vidéo", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_CONTROL).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_CORRECT).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_VALIDATE).assertDoesNotExist()

        compose.onNodeWithTag(UiTags.REVIEW_FICHE_OPEN_OPERATION).performScrollTo().performClick()

        assertEquals(listOf("tx-4"), opened)
    }

    @Test
    fun `Voir l'opération is offered only when an operation recognised the line`() {
        showFiche(viewModel(listOf(salary)), row = 0)

        compose.onNodeWithTag(UiTags.REVIEW_FICHE_CONTROL).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_OPEN_OPERATION).assertDoesNotExist()
    }

    @Test
    fun `Valider turns the line Reconnue in place, the fiche stays and Suivant opens the next line`() {
        showFiche(viewModel())

        compose.onNodeWithTag(UiTags.REVIEW_FICHE_VALIDATE).performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { transactions.sortToCategory("tx-1", "cat-courses", false) }
        compose.onNodeWithTag(UiTags.REVIEW_FICHE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_STATE).assertTextEquals("Reconnue")
        compose.onNodeWithTag(UiTags.REVIEW_POSITION).assertTextEquals("1 / 3")
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_CONTROL).assertIsDisplayed()

        compose.onNodeWithTag(UiTags.REVIEW_NEXT).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("BOULANGERIE PAUL").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_POSITION).assertTextEquals("2 / 3")
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_STATE).assertTextEquals("À qualifier")
    }

    @Test
    fun `back from the fiche returns to the list with the pills updated`() {
        showFiche(viewModel())
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_VALIDATE).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.REVIEW_BACK_TO_LIST).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.REVIEW_FICHE).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.REVIEW_LIST_PILL_UNRECOGNISED).assertTextEquals("À qualifier · 1")
        compose.onNodeWithTag(UiTags.REVIEW_LIST_PILL_RECOGNISED).assertTextEquals("Reconnues · 2")
        compose.onAllNodesWithTag(UiTags.REVIEW_LIST_ROW).assertCountEquals(3)
    }

    @Test
    fun `Précédent and Suivant walk the filter, disabled at its ends, and a swipe does the same`() {
        showFiche(viewModel())

        compose.onNodeWithTag(UiTags.REVIEW_PREVIOUS).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.REVIEW_NEXT).assertIsEnabled()

        compose.onNodeWithTag(UiTags.REVIEW_NEXT).performClick()
        compose.onNodeWithText("BOULANGERIE PAUL").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.REVIEW_FICHE).performTouchInput { swipeLeft() }
        compose.onNodeWithText("NETFLIX.COM").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_NEXT).assertIsNotEnabled()

        compose.onNodeWithTag(UiTags.REVIEW_FICHE).performTouchInput { swipeRight() }
        compose.onNodeWithText("BOULANGERIE PAUL").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_POSITION).assertTextEquals("2 / 3")
    }

    @Test
    fun `the position counts the filtered list, not the period`() {
        val model = viewModel()
        show(model)
        compose.onNodeWithTag(UiTags.REVIEW_LIST_PILL_UNRECOGNISED).performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag(UiTags.REVIEW_LIST_ROW)[1].performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.REVIEW_POSITION).assertTextEquals("2 / 2")
    }

    @Test
    fun `Correct checks the line, ticks it and disables the button`() {
        showFiche(viewModel(), row = 2)

        compose.onNodeWithTag(UiTags.REVIEW_FICHE_CONTROL).performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { transactions.controlRecognised(listOf("tx-4"), false) }
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_CHECKED).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_CONTROL).assertIsNotEnabled()
    }

    @Test
    fun `Autre catégorie finds a heading by its whole path and files the line under it`() {
        showFiche(viewModel())

        compose.onNodeWithTag(UiTags.REVIEW_FICHE_OTHER).performClick()
        compose.onNodeWithText("Loisirs › Bars › Obsydian").assertIsDisplayed()
        compose.onNodeWithText("Loisirs › Bars › Obsydian").performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { transactions.sortToCategory("tx-1", "cat-obsydian", false) }
    }

    @Test
    fun `Valider with the merchant switch on asks for the rule as well`() {
        showFiche(viewModel())

        compose.onNodeWithTag(UiTags.REVIEW_FICHE_ALWAYS).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_VALIDATE).performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { transactions.sortToCategory("tx-1", "cat-courses", true) }
    }

    @Test
    fun `Corriger opens the correction screen on the line's category`() {
        coEvery { transactions.getTransaction("tx-4") } returns Result.success(com.maggie.app.data.model.Transaction(id = "tx-4", label = "NETFLIX.COM"))
        showFiche(viewModel(), row = 2)

        compose.onNodeWithTag(UiTags.REVIEW_FICHE_CORRECT).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.REVIEW_CORRECTION_SAVE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE).assertDoesNotExist()
    }

    @Test
    fun `the satisfaction is offered on a spend that can be judged and sends the verdict`() {
        showFiche(viewModel(), row = 2)

        compose.onNodeWithText("À conserver").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_AVOIDABLE).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_KEEP).performScrollTo().performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { transactions.setRetrospect("tx-4", RETROSPECT_AVOIDABLE) }
        coVerify(exactly = 1) { transactions.setRetrospect("tx-4", RETROSPECT_KEEP) }
    }

    @Test
    fun `a line that cannot be judged offers no satisfaction at all`() {
        showFiche(viewModel())

        compose.onNodeWithTag(UiTags.REVIEW_FICHE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_KEEP).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_AVOIDABLE).assertDoesNotExist()
        compose.onNodeWithText("À conserver").assertDoesNotExist()
        compose.onNodeWithText("Superflue").assertDoesNotExist()
    }

    @Test
    fun `the fiche carries the sheet's gestures, and the sheet opens from it`() {
        val opened = mutableListOf<String>()
        val model = viewModel()
        compose.setContent { ReviewListScreen(viewModel = model, onBack = {}, onOpenTransaction = { opened += it.id }) }
        compose.onAllNodesWithTag(UiTags.REVIEW_LIST_ROW)[0].performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.REVIEW_FICHE_QUALIFICATION).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_TRANSFER).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_OPEN_SHEET).performScrollTo().performClick()

        assertEquals(listOf("tx-1"), opened)
    }

    @Test
    fun `the qualification unfolds from the fiche`() {
        showFiche(viewModel())

        compose.onNodeWithText("Obligatoire").assertDoesNotExist()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_QUALIFICATION).performScrollTo().performClick()

        compose.onNodeWithText("Obligatoire").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a line with no suggestion says so, and Valider is not offered`() {
        showFiche(viewModel(), row = 1)

        compose.onNodeWithText("Aucune suggestion pour cette ligne.").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_VALIDATE).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.REVIEW_FICHE_OTHER).assertIsDisplayed()
    }
}
