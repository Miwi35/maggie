package com.maggie.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.FakeChat
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The conversation kept on screen beside the content, on a tablet (MAG-35).
 *
 * `AppShellScreenTest` says *where* the panel goes, with a stand-in `Box`; this
 * says the real one works. It needs its own test and not only the shell's because
 * the sheet half of the same `ChatSheet.kt` has the Maestro flows as a net —
 * `01-login-chat` and `02-voice-overlay` drive `chat_input`, `chat_send` and
 * `chat_mic` on a phone AVD — while the panel has nothing: the CI emulator is a
 * phone and will never open a 1280 dp window.
 *
 * What is asserted here is what the panel owes the collapsed bar it replaces: the
 * conversation, a field that reaches the repository, and the two buttons that were
 * on the bar — because losing the mic on a tablet would mean losing voice on a
 * tablet.
 */
@RunWith(AndroidJUnit4::class)
// The panel only exists from 840 dp wide; its own width is 360 dp.
@Config(qualifiers = "w1280dp-h800dp-xhdpi")
class ChatPanelScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `the panel shows the conversation it was given`() {
        val chat = FakeChat()
        compose.setContent { ChatPanel(viewModel = chat.viewModel) }

        compose.onNodeWithText("Qu'est-ce qu'il me reste à acheter ?").assertIsDisplayed()
        compose.onNodeWithText("Des poireaux et des câpres aux Halles du voisin.").assertIsDisplayed()
    }

    /** It is the layout, not an overlay: there is nothing to dismiss, so no close button. */
    @Test
    fun `the panel has no close button, unlike the sheet`() {
        val chat = FakeChat()
        compose.setContent { ChatPanel(viewModel = chat.viewModel) }

        compose.onNodeWithTag(UiTags.CHAT_CLOSE).assertDoesNotExist()
    }

    @Test
    fun `typing and sending reaches the repository with what was typed`() {
        val chat = FakeChat()
        compose.setContent { ChatPanel(viewModel = chat.viewModel) }

        compose.onNodeWithTag(UiTags.CHAT_INPUT).performTextInput("Ajoute du pain")
        compose.onNodeWithTag(UiTags.CHAT_SEND).performClick()
        compose.waitForIdle()

        assertEquals(listOf("Ajoute du pain"), chat.sent)
    }

    /**
     * Asserted on the button itself and not on [FakeChat.sent]: `ChatViewModel`
     * returns early on a blank message anyway, so an empty click would never reach
     * the repository even with `enabled` dropped — a test reading `sent` would pass
     * on a button that is live when it should be grey.
     */
    @Test
    fun `the send button stays disabled until something is typed`() {
        val chat = FakeChat()
        compose.setContent { ChatPanel(viewModel = chat.viewModel) }

        compose.onNodeWithTag(UiTags.CHAT_SEND).assertIsNotEnabled()
    }

    /**
     * The panel replaces [ChatBottomBar], so it owes its two buttons. The mic is the
     * one that matters: it is the only way into voice mode, and the panel is what a
     * tablet has instead of the bar.
     */
    @Test
    fun `the mic and the contexts button are on the panel, and report their taps`() {
        var micTaps = 0
        var brainTaps = 0
        val chat = FakeChat()
        compose.setContent {
            ChatPanel(
                viewModel = chat.viewModel,
                onMicClick = { micTaps++ },
                onBrainClick = { brainTaps++ },
                activeContextCount = 3,
            )
        }

        compose.onNodeWithTag(UiTags.CHAT_MIC).performClick()
        compose.onNodeWithTag(UiTags.CHAT_CONTEXTS).performClick()

        assertEquals(1, micTaps)
        assertEquals(1, brainTaps)
        compose.onNodeWithText("3").assertIsDisplayed()
    }
}
