package com.maggie.app.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.screentest.ScreenRule
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
}
