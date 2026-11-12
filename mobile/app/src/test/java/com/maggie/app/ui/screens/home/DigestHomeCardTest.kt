package com.maggie.app.ui.screens.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.AudioAttachment
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.DigestBlock
import com.maggie.app.data.model.HomeDay
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.tap
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The morning digest tile at the head of the Accueil (MAG-808). */
@RunWith(AndroidJUnit4::class)
class DigestHomeCardTest {

    @get:Rule
    val compose = ScreenRule()

    private val message = ChatMessage(
        id = "m1",
        role = "assistant",
        content = "Votre digest du matin est disponible.",
        audio = AudioAttachment(durationMs = 72_000),
        blocks = listOf(DigestBlock(day = "2026-11-12", text = "Bonjour.", expiresAt = "2026-11-12T14:00:00+01:00")),
    )

    @Test
    fun `the tile names the digest, its day and length`() {
        compose.setContent { DigestHomeCardContent(HomeDay.TODAY, message) {} }

        compose.onNodeWithTag(UiTags.HOME_DIGEST).assertIsDisplayed()
        compose.onNodeWithText("Digest du matin").assertIsDisplayed()
        compose.onNodeWithText("Jeudi 12 novembre · 1:12").assertIsDisplayed()
    }

    @Test
    fun `the pill opens the digest`() {
        val opened = mutableListOf<String>()
        compose.setContent { DigestHomeCardContent(HomeDay.TODAY, message) { opened += it } }

        compose.onNodeWithText("Écouter").tap()

        assertEquals(listOf("m1"), opened)
    }

    @Test
    fun `the whole tile opens the digest`() {
        val opened = mutableListOf<String>()
        compose.setContent { DigestHomeCardContent(HomeDay.TODAY, message) { opened += it } }

        compose.onNodeWithTag(UiTags.HOME_DIGEST).performClick()

        assertEquals(listOf("m1"), opened)
    }

    @Test
    fun `no digest, nothing is drawn`() {
        compose.setContent { DigestHomeCardContent(HomeDay.TODAY, null) {} }

        compose.onNodeWithTag(UiTags.HOME_DIGEST).assertDoesNotExist()
    }

    @Test
    fun `another day than today draws nothing`() {
        compose.setContent { DigestHomeCardContent(HomeDay.TOMORROW, message) {} }

        compose.onNodeWithTag(UiTags.HOME_DIGEST).assertDoesNotExist()
    }
}
