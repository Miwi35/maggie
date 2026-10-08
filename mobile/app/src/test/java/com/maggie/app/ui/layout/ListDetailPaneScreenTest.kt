package com.maggie.app.ui.layout

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The list and its detail, drawn side by side on the JVM (MAG-263).
 *
 * `ListDetailPane` takes `showsDetailPane` as a plain value, so the tests drive it with
 * the one `AppShellScreenTest` leaves to `appLayoutFor`: what the window says is in
 * `WindowLayoutTest`, where the panes go is here.
 */
@RunWith(AndroidJUnit4::class)
class ListDetailPaneScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun xOf(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.x

    private fun widthDpOf(tag: String): Int =
        (compose.onNodeWithTag(tag).fetchSemanticsNode().size.width / compose.density.density).toInt()

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `one pane draws the list alone, the detail has no place`() {
        compose.setContent {
            ListDetailPane(
                showsDetailPane = false,
                list = { Text("Recettes") },
                detail = { Text("Chili sin carne") },
                placeholder = "Touchez une recette",
            )
        }

        compose.onNodeWithTag(UiTags.LIST_PANE).assertIsDisplayed()
        compose.onNodeWithText("Recettes").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.DETAIL_PANE).assertDoesNotExist()
        compose.onNodeWithText("Chili sin carne").assertDoesNotExist()
        compose.onNodeWithText("Touchez une recette").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `two panes draw the detail to the right of the list`() {
        compose.setContent {
            ListDetailPane(
                showsDetailPane = true,
                list = { Text("Recettes") },
                detail = { Text("Chili sin carne") },
                placeholder = "Touchez une recette",
            )
        }

        compose.onNodeWithText("Recettes").assertIsDisplayed()
        compose.onNodeWithText("Chili sin carne").assertIsDisplayed()
        assertTrue(
            "the detail starts where the list ends",
            xOf(UiTags.DETAIL_PANE) >= xOf(UiTags.LIST_PANE) + PANE_MIN_WIDTH.value * compose.density.density,
        )
        assertEquals("the list keeps the phone's width", PANE_MIN_WIDTH.value.toInt(), widthDpOf(UiTags.LIST_PANE))
        compose.onNodeWithText("Touchez une recette").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `the detail takes what the list leaves`() {
        compose.setContent {
            ListDetailPane(
                showsDetailPane = true,
                list = { Text("Recettes") },
                detail = { Text("Chili sin carne") },
                placeholder = "Touchez une recette",
            )
        }

        assertTrue("800 dp, the list's 360 and the 1 dp divider leave 439", widthDpOf(UiTags.DETAIL_PANE) in 438..440)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `nothing selected draws the waiting text in the detail pane`() {
        compose.setContent {
            ListDetailPane(
                showsDetailPane = true,
                list = { Text("Recettes") },
                detail = null,
                placeholder = "Touchez une recette pour la voir ici.",
            )
        }

        compose.onNodeWithText("Recettes").assertIsDisplayed()
        compose.onNodeWithText("Touchez une recette pour la voir ici.").assertIsDisplayed()
        assertTrue(xOf(UiTags.DETAIL_PANE) > xOf(UiTags.LIST_PANE))
    }
}
