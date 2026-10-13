package com.maggie.app.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** The user bubble of a question sent with a screenshot (MAG-214). */
@RunWith(AndroidJUnit4::class)
class MessageBubbleScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val question = ChatMessage(id = "u-1", role = "user", content = "c'est quoi ce produit ?", hasImage = true)

    private fun jpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    @Test
    fun `the screenshot sent from this device is shown above the question`() {
        compose.setContent { MessageBubble(question, thumbnail = jpeg()) }

        compose.onNodeWithContentDescription("Capture d'écran").assertIsDisplayed()
        compose.onNodeWithText("c'est quoi ce produit ?").assertIsDisplayed()
        compose.onNodeWithText("Capture d'écran (non conservée)").assertDoesNotExist()
    }

    @Test
    fun `a screenshot no longer in memory is named, not shown`() {
        compose.setContent { MessageBubble(question) }

        compose.onNodeWithText("Capture d'écran (non conservée)").assertIsDisplayed()
        compose.onNodeWithContentDescription("Capture d'écran").assertDoesNotExist()
        compose.onNodeWithText("c'est quoi ce produit ?").assertIsDisplayed()
    }

    @Test
    fun `a question sent without a screenshot says nothing about one`() {
        compose.setContent { MessageBubble(question.copy(hasImage = false)) }

        compose.onNodeWithText("Capture d'écran (non conservée)").assertDoesNotExist()
        compose.onNodeWithContentDescription("Capture d'écran").assertDoesNotExist()
    }

    @Test
    fun `the user's answer to a request is a plain line, not a bubble (MAG-336)`() {
        val decision = ChatMessage(
            id = "d-1",
            role = "decision",
            content = "Vous avez répondu : Autoriser — supprimer la série « Psychomot Ael »",
        )

        compose.setContent { MessageBubble(decision) }

        compose.onNodeWithText("Vous avez répondu : Autoriser — supprimer la série « Psychomot Ael »").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CHAT_DECISION).assertIsDisplayed()
    }

    @Test
    fun `a question or an answer is no decision line`() {
        compose.setContent { MessageBubble(question) }

        compose.onNodeWithTag(UiTags.CHAT_DECISION).assertDoesNotExist()
    }
}
