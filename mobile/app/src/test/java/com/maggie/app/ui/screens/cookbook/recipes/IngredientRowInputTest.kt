package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.screentest.ScreenRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** MAG-536: the unit of an ingredient line is read whole in the recipe form, whatever the unit. */
@RunWith(AndroidJUnit4::class)
class IngredientRowInputTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `the longest unit is not cut in its field`() {
        val row = IngredientRow(ciqualFoodName = "Poireau", quantity = "2", unit = CookbookUnit.BOTTLE)
        compose.setContent {
            IngredientRowInput(row = row, onUpdate = {}, onRemove = {}, onSearchCiqual = { emptyList() })
        }

        val unitField = compose.onNode(hasText("Unité") and hasText("bottle"))
        unitField.assertIsDisplayed()
        // « bottle », its label and the dropdown arrow need about 120 dp; the field was ~95 dp wide.
        unitField.assertWidthIsAtLeast(120.dp)
    }

    @Test
    fun `a short unit like g shows whole`() {
        val row = IngredientRow(ciqualFoodName = "Farine", quantity = "200", unit = CookbookUnit.G)
        compose.setContent {
            IngredientRowInput(row = row, onUpdate = {}, onRemove = {}, onSearchCiqual = { emptyList() })
        }

        compose.onNode(hasText("Unité") and hasText("g")).assertIsDisplayed()
    }
}
