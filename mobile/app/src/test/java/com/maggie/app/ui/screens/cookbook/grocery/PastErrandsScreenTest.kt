package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Errand
import com.maggie.app.data.model.PurchasedItem
import com.maggie.app.data.model.endedAtLabel
import com.maggie.app.screentest.FakeGrocery
import com.maggie.app.screentest.FakePastErrands
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** « Courses passées » (MAG-488): the errands « Terminer la course » archived, and one of them in its sheet. */
@RunWith(AndroidJUnit4::class)
class PastErrandsScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val lidl = Errand(
        id = "errand-lidl",
        storeName = "Lidl",
        endedAt = "2026-09-20T10:00:00+02:00",
        itemCount = 1,
        items = listOf(PurchasedItem(label = "Citrons", quantity = 3f, unit = CookbookUnit.PIECE)),
    )
    private val carrefour = Errand(
        id = "errand-carrefour",
        storeName = "Carrefour",
        endedAt = "2026-10-02T18:00:00+02:00",
        itemCount = 2,
        items = listOf(
            PurchasedItem(
                label = "Riz",
                quantity = 2f,
                unit = CookbookUnit.PACK,
                packagingUnit = CookbookUnit.PACK,
                packagingSize = 500f,
                packagingSizeUnit = CookbookUnit.G,
            ),
            PurchasedItem(label = "Sacs"),
        ),
    )
    private val noStore = Errand(id = "errand-none", storeName = null, endedAt = "2026-08-01T09:00:00+02:00", itemCount = 1)

    private fun show(fake: FakePastErrands) {
        compose.setContent { PastErrandsScreen(viewModel = fake.viewModel, onBack = {}) }
    }

    @Test
    fun `the errands are listed the most recent first, with their day and their count`() {
        show(FakePastErrands(listOf(lidl, noStore, carrefour)))

        assertTopToBottom(
            "Carrefour" to compose.onNodeWithText("Carrefour"),
            "Lidl" to compose.onNodeWithText("Lidl"),
            "Sans magasin" to compose.onNodeWithText("Sans magasin"),
        )
        compose.onNodeWithText(carrefour.endedAtLabel()).assertIsDisplayed()
        compose.onNodeWithContentDescription("2 articles").assertIsDisplayed()
    }

    @Test
    fun `no errand yet says so`() {
        show(FakePastErrands())

        compose.onNodeWithText("Aucune course passée").assertIsDisplayed()
    }

    @Test
    fun `a failed load says so and offers to try again`() {
        show(FakePastErrands(loadFailure = RuntimeException("Serveur injoignable")))

        compose.onNodeWithText("Serveur injoignable").assertIsDisplayed()
        compose.onNodeWithText("Réessayer").assertIsDisplayed()
        compose.onNodeWithText("Aucune course passée").assertDoesNotExist()
    }

    @Test
    fun `a tap on an errand opens its lines in a sheet, with what was bought of each`() {
        show(FakePastErrands(listOf(lidl, carrefour)))

        compose.onNodeWithText("Carrefour").performClick()

        val inSheet = hasAnyAncestor(hasTestTag(UiTags.PAST_ERRAND_SHEET))
        compose.onNode(hasText("Riz") and inSheet).assertIsDisplayed()
        compose.onNode(hasText("2 paquets · paquet de 500 g") and inSheet).assertIsDisplayed()
        compose.onNode(hasText("Sacs") and inSheet).assertIsDisplayed()
        compose.onNode(hasText("Citrons") and inSheet).assertDoesNotExist()
    }

    @Test
    fun `the grocery screen's history icon asks for « Courses passées »`() {
        val fake = FakeGrocery()
        var opened = 0
        compose.setContent {
            GroceryScreen(
                viewModel = fake.viewModel,
                recurringViewModel = fake.recurring.viewModel,
                onNavigateToPastErrands = { opened++ },
            )
        }

        compose.onNodeWithTag(UiTags.PAST_ERRANDS_OPEN).assertIsDisplayed()
        compose.onNodeWithContentDescription("Courses passées").performClick()

        assertEquals(1, opened)
    }
}
