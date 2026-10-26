package com.maggie.app.ui.components

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

/** The band under the content, to the letter of `charte/Tel-Cible` (MAG-624). */
@RunWith(AndroidJUnit4::class)
class ChatBottomBarScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `the bar has Maggie's face in the field and the mic on the right`() {
        compose.setContent { ChatBottomBar(onOpenChat = {}) }

        compose.onNodeWithTag(UiTags.CHAT_AVATAR, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Demander à Maggie...").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CHAT_MIC).assertIsDisplayed()
    }

    @Test
    fun `the face is inside the field, not beside it`() {
        compose.setContent { ChatBottomBar(onOpenChat = {}) }

        val field = compose.onNodeWithTag(UiTags.CHAT_OPEN).fetchSemanticsNode().boundsInRoot
        val face = compose.onNodeWithTag(UiTags.CHAT_AVATAR, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val mic = compose.onNodeWithTag(UiTags.CHAT_MIC).fetchSemanticsNode().boundsInRoot

        assert(face.left >= field.left && face.right <= field.right) { "face $face outside field $field" }
        assert(mic.left >= field.right) { "mic $mic should sit right of the field $field" }
    }

    @Test
    fun `the field opens the conversation and the mic opens voice mode`() {
        var opened = 0
        var mic = 0
        compose.setContent { ChatBottomBar(onOpenChat = { opened++ }, onMicClick = { mic++ }) }

        compose.onNodeWithTag(UiTags.CHAT_OPEN).performClick()
        compose.onNodeWithTag(UiTags.CHAT_MIC).performClick()

        assertEquals(1, opened)
        assertEquals(1, mic)
    }
}
