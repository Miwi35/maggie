package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.Need
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.RecipeNeedMeal
import com.maggie.app.data.model.RecipeNeedResponse
import com.maggie.app.data.model.Store
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Adding or editing a line in quantity or in packagings (MAG-573), on the sheets themselves: one test
 * per transition of the entry — prefill, switch of mode, stepper — and what each one sends.
 */
@RunWith(AndroidJUnit4::class)
class QuantityEntryScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val pasta = Product(
        id = "p1",
        name = "Pâtes sèches",
        category = ProductCategory.GRAIN,
        defaultUnit = CookbookUnit.G,
        packagingUnit = CookbookUnit.PACK,
        packagingSize = 500f,
        packagingSizeUnit = CookbookUnit.G,
        restockQuantity = 2,
    )
    private val courgettes = Product(id = "p2", name = "Courgettes", category = ProductCategory.PRODUCE, defaultUnit = CookbookUnit.PIECE)

    private var added: QuantityPayload? = null
    private var saved: QuantityPayload? = null
    private var saveCalls = 0
    private var chosen: Product? = null

    private fun openAddSheetOn(
        product: Product,
        recipeNeed: RecipeNeedResponse? = null,
        products: List<Product> = listOf(pasta, courgettes),
        error: String? = null,
    ) {
        compose.setContent {
            AddItemSheet(
                products = products,
                stores = emptyList(),
                onAddItem = { _, quantity, _, _, _ -> added = quantity },
                onDismiss = {},
                recipeNeed = recipeNeed,
                onProductChosen = { chosen = it },
                error = error,
            )
        }
        openProductPicker()
        // The picker lists nothing under two characters; the prefix keeps the product row the only exact match.
        compose.onNodeWithTag(UiTags.PRODUCT_SEARCH).performTextInput(product.name.take(3))
        compose.onNodeWithText(product.name).tap()
    }

    // A touch does not reach a modal sheet's content under Robolectric; the semantic click does.
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun openProductPicker() {
        compose.onNodeWithTag(UiTags.GROCERY_ADD_PRODUCT).tap()
    }

    private fun needOf(quantity: Double, vararg meals: RecipeNeedMeal) = RecipeNeedResponse(
        productId = "p1",
        recipeNeed = Need(quantity, "g"),
        needs = listOf(Need(quantity, "g")),
        meals = meals.toList(),
    )

    private val riceMeals = arrayOf(
        RecipeNeedMeal(mealId = "m1", name = "Riz au curry", date = "2026-10-24", quantity = 500.0, unit = "g"),
        RecipeNeedMeal(mealId = "m2", name = "Riz cantonais", date = "2026-10-26", quantity = 300.0, unit = "g"),
    )

    private fun openEditSheetOn(item: GroceryItem) {
        compose.setContent {
            ItemDetailContent(
                item = item,
                products = listOf(pasta, courgettes),
                stores = emptyList(),
                onSave = { _, quantity, _, _, _ ->
                    saved = quantity
                    saveCalls++
                },
            )
        }
    }

    @Test
    fun `a packaged product opens in packagings, prefilled with its automatic restock`() {
        openAddSheetOn(pasta)

        compose.onNodeWithTag(UiTags.GROCERY_PACKAGINGS_FIELD).assertTextContains("2")
        compose.onNodeWithTag(UiTags.GROCERY_PACKAGING_CAPTION).assertTextEquals("paquets de 500 g")
        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_SUMMARY).assertTextEquals("1 kg · 2 paquets")

        compose.onNodeWithText("Ajouter").tap()

        assertEquals(QuantityPayload(packagings = 2), added)
    }

    @Test
    fun `the plus button changes the count`() {
        openAddSheetOn(pasta)

        compose.onNodeWithContentDescription("Augmenter le nombre de conditionnements").tap()

        compose.onNodeWithTag(UiTags.GROCERY_PACKAGINGS_FIELD).assertTextContains("3")
        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_SUMMARY).assertTextEquals("1,5 kg · 3 paquets")
    }

    @Test
    fun `switching to quantity turns 300 g into one packaging to buy and sends the quantity alone`() {
        openAddSheetOn(pasta)

        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_MODE_QUANTITY).tap()
        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_FIELD).performTextInput("300")

        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_SUMMARY).assertTextEquals("300 g · 1 paquet")

        compose.onNodeWithText("Ajouter").tap()

        assertEquals(QuantityPayload(quantity = 300f, unit = "g"), added)
    }

    @Test
    fun `a product without a packaging has the quantity mode only`() {
        openAddSheetOn(courgettes)

        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_MODE_PACKAGINGS).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_FIELD).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_SUMMARY).assertDoesNotExist()

        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_FIELD).performTextInput("4")
        compose.onNodeWithText("Ajouter").tap()

        assertEquals(QuantityPayload(quantity = 4f, unit = "piece"), added)
    }

    @Test
    fun `the header says Ajouter à la liste, a hint before a product and the product with its store line after`() {
        val rice = pasta.copy(preferredStore = "/api/stores/s1")
        compose.setContent {
            AddItemSheet(
                products = listOf(rice),
                stores = listOf(Store(id = "s1", name = "Biocoop")),
                onAddItem = { _, _, _, _, _ -> },
                onDismiss = {},
            )
        }
        compose.onNodeWithText("Ajouter à la liste").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.GROCERY_ADD_PRODUCT).assertTextEquals("Choisir un produit")
        compose.onNodeWithContentDescription("Fermer").assertIsDisplayed()
        compose.onNodeWithText("Ajouter").assertIsNotEnabled()

        openProductPicker()
        compose.onNodeWithTag(UiTags.PRODUCT_SEARCH).performTextInput(rice.name.take(3))
        compose.onNodeWithText(rice.name).tap()

        compose.onNodeWithTag(UiTags.GROCERY_ADD_PRODUCT).assertTextEquals(rice.name)
        compose.onNodeWithText("Biocoop · se vend en paquet de 500 g").assertIsDisplayed()
        compose.onNodeWithText("Ajouter").assertIsEnabled()
    }

    @Test
    fun `category and store fields only for a free label or a product without a store`() {
        openAddSheetOn(pasta)
        compose.onNodeWithText("Catégorie").assertIsDisplayed()
        compose.onNodeWithText("Magasin").assertIsDisplayed()
    }

    @Test
    fun `a product with its store leaves no category or store field`() {
        val rice = pasta.copy(preferredStore = "/api/stores/s1")
        compose.setContent {
            AddItemSheet(
                products = listOf(rice),
                stores = listOf(Store(id = "s1", name = "Biocoop")),
                onAddItem = { _, _, _, _, _ -> },
                onDismiss = {},
            )
        }
        openProductPicker()
        compose.onNodeWithTag(UiTags.PRODUCT_SEARCH).performTextInput(rice.name.take(3))
        compose.onNodeWithText(rice.name).tap()

        compose.onNodeWithText("Catégorie").assertDoesNotExist()
        compose.onNodeWithText("Magasin").assertDoesNotExist()
    }

    @Test
    fun `packagings read as a big number between minus and plus, with the caption under`() {
        openAddSheetOn(pasta)

        compose.onNodeWithContentDescription("Diminuer le nombre de conditionnements").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.GROCERY_PACKAGINGS_FIELD).assertTextEquals("2")
        compose.onNodeWithContentDescription("Augmenter le nombre de conditionnements").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.GROCERY_PACKAGING_CAPTION).assertTextEquals("paquets de 500 g")
    }

    @Test
    fun `packaging steppers sit at both edges`() {
        openAddSheetOn(pasta)

        val entry = compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_ENTRY).fetchSemanticsNode().boundsInRoot
        val third = entry.width / 3
        val minus = compose.onNodeWithContentDescription("Diminuer le nombre de conditionnements").fetchSemanticsNode().boundsInRoot
        val plus = compose.onNodeWithContentDescription("Augmenter le nombre de conditionnements").fetchSemanticsNode().boundsInRoot

        assertTrue(minus.right < entry.left + third)
        assertTrue(plus.left > entry.right - third)
    }

    @Test
    fun `choosing a product asks for the week's recipe need of that product`() {
        openAddSheetOn(pasta)

        assertEquals(pasta, chosen)
    }

    @Test
    fun `the box lists the meals that need the product, and the summary counts them in`() {
        openAddSheetOn(pasta, recipeNeed = needOf(800.0, *riceMeals))

        compose.onNodeWithTag(UiTags.GROCERY_RECIPE_NEED_BOX).assertIsDisplayed()
        compose.onNodeWithText("Pour les recettes de la semaine").assertIsDisplayed()
        compose.onNodeWithText("800 g").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.GROCERY_RECIPE_NEED_MEAL).assertCountEquals(1)
        compose.onNodeWithTag(UiTags.GROCERY_RECIPE_NEED_MEAL).assertTextEquals("Riz au curry sam. 500 g · Riz cantonais lun. 300 g")
        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_SUMMARY).assertTextEquals("1 kg (dont 800 g pour les recettes) · 2 paquets")
        compose.onNodeWithTag(UiTags.GROCERY_COVERAGE_ALERT).assertDoesNotExist()
    }

    @Test
    fun `no box when no meal needs the product`() {
        openAddSheetOn(pasta, recipeNeed = RecipeNeedResponse(productId = "p1"))

        compose.onNodeWithTag(UiTags.GROCERY_RECIPE_NEED_BOX).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.GROCERY_COVERAGE_ALERT).assertDoesNotExist()
    }

    @Test
    fun `a need read for another product is not shown`() {
        openAddSheetOn(pasta, recipeNeed = needOf(800.0, *riceMeals).copy(productId = "other"))

        compose.onNodeWithTag(UiTags.GROCERY_RECIPE_NEED_BOX).assertDoesNotExist()
    }

    @Test
    fun `without a restock the count is prefilled from the week's need, rounded up`() {
        val bare = pasta.copy(restockQuantity = null)
        openAddSheetOn(bare, recipeNeed = needOf(800.0, *riceMeals), products = listOf(bare))

        compose.onNodeWithTag(UiTags.GROCERY_PACKAGINGS_FIELD).assertTextContains("2")

        compose.onNodeWithText("Ajouter").tap()

        assertEquals(QuantityPayload(packagings = 2), added)
    }

    @Test
    fun `the need arriving after the product was chosen replaces the one-packaging default`() {
        val bare = pasta.copy(restockQuantity = null)
        var recipeNeed by mutableStateOf<RecipeNeedResponse?>(null)
        compose.setContent {
            AddItemSheet(
                products = listOf(bare),
                stores = emptyList(),
                onAddItem = { _, quantity, _, _, _ -> added = quantity },
                onDismiss = {},
                recipeNeed = recipeNeed,
            )
        }
        openProductPicker()
        compose.onNodeWithTag(UiTags.PRODUCT_SEARCH).performTextInput(bare.name.take(3))
        compose.onNodeWithText(bare.name).tap()
        compose.onNodeWithTag(UiTags.GROCERY_PACKAGINGS_FIELD).assertTextContains("1")

        compose.runOnUiThread { recipeNeed = needOf(1200.0, *riceMeals) }
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.GROCERY_PACKAGINGS_FIELD).assertTextContains("3")
    }

    @Test
    fun `switching to quantity offers one to three packagings and the need as suggestions`() {
        openAddSheetOn(pasta, recipeNeed = needOf(800.0, *riceMeals))

        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_MODE_QUANTITY).tap()

        compose.onNodeWithTag(UiTags.groceryQuantitySuggestion(0)).assertTextContains("500 g")
        compose.onNodeWithTag(UiTags.groceryQuantitySuggestion(1)).assertTextContains("800 g")
        compose.onNodeWithTag(UiTags.groceryQuantitySuggestion(2)).assertTextContains("1 kg")
        compose.onNodeWithTag(UiTags.groceryQuantitySuggestion(3)).assertTextContains("1,5 kg")

        compose.onNodeWithTag(UiTags.groceryQuantitySuggestion(2)).tap()
        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_SUMMARY).assertTextEquals("1 kg (dont 800 g pour les recettes) · 2 paquets")
        compose.onNodeWithText("Ajouter").tap()

        assertEquals(QuantityPayload(quantity = 1000f, unit = "g"), added)
    }

    @Test
    fun `a count short of the need alerts, and adjusting sets the count that covers it`() {
        openAddSheetOn(pasta, recipeNeed = needOf(1200.0, *riceMeals))

        compose.onNodeWithTag(UiTags.GROCERY_COVERAGE_ALERT).assertIsDisplayed()
        compose.onNodeWithText("Ne couvre pas les besoins de la semaine (1,2 kg pour les recettes)").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.GROCERY_COVERAGE_ADJUST).assertTextEquals("Ajuster à 3 paquets")

        compose.onNodeWithTag(UiTags.GROCERY_COVERAGE_ADJUST).tap()

        compose.onNodeWithTag(UiTags.GROCERY_PACKAGINGS_FIELD).assertTextContains("3")
        compose.onNodeWithTag(UiTags.GROCERY_COVERAGE_ALERT).assertDoesNotExist()

        compose.onNodeWithText("Ajouter").tap()

        assertEquals(QuantityPayload(packagings = 3), added)
    }

    @Test
    fun `the alert goes when the count is stepped up to what covers the need`() {
        openAddSheetOn(pasta, recipeNeed = needOf(1200.0, *riceMeals))

        compose.onNodeWithContentDescription("Augmenter le nombre de conditionnements").tap()

        compose.onNodeWithTag(UiTags.GROCERY_COVERAGE_ALERT).assertDoesNotExist()
    }

    @Test
    fun `the reason the API gave stays on the sheet`() {
        openAddSheetOn(pasta, error = "Quantité et conditionnements ensemble")

        compose.onNodeWithText("Quantité et conditionnements ensemble").assertIsDisplayed()
    }

    @Test
    fun `editing a packaged line steps its packagings and sends them`() {
        openEditSheetOn(GroceryItem(id = "i1", label = "Pâtes sèches", product = pasta, quantity = 3f, unit = CookbookUnit.PACK))

        compose.onNodeWithTag(UiTags.GROCERY_PACKAGINGS_FIELD).assertTextContains("3")
        compose.onNodeWithContentDescription("Diminuer le nombre de conditionnements").tap()
        compose.onNodeWithText("Enregistrer").tap()

        assertEquals(QuantityPayload(packagings = 2), saved)
    }

    @Test
    fun `saving a packaged line without touching its quantity sends none`() {
        openEditSheetOn(GroceryItem(id = "i1", label = "Pâtes sèches", product = pasta, quantity = 3f, unit = CookbookUnit.PACK))

        compose.onNodeWithText("Enregistrer").tap()

        assertEquals(1, saveCalls)
        assertNull(saved)
    }
}
