package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Category
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** MAG-474: the full-screen icon choice, and the field of the category form that opens it. */
@RunWith(AndroidJUnit4::class)
class CategoryIconPickerScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val picks = mutableListOf<String?>()

    private fun picker(current: String? = null, color: String? = null) {
        compose.setContent {
            CategoryIconPickerScreen(current = current, color = color, onPick = { picks += it }, onBack = {})
        }
        compose.waitForIdle()
    }

    @Test
    fun `opens on a search field and the grid, each icon with its label`() {
        picker()

        compose.onNodeWithTag(UiTags.CATEGORY_ICON_SEARCH).assertIsDisplayed()
        compose.onNodeWithText("Aucune icône").assertIsDisplayed()
        compose.onNodeWithText("Courses").assertIsDisplayed()
        compose.onNodeWithText("Maison").assertIsDisplayed()
    }

    @Test
    fun `typing voiture puts the car first and drops the others`() {
        picker()

        compose.onNodeWithTag(UiTags.CATEGORY_ICON_SEARCH).performTextInput("voiture")
        compose.waitForIdle()

        compose.onNodeWithText("Voiture").assertIsDisplayed()
        compose.onAllNodesWithText("Courses").assertCountEquals(0)
        compose.onAllNodesWithText("Maison").assertCountEquals(0)
    }

    @Test
    fun `typing chien finds the pets icon and hands its name back`() {
        picker()

        compose.onNodeWithTag(UiTags.CATEGORY_ICON_SEARCH).performTextInput("chien")
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.CATEGORY_ICON_TILE_PREFIX + "pets").assertIsDisplayed().performClick()

        assertEquals(listOf<String?>("pets"), picks)
    }

    @Test
    fun `the grid only composes the tiles in view, not the whole catalogue`() {
        picker()

        compose.onAllNodesWithTag(UiTags.CATEGORY_ICON_TILE_PREFIX + "zoom-out").assertCountEquals(0)
        compose.onNodeWithText("Courses").assertIsDisplayed()
    }

    @Test
    fun `says so when nothing matches, and no icon stays available`() {
        picker()

        compose.onNodeWithTag(UiTags.CATEGORY_ICON_SEARCH).performTextInput("zzz")
        compose.waitForIdle()

        compose.onNodeWithText("Aucune icône ne correspond.").assertIsDisplayed()
        compose.onNodeWithText("Aucune icône").assertIsDisplayed()
    }

    @Test
    fun `choosing an icon hands its name back`() {
        picker()

        compose.onNodeWithTag(UiTags.CATEGORY_ICON_SEARCH).performTextInput("courses")
        compose.waitForIdle()
        compose.onNodeWithText("Courses").performClick()

        assertEquals(listOf<String?>("shopping-cart"), picks)
    }

    @Test
    fun `no icon hands back null`() {
        picker(current = "bolt")

        compose.onNodeWithText("Aucune icône").performClick()

        assertEquals(listOf<String?>(null), picks)
    }

    @Test
    fun `the current icon is shown selected`() {
        picker(current = "bolt")

        compose.onNode(hasText("Électricité")).assertIsDisplayed()
        compose.onNodeWithText("Électricité").assertIsSelected()
        compose.onNodeWithText("Courses").assertIsNotSelected()
    }

    @Test
    fun `a name outside the catalogue is listed first and selected`() {
        picker(current = "icone-disparue")

        compose.onNodeWithText("icone-disparue").assertIsDisplayed()
        compose.onNodeWithText("icone-disparue").assertIsSelected()
    }

    @Test
    fun `a stored name that looks like a fixed row does not break the grid`() {
        picker(current = "none")

        compose.onNodeWithText("none").assertIsDisplayed()
        compose.onNodeWithText("Courses").assertIsDisplayed()
    }

    @Test
    fun `the category form shows the icon by its label and opens the grid from it`() {
        val state = CategoryFormState(Category(id = "cat-1", name = "Courses", icon = "shopping-cart"))
        var opened = 0
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CategoryFormFields(state, onPickIcon = { opened++ })
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Icône, Courses").performScrollTo().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `the category form says there is no icon yet`() {
        val state = CategoryFormState()
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) { CategoryFormFields(state) }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Icône, aucune").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the form state takes the picked icon, and clears it for none`() {
        val state = CategoryFormState(Category(id = "cat-1", name = "Courses", icon = "bolt"))

        state.icon = "shopping-cart"
        assertEquals("shopping-cart", state.toRequest().icon)

        state.icon = ""
        assertEquals(null, state.toRequest().icon)
    }
}
