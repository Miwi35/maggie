package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.api.CategoryCreateRequest
import com.maggie.app.data.model.Category
import com.maggie.app.screentest.FakeCategories
import com.maggie.app.screentest.ScreenRule
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A category on a screen of its own (MAG-353), and declaring a rente from the phone (MAG-257).
 *
 * The flag is only ever true on an income category — the API answers 422
 * otherwise — so what the form owes is that the box exists on « Recette » and
 * nowhere else, that leaving « Recette » unticks it, and that what was ticked is
 * what leaves in the request.
 *
 * The deletion's question is a dialog, a window of its own, and Robolectric never
 * reaches idle once one is open (the Compose test waits out Espresso's 60 s). So the
 * view model's side of it is in `CategoryViewModelTest`, the sentence it asks is a
 * function tested below, and the dialog itself is walked on a device by
 * `e2e/mobile/flows/12-finance-rente.yaml` and `13-finance-category-edit.yaml`.
 */
@RunWith(AndroidJUnit4::class)
class CategoryListScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun form(initial: Category? = null): CategoryFormState {
        val state = CategoryFormState(initial)
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) { CategoryFormFields(state) }
        }
        compose.waitForIdle()
        return state
    }

    private fun list(categories: FakeCategories) {
        compose.setContent {
            CategoryListScreen(viewModel = categories.viewModel, onBack = {})
        }
        compose.waitForIdle()
    }

    @Test
    fun `the form offers the Recette obligation`() {
        form()

        compose.onNodeWithText("Recette").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the form asks the six fields with a line under each`() {
        form()

        compose.onNodeWithText("Nom").assertIsDisplayed()
        compose.onNodeWithText("Alimentation, Loisirs, Transport…").assertIsDisplayed()
        compose.onNodeWithText("Rattacher à une catégorie").assertIsDisplayed()
        compose.onNodeWithText("Nature de la dépense").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Couleur").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Code hexadécimal, par exemple #4CAF50.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Icône").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the Rente box only shows on a Recette`() {
        form()

        compose.onNodeWithText("Rente").assertDoesNotExist()
        compose.onNodeWithText("Recette").performScrollTo().performClick()
        compose.onNodeWithText("Rente").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Épargne").performScrollTo().performClick()
        compose.onNodeWithText("Rente").assertDoesNotExist()
    }

    @Test
    fun `leaving Recette unticks the Rente box`() {
        val state = form()

        compose.onNodeWithText("Nom").performTextInput("Loyers perçus")
        compose.onNodeWithText("Recette").performScrollTo().performClick()
        compose.onNodeWithText("Rente").performScrollTo().performClick()
        compose.onNodeWithText("Rente").assertIsOn()

        compose.onNodeWithText("Épargne").performScrollTo().performClick()
        compose.onNodeWithText("Recette").performScrollTo().performClick()
        compose.onNodeWithText("Rente").performScrollTo().assertIsOff()

        assertFalse(state.toRequest().passiveIncome)
    }

    @Test
    fun `a rente ticked on a Recette leaves in the request`() {
        val state = form()

        compose.onNodeWithText("Nom").performTextInput("Loyers perçus")
        compose.onNodeWithText("Recette").performScrollTo().performClick()
        compose.onNodeWithText("Rente").performScrollTo().performClick()

        assertEquals(
            CategoryCreateRequest(name = "Loyers perçus", obligation = "income", passiveIncome = true),
            state.toRequest(),
        )
    }

    @Test
    fun `a plain Recette leaves without the flag`() {
        val state = form()

        compose.onNodeWithText("Nom").performTextInput("Prime")
        compose.onNodeWithText("Recette").performScrollTo().performClick()

        val request = state.toRequest()
        assertEquals("income", request.obligation)
        assertFalse(request.passiveIncome)
    }

    @Test
    fun `a rente created through the API is listed as Recette · rente`() {
        val categories = FakeCategories()
        list(categories)

        compose.runOnIdle {
            categories.viewModel.createCategory(
                CategoryCreateRequest(name = "Loyers perçus", obligation = "income", passiveIncome = true),
            )
        }
        compose.waitForIdle()

        assertTrue(categories.created.single().passiveIncome)
        compose.onNodeWithText("Loyers perçus").assertIsDisplayed()
        compose.onNodeWithText("Recette · rente").assertIsDisplayed()
    }

    @Test
    fun `the rows have no delete icon any more`() {
        list(FakeCategories())

        compose.onNodeWithText("Courses").assertIsDisplayed()
        compose.onNodeWithContentDescription("Supprimer").assertDoesNotExist()
    }

    @Test
    fun `tapping a row opens its screen with the deletion at the bottom`() {
        list(FakeCategories())

        compose.onNodeWithText("Courses").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Modifier la catégorie").assertIsDisplayed()
        compose.onNodeWithText("Enregistrer").assertIsDisplayed()
        compose.onNodeWithText("Supprimer la catégorie").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a new category has no deletion to offer`() {
        list(FakeCategories())

        compose.onNodeWithContentDescription("Nouvelle catégorie").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Nouvelle catégorie").assertIsDisplayed()
        compose.onNodeWithText("Créer").assertIsDisplayed()
        compose.onNodeWithText("Supprimer la catégorie").assertDoesNotExist()
    }

    @Test
    fun `renaming a category sends the name alone and the list shows it`() {
        val categories = FakeCategories()
        list(categories)

        compose.onNodeWithText("Courses").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Nom").performTextReplacement("Courses alimentaires")
        compose.onNodeWithText("Enregistrer").performClick()
        compose.waitForIdle()

        assertEquals(
            listOf("cat-courses" to JsonObject(mapOf("name" to JsonPrimitive("Courses alimentaires")))),
            categories.patched,
        )
        compose.onNodeWithText("Courses alimentaires").assertIsDisplayed()
    }

    @Test
    fun `saving without touching anything sends nothing`() {
        val categories = FakeCategories()
        list(categories)

        compose.onNodeWithText("Courses").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Enregistrer").performClick()
        compose.waitForIdle()

        assertTrue(categories.patched.isEmpty())
        compose.onNodeWithText("Catégories").assertIsDisplayed()
    }

    @Test
    fun `an empty name is refused where it is typed`() {
        val categories = FakeCategories()
        list(categories)

        compose.onNodeWithText("Courses").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Nom").performTextReplacement("")
        compose.onNodeWithText("Enregistrer").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Indiquez un nom pour la catégorie.").assertIsDisplayed()
        assertTrue(categories.patched.isEmpty())
    }

    @Test
    fun `only what moved leaves in the patch`() {
        val before = Category(
            id = "cat-1",
            name = "Courses",
            obligation = "mandatory",
            parent = "/api/categories/cat-0",
            color = "#4CAF50",
            icon = "shopping-cart",
        )
        val state = CategoryFormState(before)

        assertEquals(JsonObject(emptyMap()), state.changes())

        state.name = " Courses alimentaires "
        state.color = ""
        state.parent = null
        state.select("income")
        state.passiveIncome = true

        assertEquals(
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive("Courses alimentaires"),
                    "parent" to JsonNull,
                    "obligation" to JsonPrimitive("income"),
                    "passiveIncome" to JsonPrimitive(true),
                    "color" to JsonNull,
                ),
            ),
            state.changes(),
        )
    }

    @Test
    fun `the deletion says what it takes`() {
        assertEquals(
            "12 transactions perdront leur catégorie.\n" +
                "1 règle de catégorisation sera supprimée.\n" +
                "2 sous-catégories seront supprimées.\n" +
                "Cette action est définitive.",
            categoryDeletionBody(CategoryDeletionImpact(transactions = 12, rules = 1, subCategories = 2)),
        )
        assertEquals(
            "1 transaction perdra sa catégorie.\nCette action est définitive.",
            categoryDeletionBody(CategoryDeletionImpact(transactions = 1, rules = 0, subCategories = 0)),
        )
        assertEquals(
            "Aucune transaction n'est rattachée.\nCette action est définitive.",
            categoryDeletionBody(CategoryDeletionImpact(transactions = 0, rules = 0, subCategories = 0)),
        )
        assertEquals(
            "Les transactions rattachées perdront leur catégorie.\n" +
                "Les règles de catégorisation rattachées seront supprimées.\n" +
                "Cette action est définitive.",
            categoryDeletionBody(CategoryDeletionImpact(transactions = null, rules = null, subCategories = 0)),
        )
    }
}
