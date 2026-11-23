package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import androidx.compose.ui.graphics.Color
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The recipe form as « Recette-Edit-Tel » draws it (MAG-939): ✕ and the save pill on top, the title in the page, sections in capitals. */
@RunWith(AndroidJUnit4::class)
class RecipeFormScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private var current = RecipeForm()
    private var backs = 0
    private var saves = 0

    private fun show(
        initial: RecipeForm = RecipeForm(name = "Pâtes", servings = "4"),
        title: String = "Modifier la recette",
        saveLabel: String = "Enregistrer",
        isSaving: Boolean = false,
        banner: @Composable () -> Unit = {},
    ) {
        current = initial
        compose.setContent {
            var form by remember { mutableStateOf(initial) }
            RecipeFormScreen(
                title = title,
                saveLabel = saveLabel,
                form = form,
                isSaving = isSaving,
                error = null,
                stepErrors = emptyMap(),
                edit = { change -> form = change(form); current = form },
                onSave = { saves++ },
                onBack = { backs++ },
                onSearchCiqual = { emptyList() },
                onLoadUtensils = { emptyList() },
                banner = banner,
            )
        }
    }

    @Test
    fun `the title is in the page and the save pill sits in the top bar`() {
        show()

        compose.onNodeWithText("Modifier la recette").assertExists()
        compose.onNodeWithTag(UiTags.RECIPE_SAVE).assertIsEnabled()
        val pill = compose.onNodeWithTag(UiTags.RECIPE_SAVE).getBoundsInRoot()
        val title = compose.onNodeWithText("Modifier la recette").getBoundsInRoot()
        assertTrue("the pill is in the bar, above the title", pill.bottom <= title.top)
        compose.onNodeWithTag(UiTags.RECIPE_SAVE).performClick()
        assertEquals(1, saves)
    }

    @Test
    fun `the close button goes back`() {
        show()

        compose.onNodeWithContentDescription("Fermer").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `there is no save button at the bottom`() {
        show()

        compose.onAllNodesWithText("Enregistrer").assertCountEquals(1)
    }

    @Test
    fun `a new recipe says Nouvelle recette and Créer`() {
        show(title = "Nouvelle recette", saveLabel = "Créer")

        compose.onNodeWithText("Nouvelle recette").assertExists()
        compose.onNodeWithText("Créer").assertExists()
    }

    @Test
    fun `the save pill is off while the name is empty`() {
        show(initial = RecipeForm())

        compose.onNodeWithTag(UiTags.RECIPE_SAVE).assertIsNotEnabled()
    }

    @Test
    fun `the save pill is off while saving`() {
        show(isSaving = true)

        compose.onNodeWithTag(UiTags.RECIPE_SAVE).assertIsNotEnabled()
    }

    @Test
    fun `portions and tags share one row`() {
        show()

        val portions = compose.onNodeWithText("Portions").getBoundsInRoot()
        val tags = compose.onNodeWithText("Tags").getBoundsInRoot()
        assertEquals(portions.top, tags.top)
        assertTrue(portions.left < tags.left)
        compose.onAllNodesWithText("Tags (séparés par des virgules)").assertCountEquals(0)
    }

    @Test
    fun `the sections are titled in capitals, with no Ajouter`() {
        show()

        compose.onNodeWithText("INGRÉDIENTS").assertExists()
        compose.onNodeWithText("MATÉRIEL").assertExists()
        compose.onNodeWithText("ÉTAPES").assertExists()
        compose.onAllNodesWithText("Ajouter").assertCountEquals(0)
    }

    @Test
    fun `plus Ingrédient adds a line under the last one`() {
        show()

        compose.onNodeWithText("+ Ingrédient").performScrollTo().performClick()

        assertEquals(1, current.ingredients.size)
    }

    @Test
    fun `plus Étape adds a step under the last one`() {
        show()

        compose.onNodeWithTag(UiTags.RECIPE_STEP_ADD).performScrollTo().performClick()

        assertEquals(1, current.steps.size)
        compose.onNodeWithTag(UiTags.RECIPE_STEP).assertExists()
    }

    @Test
    fun `plus Ustensile opens the search`() {
        show()

        compose.onNodeWithTag(UiTags.RECIPE_UTENSIL_ADD).performScrollTo().performClick()

        compose.onNodeWithTag(UiTags.RECIPE_UTENSIL_SEARCH).assertExists()
    }

    @Test
    fun `notes and the conflict banner stay`() {
        show(banner = { Text("Recette modifiée ailleurs") })

        compose.onNodeWithText("Recette modifiée ailleurs").assertExists()
        compose.onNodeWithText("Notes").assertExists()
    }

    @Test
    fun `in dark mode the save pill is F3F1F8 with 0F0E17 text`() {
        assertEquals(Color(0xFFF3F1F8) to Color(0xFF0F0E17), savePillColors(MaggieTokens.surfaceDark))
    }

    @Test
    fun `in light mode the save pill takes the light surface's text and background`() {
        val light = MaggieTokens.surfaceLight
        assertEquals(light.text to light.background, savePillColors(light))
    }
}
