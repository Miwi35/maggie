package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.FakeCategories
import com.maggie.app.screentest.ScreenRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Declaring a rente from the phone (MAG-257).
 *
 * The flag is only ever true on an income category — the API answers 422
 * otherwise — so what the dialog owes is that the box exists on « Recette » and
 * nowhere else, that leaving « Recette » unticks it, and that what was ticked is
 * what leaves in the request.
 */
@RunWith(AndroidJUnit4::class)
class CategoryListScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun open(categories: FakeCategories) {
        compose.setContent {
            CategoryListScreen(viewModel = categories.viewModel, onBack = {})
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Nouvelle catégorie").performClick()
    }

    @Test
    fun `the dialog offers the Recette obligation`() {
        open(FakeCategories())

        compose.onNodeWithText("Recette").assertIsDisplayed()
    }

    @Test
    fun `the Rente box only shows on a Recette`() {
        open(FakeCategories())

        compose.onNodeWithText("Rente").assertDoesNotExist()
        compose.onNodeWithText("Recette").performClick()
        compose.onNodeWithText("Rente").assertIsDisplayed()
        compose.onNodeWithText("Épargne").performClick()
        compose.onNodeWithText("Rente").assertDoesNotExist()
    }

    @Test
    fun `leaving Recette unticks the Rente box`() {
        val categories = FakeCategories()
        open(categories)

        compose.onNodeWithText("Nom").performTextInput("Loyers perçus")
        compose.onNodeWithText("Recette").performClick()
        compose.onNodeWithText("Rente").performClick()
        compose.onNodeWithText("Rente").assertIsOn()

        compose.onNodeWithText("Épargne").performClick()
        compose.onNodeWithText("Recette").performClick()
        compose.onNodeWithText("Rente").assertIsOff()

        compose.onNodeWithText("Créer").performClick()
        compose.waitForIdle()

        assertFalse(categories.created.single().passiveIncome)
    }

    @Test
    fun `a rente ticked on a Recette is sent and shown as such`() {
        val categories = FakeCategories()
        open(categories)

        compose.onNodeWithText("Nom").performTextInput("Loyers perçus")
        compose.onNodeWithText("Recette").performClick()
        compose.onNodeWithText("Rente").performClick()
        compose.onNodeWithText("Créer").performClick()
        compose.waitForIdle()

        val request = categories.created.single()
        assertEquals("Loyers perçus", request.name)
        assertEquals("income", request.obligation)
        assertTrue(request.passiveIncome)
        compose.onNodeWithText("Loyers perçus").assertIsDisplayed()
        compose.onNodeWithText("Recette · rente").assertIsDisplayed()
    }

    @Test
    fun `a plain Recette is created without the flag`() {
        val categories = FakeCategories()
        open(categories)

        compose.onNodeWithText("Nom").performTextInput("Prime")
        compose.onNodeWithText("Recette").performClick()
        compose.onNodeWithText("Créer").performClick()
        compose.waitForIdle()

        assertFalse(categories.created.single().passiveIncome)
        compose.onNodeWithText("Recette").assertIsDisplayed()
    }
}
