package com.maggie.app.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.MessageFeedback
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
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

    // --- Thumbs on Maggie's answers (MAG-23), in the long-press menu (MAG-624) ---

    private val answer = ChatMessage(id = "a-1", role = "assistant", content = "Il fait beau.")
    private val ratings = mutableListOf<String?>()
    private val record: (String?) -> Unit = { rating -> ratings += rating }

    private fun openMenu() {
        compose.onNodeWithText("Il fait beau.").performTouchInput { longClick() }
    }

    @Test
    fun `an answer shows no thumbs under it`() {
        compose.setContent { MessageBubble(answer, onRate = record) }

        compose.onNodeWithText("Il fait beau.").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.MESSAGE_THUMB_UP).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.MESSAGE_THUMB_DOWN).assertDoesNotExist()
    }

    @Test
    fun `a long press on an answer offers both thumbs, none selected until rated`() {
        compose.setContent { MessageBubble(answer, onRate = record) }

        openMenu()

        compose.onNodeWithTag(UiTags.MESSAGE_THUMB_UP).assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithTag(UiTags.MESSAGE_THUMB_DOWN).assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithText("Réponse utile").assertIsDisplayed()
        compose.onNodeWithText("Réponse à revoir").assertIsDisplayed()
    }

    @Test
    fun `a question has no thumbs`() {
        compose.setContent { MessageBubble(question, onRate = record) }

        compose.onNodeWithText("c'est quoi ce produit ?").performTouchInput { longClick() }

        compose.onNodeWithTag(UiTags.MESSAGE_THUMB_UP).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.MESSAGE_THUMB_DOWN).assertDoesNotExist()
    }

    @Test
    fun `a thumb up is sent at once`() {
        compose.setContent { MessageBubble(answer, onRate = record) }

        openMenu()
        compose.onNodeWithTag(UiTags.MESSAGE_THUMB_UP).performClick()

        assertEquals(listOf<String?>(MessageFeedback.UP), ratings)
    }

    @Test
    fun `a thumb down is sent at once, before any word on why`() {
        compose.setContent { MessageBubble(answer, onRate = record) }

        openMenu()
        compose.onNodeWithTag(UiTags.MESSAGE_THUMB_DOWN).performClick()

        assertEquals(listOf<String?>(MessageFeedback.DOWN), ratings)
    }

    @Test
    fun `the selected thumb shows as selected, and choosing it again takes it back`() {
        compose.setContent { MessageBubble(answer.copy(feedback = MessageFeedback.UP), onRate = record) }

        openMenu()
        compose.onNodeWithTag(UiTags.MESSAGE_THUMB_UP).assertIsSelected().performClick()

        assertEquals(listOf<String?>(null), ratings)
    }

    @Test
    fun `a selected thumb down chosen again is taken back`() {
        compose.setContent { MessageBubble(answer.copy(feedback = MessageFeedback.DOWN), onRate = record) }

        openMenu()
        compose.onNodeWithTag(UiTags.MESSAGE_THUMB_DOWN).assertIsSelected()
        compose.onNodeWithTag(UiTags.MESSAGE_THUMB_UP).assertIsNotSelected()
        compose.onNodeWithTag(UiTags.MESSAGE_THUMB_DOWN).performClick()

        assertEquals(listOf<String?>(null), ratings)
    }

    // The comment offered after a thumb down is an `AlertDialog`, which hangs Robolectric's
    // idling (AppNotIdleException), so it is not driven here. Its state lives in the ViewModel
    // (`ChatUiState.commentingMessageId`): opening it, « Envoyer » and « Passer » are covered by
    // `ChatViewModelTest`; the dialog only renders that state.
}
