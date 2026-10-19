package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.ControlResult
import com.maggie.app.data.model.OPERATION_RULE
import com.maggie.app.data.model.OPERATION_SERIES
import com.maggie.app.data.model.RETROSPECT_AVOIDABLE
import com.maggie.app.data.model.RecognisedGroup
import com.maggie.app.data.model.RecognisedLine
import com.maggie.app.data.model.RecognisedToControl
import com.maggie.app.data.repository.CategoryRepository
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
 * « Reconnues à contrôler » as it is drawn, and the tags `13-finance-to-control` addresses it by (MAG-460).
 *
 * `RecognisedToControlViewModelTest` says what each gesture does to the state; this one says that the groups
 * and their lines reach the screen, that the buttons send what they say, and that an income offers no satisfaction.
 */
@RunWith(AndroidJUnit4::class)
class RecognisedToControlScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun spend(id: String) = RecognisedLine(
        id = id,
        label = "CARREFOUR $id",
        amountCents = -2000,
        bookedAt = "2026-10-12",
        accountName = "Courant",
        judgeable = true,
        categoryId = "cat-courses",
        categoryPath = "Alimentation › Courses",
        qualifiedBy = "rule",
        qualifiedByRuleId = "rule-1",
    )

    private val groceries = RecognisedGroup(
        operationType = OPERATION_RULE,
        operationId = "rule-1",
        label = "Courses",
        count = 2,
        totalCents = -4000,
        unratedCount = 2,
        transactions = listOf(spend("t1"), spend("t2")),
    )

    private val salary = RecognisedGroup(
        operationType = OPERATION_SERIES,
        operationId = "series-1",
        label = "Salaire",
        count = 1,
        totalCents = 250000,
        unratedCount = 0,
        transactions = listOf(spend("t3").copy(label = "VIREMENT EMPLOYEUR", amountCents = 250000, judgeable = false)),
    )

    private val transactions = mockk<TransactionRepository>()

    private fun viewModel(
        groups: List<RecognisedGroup> = listOf(groceries, salary),
        read: Result<RecognisedToControl> = Result.success(RecognisedToControl("2026-10", groups.sumOf { it.count }, groups)),
    ): RecognisedToControlViewModel {
        val categoryRepository = mockk<CategoryRepository>()
        coEvery { categoryRepository.getCategories() } returns Result.success(listOf(Category(id = "cat-courses", name = "Courses")))
        coEvery { transactions.getRecognisedToControl("2026-10") } returns read
        coEvery { transactions.controlRecognised(any(), any()) } returns Result.success(ControlResult(2, 2))
        coEvery { transactions.setRetrospect(any(), any()) } returns Result.success(Unit)

        return RecognisedToControlViewModel(transactions, categoryRepository, today = LocalDate.of(2026, 10, 16))
    }

    @Test
    fun `the groups are headed by their operation and count, with the lines under them`() {
        val model = viewModel()
        compose.setContent { RecognisedToControlScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithText("3 reconnues à contrôler").assertIsDisplayed()
        compose.onNodeWithText("Courses · 2").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.TO_CONTROL_GROUP).assertCountEquals(2)
        compose.onNodeWithText("CARREFOUR t1").assertExists()
        compose.onNodeWithText("CARREFOUR t2").assertExists()
        compose.onAllNodesWithText("→ Alimentation › Courses").onFirst().assertExists()
    }

    @Test
    fun `tout a conserver sends the lines of the group with the keep verdict`() {
        val model = viewModel(groups = listOf(groceries))
        compose.setContent { RecognisedToControlScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithTag(UiTags.TO_CONTROL_KEEP_ALL).performScrollTo().performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { transactions.controlRecognised(listOf("t1", "t2"), true) }
    }

    @Test
    fun `valider sends the lines without any verdict`() {
        val model = viewModel(groups = listOf(groceries))
        compose.setContent { RecognisedToControlScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithTag(UiTags.TO_CONTROL_VALIDATE).performScrollTo().performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { transactions.controlRecognised(listOf("t1", "t2"), false) }
    }

    @Test
    fun `a group with nothing left to judge offers valider only`() {
        val model = viewModel(groups = listOf(salary))
        compose.setContent { RecognisedToControlScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithTag(UiTags.TO_CONTROL_VALIDATE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TO_CONTROL_KEEP_ALL).assertDoesNotExist()
    }

    private fun openLine(index: Int = 0) {
        compose.onAllNodesWithTag(UiTags.TO_CONTROL_ROW)[index].performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the page opens on the list, the line gestures wait for the fiche`() {
        val model = viewModel()
        compose.setContent { RecognisedToControlScreen(viewModel = model, onBack = {}) }

        compose.onAllNodesWithTag(UiTags.TO_CONTROL_ROW).assertCountEquals(3)
        compose.onNodeWithTag(UiTags.TO_CONTROL_FICHE).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.REVIEW_NEXT).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TO_CONTROL_CORRECT).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TO_CONTROL_AVOIDABLE).assertDoesNotExist()
    }

    @Test
    fun `touching a line opens its fiche with precedent, suivant and its position`() {
        val model = viewModel()
        compose.setContent { RecognisedToControlScreen(viewModel = model, onBack = {}) }

        openLine(1)

        compose.onNodeWithTag(UiTags.TO_CONTROL_FICHE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_POSITION).assertTextEquals("2 / 3")
        compose.onNodeWithTag(UiTags.REVIEW_PREVIOUS).assertIsEnabled()
        compose.onNodeWithTag(UiTags.REVIEW_NEXT).assertIsEnabled()
        compose.onNodeWithText("CARREFOUR t2").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TO_CONTROL_VALIDATE).assertDoesNotExist()
    }

    @Test
    fun `suivant and precedent move the fiche along the list and stop at its ends`() {
        val model = viewModel()
        compose.setContent { RecognisedToControlScreen(viewModel = model, onBack = {}) }
        openLine(0)

        compose.onNodeWithTag(UiTags.REVIEW_PREVIOUS).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.REVIEW_NEXT).performClick()
        compose.onNodeWithTag(UiTags.REVIEW_POSITION).assertTextEquals("2 / 3")
        compose.onNodeWithTag(UiTags.REVIEW_NEXT).performClick()
        compose.onNodeWithTag(UiTags.REVIEW_POSITION).assertTextEquals("3 / 3")
        compose.onNodeWithText("VIREMENT EMPLOYEUR").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_NEXT).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.REVIEW_PREVIOUS).performClick()
        compose.onNodeWithTag(UiTags.REVIEW_POSITION).assertTextEquals("2 / 3")
    }

    @Test
    fun `back from the fiche returns to the list, not out of the page`() {
        var left = false
        val model = viewModel()
        compose.setContent { RecognisedToControlScreen(viewModel = model, onBack = { left = true }) }
        openLine(1)

        compose.onNodeWithTag(UiTags.REVIEW_BACK_TO_LIST).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.TO_CONTROL_FICHE).assertDoesNotExist()
        compose.onAllNodesWithTag(UiTags.TO_CONTROL_GROUP).assertCountEquals(2)
        assertEquals(false, left)
        assertEquals("t2", model.uiState.value.selectedId)
    }

    @Test
    fun `on a tablet the list stays and the fiche sits beside it, a placeholder until a line is open`() {
        val model = viewModel()
        compose.setContent { RecognisedToControlScreen(viewModel = model, onBack = {}, showsDetailPane = true) }

        // The test window is narrower than a tablet's: the pane is drawn, not necessarily in view.
        compose.onNodeWithText("Touchez une ligne pour la contrôler ici.").assertExists()
        compose.onNodeWithTag(UiTags.TO_CONTROL_FICHE).assertDoesNotExist()

        openLine(0)

        compose.onNodeWithTag(UiTags.TO_CONTROL_FICHE).assertExists()
        compose.onAllNodesWithTag(UiTags.TO_CONTROL_GROUP).assertCountEquals(2)
        compose.onNodeWithText("Touchez une ligne pour la contrôler ici.").assertDoesNotExist()
    }

    @Test
    fun `an income offers no satisfaction at all`() {
        val model = viewModel(groups = listOf(salary))
        compose.setContent { RecognisedToControlScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithTag(UiTags.TO_CONTROL_ROW).assertIsDisplayed()
        openLine()
        compose.onNodeWithTag(UiTags.TO_CONTROL_FICHE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TO_CONTROL_KEEP).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TO_CONTROL_AVOIDABLE).assertDoesNotExist()
        compose.onNodeWithText("Superflue").assertDoesNotExist()
    }

    @Test
    fun `a spend is judged from its line`() {
        val model = viewModel(groups = listOf(groceries))
        compose.setContent { RecognisedToControlScreen(viewModel = model, onBack = {}) }

        openLine()
        compose.onNodeWithTag(UiTags.TO_CONTROL_AVOIDABLE).performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { transactions.setRetrospect("t1", RETROSPECT_AVOIDABLE) }
    }

    @Test
    fun `the operation and the sheet open from a line, and the operation only while it exists`() {
        val operations = mutableListOf<String>()
        val sheets = mutableListOf<String>()
        val deleted = groceries.copy(operationId = null, transactions = listOf(spend("t5"), spend("t6")))
        val model = viewModel(groups = listOf(groceries, deleted.copy(label = "Ancienne règle")))
        compose.setContent {
            RecognisedToControlScreen(
                viewModel = model,
                onBack = {},
                onOpenOperation = { operations += it.label },
                onOpenTransaction = { sheets += it.id },
            )
        }

        // Two groups of two lines: only the first has an operation left to open.
        openLine(0)
        compose.onNodeWithTag(UiTags.TO_CONTROL_OPEN_OPERATION).performClick()
        compose.onNodeWithTag(UiTags.TO_CONTROL_OPEN_SHEET).performClick()
        compose.onNodeWithTag(UiTags.REVIEW_BACK_TO_LIST).performClick()
        openLine(2)
        compose.onNodeWithTag(UiTags.TO_CONTROL_OPEN_OPERATION).assertDoesNotExist()

        assertEquals(listOf("Courses"), operations)
        assertEquals(listOf("t1"), sheets)
    }

    @Test
    fun `corriger opens the qualification of the line`() {
        coEvery { transactions.getTransaction("t1") } returns Result.success(com.maggie.app.data.model.Transaction(id = "t1", label = "x"))
        val model = viewModel(groups = listOf(groceries))
        compose.setContent { RecognisedToControlScreen(viewModel = model, onBack = {}) }

        openLine()
        compose.onNodeWithTag(UiTags.TO_CONTROL_CORRECT).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.TO_CONTROL_SAVE).assertIsDisplayed()
    }

    @Test
    fun `an empty month says there is nothing to check`() {
        val model = viewModel(groups = emptyList())
        compose.setContent { RecognisedToControlScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithTag(UiTags.TO_CONTROL_EMPTY).assertIsDisplayed()
        compose.onNodeWithText("Rien à contrôler").assertIsDisplayed()
    }

    @Test
    fun `a list that cannot be read offers to retry instead of saying nothing is left`() {
        val model = viewModel(read = Result.failure(RuntimeException("offline")))
        compose.setContent { RecognisedToControlScreen(viewModel = model, onBack = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("Chargement impossible").assertIsDisplayed()
        compose.onNodeWithText("Réessayer").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TO_CONTROL_EMPTY).assertDoesNotExist()
    }
}
