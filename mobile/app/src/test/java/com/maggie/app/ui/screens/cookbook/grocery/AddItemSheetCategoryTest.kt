package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.tap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The category of a free label is read in French, never as the enum name (MAG-794); only a free label is asked for it (MAG-1054). */
@RunWith(AndroidJUnit4::class)
class AddItemSheetCategoryTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun categoryIsOfferedAndShownInFrench() {
        compose.setContent {
            AddItemSheet(
                products = emptyList(),
                stores = emptyList(),
                onAddItem = { _, _, _, _, _ -> },
                onDismiss = {},
            )
        }
        compose.onNodeWithText("Catégorie").tap()
        compose.onNodeWithText("Fruits et légumes").assertIsDisplayed()
        compose.onNodeWithText("produce").assertDoesNotExist()
        compose.onNodeWithText("Fruits et légumes").tap()
        compose.onNodeWithText("Fruits et légumes").assertIsDisplayed()
    }
}
