package com.maggie.app.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.AudioAttachment
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.voice.VoiceMessagePlayer
import com.maggie.app.voice.VoicePlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The voice bubble of the chat (MAG-686): player, speed, folded transcript without delivery tags, error. */
@RunWith(AndroidJUnit4::class)
class VoiceBubbleScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val message = ChatMessage(
        id = "m1",
        role = "assistant",
        content = "[warmly] Bonjour, voici ta journée",
        audio = AudioAttachment(durationMs = 65_000, size = 1, url = "/agent/messages/m1/audio", transcript = "[warmly] Bonjour, voici ta journée"),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val player = object : VoiceMessagePlayer {
        override val positionMs = 0
        override fun prepare(url: String, listener: VoiceMessagePlayer.Listener) = listener.onPrepared(65_000)
        override fun start() {}
        override fun pause() {}
        override fun seekTo(positionMs: Int) {}
        override fun setSpeed(speed: Float) {}
        override fun release() {}
    }

    @After
    fun tearDown() = scope.cancel()

    private fun show(resolve: suspend (String) -> String = { "https://signed/$it" }): VoicePlayback {
        val playback = VoicePlayback(scope, { player }, resolve)
        compose.setContent {
            CompositionLocalProvider(LocalVoicePlayback provides playback) { MessageBubble(message) }
        }
        return playback
    }

    @Test
    fun `the bubble shows the player, the duration and the speed instead of the text`() {
        show()

        compose.onNodeWithTag(UiTags.VOICE_PLAY).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.VOICE_SEEK).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.VOICE_DURATION).assertTextContains("1:05")
        compose.onNodeWithTag(UiTags.VOICE_SPEED).assertTextContains("×1")
        compose.onNodeWithTag(UiTags.VOICE_TRANSCRIPT).assertDoesNotExist()
    }

    @Test
    fun `the speed button goes x1,5 then x2 then x1`() {
        show()
        for (expected in listOf("×1,5", "×2", "×1")) {
            compose.onNodeWithTag(UiTags.VOICE_SPEED).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(UiTags.VOICE_SPEED).assertTextEquals(expected)
        }
    }

    @Test
    fun `the transcript unfolds under the bubble without a delivery tag`() {
        show()

        compose.onNodeWithTag(UiTags.VOICE_TRANSCRIPT_TOGGLE).performClick()

        compose.onNodeWithTag(UiTags.VOICE_TRANSCRIPT).assertIsDisplayed().assertTextContains("Bonjour, voici ta journée")
        compose.onNodeWithText("[warmly]", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an audio that cannot be read says so in the bubble`() {
        show { throw IllegalStateException("404") }

        compose.onNodeWithTag(UiTags.VOICE_PLAY).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.VOICE_ERROR).assertIsDisplayed()
    }
}
