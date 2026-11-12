package com.maggie.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.AudioAttachment
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.DigestBlock
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DigestCardTest {

    @get:Rule
    val compose = ScreenRule()

    private val block = DigestBlock(day = "2026-11-12", text = "Bonjour monsieur.", expiresAt = "2026-11-12T14:00:00+01:00")
    private val audio = AudioAttachment(durationMs = 72_000, transcript = "Bonjour monsieur.")

    @Test
    fun `the card reads the title, the day and the length of the vocal`() {
        compose.setContent { DigestCard(block, audio, onOpen = {}) }

        compose.onNodeWithText("Digest du matin").assertIsDisplayed()
        compose.onNodeWithText("Jeudi 12 novembre · 1:12").assertIsDisplayed()
    }

    @Test
    fun `without a vocal the card gives the day alone`() {
        compose.setContent { DigestCard(block, null, onOpen = {}) }

        compose.onNodeWithText("Jeudi 12 novembre").assertIsDisplayed()
    }

    @Test
    fun `a tap on the card opens the screen`() {
        var opened = 0
        compose.setContent { DigestCard(block, audio, onOpen = { opened++ }) }

        compose.onNodeWithTag(UiTags.CHAT_DIGEST_CARD).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `the digest message shows its announcement and no voice bubble`() {
        val message = ChatMessage(
            id = "m-1",
            role = "assistant",
            content = "Votre digest du matin est disponible.",
            audio = audio,
            blocks = listOf(block),
        )
        compose.setContent { MessageBubble(message) }

        compose.onNodeWithText("Votre digest du matin est disponible.").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.VOICE_PLAY).assertDoesNotExist()
    }
}
