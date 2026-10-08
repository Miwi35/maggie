package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.api.CategoryCreateRequest
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
 * otherwise — so what the form owes is that the box exists on « Recette » and
 * nowhere else, that leaving « Recette » unticks it, and that what was ticked is
 * what leaves in the request.
 *
 * The form is drawn on its own, without the `AlertDialog` that hosts it: a dialog
 * is a window of its own, and Robolectric never reaches idle once one is open
 * (the Compose test waits out Espresso's 60 s). The dialog itself, and the row it
 * adds, are walked on a device by `e2e/mobile/flows/12-finance-rente.yaml`.
 */
@RunWith(AndroidJUnit4::class)
class CategoryListScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun form(): CategoryCreateFormState {
        val state = CategoryCreateFormState()
        compose.setContent { CategoryCreateFields(state) }
        compose.waitForIdle()
        return state
    }

    @Test
    fun `the form offers the Recette obligation`() {
        form()

        compose.onNodeWithText("Recette").assertIsDisplayed()
    }

    @Test
    fun `the Rente box only shows on a Recette`() {
        form()

        compose.onNodeWithText("Rente").assertDoesNotExist()
        compose.onNodeWithText("Recette").performClick()
        compose.onNodeWithText("Rente").assertIsDisplayed()
        compose.onNodeWithText("Épargne").performClick()
        compose.onNodeWithText("Rente").assertDoesNotExist()
    }

    @Test
    fun `leaving Recette unticks the Rente box`() {
        val state = form()

        compose.onNodeWithText("Nom").performTextInput("Loyers perçus")
        compose.onNodeWithText("Recette").performClick()
        compose.onNodeWithText("Rente").performClick()
        compose.onNodeWithText("Rente").assertIsOn()

        compose.onNodeWithText("Épargne").performClick()
        compose.onNodeWithText("Recette").performClick()
        compose.onNodeWithText("Rente").assertIsOff()

        assertFalse(state.toRequest().passiveIncome)
    }

    @Test
    fun `a rente ticked on a Recette leaves in the request`() {
        val state = form()

        compose.onNodeWithText("Nom").performTextInput("Loyers perçus")
        compose.onNodeWithText("Recette").performClick()
        compose.onNodeWithText("Rente").performClick()

        assertEquals(
            CategoryCreateRequest(name = "Loyers perçus", obligation = "income", passiveIncome = true),
            state.toRequest(),
        )
    }

    @Test
    fun `a plain Recette leaves without the flag`() {
        val state = form()

        compose.onNodeWithText("Nom").performTextInput("Prime")
        compose.onNodeWithText("Recette").performClick()

        val request = state.toRequest()
        assertEquals("income", request.obligation)
        assertFalse(request.passiveIncome)
    }

    @Test
    fun `a rente created through the API is listed as Recette · rente`() {
        val categories = FakeCategories()
        compose.setContent {
            CategoryListScreen(viewModel = categories.viewModel, onBack = {})
        }
        compose.waitForIdle()

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
}
