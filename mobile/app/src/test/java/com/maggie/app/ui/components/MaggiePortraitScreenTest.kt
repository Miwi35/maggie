package com.maggie.app.ui.components

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Maggie's folded portrait and its unread pill, to the letter of `Portrait-Pastille-Tablette` (MAG-965). */
@RunWith(AndroidJUnit4::class)
class MaggiePortraitScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `no unread, no badge and the plain label`() {
        compose.setContent { MaggiePortrait(onClick = {}) }

        compose.onNodeWithTag(UiTags.CHAT_UNREAD_BADGE, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.CHAT_OPEN).assertContentDescriptionEquals("Ouvrir la conversation avec Maggie")
    }

    @Test
    fun `two unread show 2 and say it`() {
        compose.setContent { MaggiePortrait(onClick = {}, unreadCount = 2) }

        compose.onNodeWithTag(UiTags.CHAT_UNREAD_BADGE, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CHAT_OPEN)
            .assertContentDescriptionEquals("Ouvrir la conversation avec Maggie, 2 messages non lus")
    }

    @Test
    fun `one unread says message non lu`() {
        compose.setContent { MaggiePortrait(onClick = {}, unreadCount = 1) }

        compose.onNodeWithTag(UiTags.CHAT_OPEN)
            .assertContentDescriptionEquals("Ouvrir la conversation avec Maggie, 1 message non lu")
    }

    @Test
    fun `more than 99 read 99+`() {
        compose.setContent { MaggiePortrait(onClick = {}, unreadCount = 120) }

        compose.onNodeWithText("99+", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a tap still opens the conversation`() {
        var opened = 0
        compose.setContent { MaggiePortrait(onClick = { opened++ }, unreadCount = 2) }

        compose.onNodeWithTag(UiTags.CHAT_OPEN).performClick()

        assertEquals(1, opened)
    }
}
