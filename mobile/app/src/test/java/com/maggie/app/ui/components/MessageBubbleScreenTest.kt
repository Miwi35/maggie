package com.maggie.app.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.MessageFeedback
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // --- Thumbs on Maggie's answers (MAG-23) ---

    private val answer = ChatMessage(id = "a-1", role = "assistant", content = "Il fait beau.")
    private val ratings = mutableListOf<Pair<String?, String?>>()
    private val record: (String?, String?) -> Unit = { rating, comment -> ratings += rating to comment }

    @Test
    fun `an answer has both thumbs, none selected until rated`() {
        compose.setContent { MessageBubble(answer, onRate = record) }

        compose.onNodeWithContentDescription("Réponse utile").assertIsDisplayed().assertIsOff()
        compose.onNodeWithContentDescription("Réponse à revoir").assertIsDisplayed().assertIsOff()
    }

    @Test
    fun `a question has no thumbs`() {
        compose.setContent { MessageBubble(question, onRate = record) }

        compose.onNodeWithContentDescription("Réponse utile").assertDoesNotExist()
        compose.onNodeWithContentDescription("Réponse à revoir").assertDoesNotExist()
    }

    @Test
    fun `the thumbs are large enough to tap`() {
        compose.setContent { MessageBubble(answer, onRate = record) }

        compose.onNodeWithContentDescription("Réponse utile").assertTouchHeightIsEqualTo(48.dp)
    }

    @Test
    fun `a thumb up is sent at once`() {
        compose.setContent { MessageBubble(answer, onRate = record) }

        compose.onNodeWithContentDescription("Réponse utile").performClick()

        assertEquals(listOf<Pair<String?, String?>>(MessageFeedback.UP to null), ratings)
    }

    @Test
    fun `the selected thumb shows as on, and tapping it again takes it back`() {
        compose.setContent { MessageBubble(answer.copy(feedback = MessageFeedback.UP), onRate = record) }

        compose.onNodeWithContentDescription("Réponse utile").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Réponse à revoir").assertIsOff()

        assertEquals(listOf<Pair<String?, String?>>(null to null), ratings)
    }

    @Test
    fun `a selected thumb down tapped again is taken back without asking anything`() {
        compose.setContent { MessageBubble(answer.copy(feedback = MessageFeedback.DOWN), onRate = record) }

        compose.onNodeWithContentDescription("Réponse à revoir").assertIsOn().performClick()

        assertEquals(listOf<Pair<String?, String?>>(null to null), ratings)
    }

    // The thumb-down dialog is not driven here: an `AlertDialog` hangs Robolectric's idling
    // (AppNotIdleException), and no screen test of the app opens one. The comment it carries
    // is covered by `ChatViewModelTest`; the dialog itself is left to a Maestro journey.
}
