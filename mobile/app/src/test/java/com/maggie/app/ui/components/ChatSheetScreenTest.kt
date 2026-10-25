package com.maggie.app.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.FakeChat
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone's chat sheet to the letter of `charte/Chat-Tel` (MAG-624), in text mode (a `Dialog`).
 * Robolectric does not deliver a click into a dialog window, so what the mic and the ✕ do is
 * asserted on the panel, which has the same buttons (`ChatPanelScreenTest`), and by the Maestro journeys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class ChatSheetScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun open() {
        val chat = FakeChat()
        compose.setContent {
            ChatSheet(
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                viewModel = chat.viewModel,
                onDismiss = {},
            )
        }
    }

    @Test
    fun `the header is the threads and the close button, with no title and no search`() {
        open()

        compose.onNodeWithTag(UiTags.CHAT_THREADS).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CHAT_CLOSE).assertIsDisplayed()
        compose.onNodeWithContentDescription("Rechercher").assertDoesNotExist()
        compose.onNodeWithText("Maggie").assertDoesNotExist()
    }

    @Test
    fun `Chat and Mind are a pill, Chat selected first`() {
        open()

        compose.onNodeWithTag(UiTags.CHAT_TAB).assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag(UiTags.MIND_TAB).assertIsDisplayed().assertIsNotSelected()
    }

    @Test
    fun `the input has the mic on its left and Écrire à Maggie as its placeholder`() {
        open()

        compose.onNodeWithText("Écrire à Maggie…").assertIsDisplayed()
        val micBounds = compose.onNodeWithTag(UiTags.CHAT_MIC).fetchSemanticsNode().boundsInRoot
        val inputBounds = compose.onNodeWithTag(UiTags.CHAT_INPUT).fetchSemanticsNode().boundsInRoot
        assert(micBounds.right <= inputBounds.left + 1f) { "mic $micBounds should be left of the input $inputBounds" }
    }

    @Test
    fun `an answer has no thumbs under it`() {
        open()

        compose.onNodeWithTag(UiTags.MESSAGE_THUMB_UP).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.MESSAGE_THUMB_DOWN).assertDoesNotExist()
    }
}
