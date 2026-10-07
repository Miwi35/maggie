package com.maggie.app.ui.components

import android.content.Context
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.voice.AudioRecorder
import com.maggie.app.voice.VoiceHint
import com.maggie.app.voice.VoiceManager
import com.maggie.app.voice.VoiceState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

private class HeldRecorder : AudioRecorder {
    var stopped = false

    override fun start(file: File, pcmSink: OutputStream?) {
        file.writeText("audio")
    }

    override fun stop() {
        stopped = true
    }

    override fun release() = Unit
}

/**
 * The mic button as a finger meets it (MAG-221): what the *gesture* does while the
 * button is held, which `VoiceManagerTest` cannot see because it calls `pressDown`
 * and `pressRelease` itself.
 *
 * The owner's third refusal was a listening that ended while his finger was still on
 * the button — on the phone's log, the recording closed between 0.7 s and 5 s before
 * the touch lifted, and nothing was sent. Nothing in the voice path stops a recording
 * on its own; the button did, by reading a finger that drifted off the 64 dp circle —
 * or a button that pulses under it — as a swipe away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class VoiceControlBarScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private lateinit var voiceManager: VoiceManager
    private lateinit var recorder: HeldRecorder
    private lateinit var testScope: TestScope
    private var now = 0L
    private val sent = mutableListOf<String>()

    @Before
    fun setUp() {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns Files.createTempDirectory("voice-bar").toFile()
        val api = mockk<MaggieApiService>(relaxed = true)
        coEvery { api.transcribe(any(), any()) } returns "ajoute du lait"
        recorder = HeldRecorder()
        testScope = TestScope()
        now = 0L
        voiceManager = VoiceManager(
            context,
            api,
            mockk<UserPreferenceRepository>(relaxed = true),
            recorderFactory = { recorder },
            clock = { now },
            scope = testScope,
        )
        // The mic pulses forever while listening; the clock is advanced by hand so the
        // test does not wait for an animation that never ends.
        compose.mainClock.autoAdvance = false
    }

    private fun show(parent: @Composable (@Composable () -> Unit) -> Unit = { it() }) {
        compose.setContent {
            parent { VoiceControlBar(voiceManager = voiceManager, onResult = { sent += it }) }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun mic() = compose.onNodeWithTag(UiTags.VOICE_MIC)

    private fun settle(ms: Long) {
        now += ms
        compose.mainClock.advanceTimeBy(ms)
    }

    private fun releaseAndTranscribe() {
        mic().performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        testScope.runCurrent()
    }

    @Test
    fun `holding the mic listens until the finger lifts, then sends`() {
        show()

        mic().performTouchInput { down(center) }
        settle(800)
        assertEquals(VoiceState.LISTENING, voiceManager.state.value)

        releaseAndTranscribe()

        assertEquals(listOf("ajoute du lait"), sent)
    }

    @Test
    fun `a brief tap sends nothing and asks to hold`() {
        show()

        mic().performTouchInput { down(center) }
        settle(80)
        releaseAndTranscribe()

        assertTrue(sent.isEmpty())
        assertEquals(VoiceState.IDLE, voiceManager.state.value)
        assertEquals(VoiceHint.HOLD_LONGER, voiceManager.hint.value)
    }

    @Test
    fun `sliding well off the button cancels, and nothing is sent`() {
        show()

        mic().performTouchInput { down(center) }
        settle(800)
        mic().performTouchInput { moveTo(Offset(width + 150.dp.toPx(), center.y)) }
        compose.mainClock.advanceTimeByFrame()

        assertEquals(VoiceState.IDLE, voiceManager.state.value)
        assertTrue(recorder.stopped)

        releaseAndTranscribe()
        assertTrue(sent.isEmpty())
    }

    /** The reproduction: a finger is bigger than the button and never keeps still. */
    @Test
    fun `a finger drifting just past the edge of the button keeps listening`() {
        show()

        mic().performTouchInput { down(center) }
        settle(800)
        mic().performTouchInput { moveTo(Offset(width + 12.dp.toPx(), center.y + 6.dp.toPx())) }
        settle(2000)

        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
        assertFalse(recorder.stopped)

        mic().performTouchInput { moveTo(center) }
        releaseAndTranscribe()

        assertEquals(listOf("ajoute du lait"), sent)
    }

    @Test
    fun `the cancel margin is 48 dp past the edge`() {
        show()

        mic().performTouchInput { down(center) }
        settle(800)
        mic().performTouchInput { moveTo(Offset(width + 40.dp.toPx(), center.y)) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(VoiceState.LISTENING, voiceManager.state.value)

        mic().performTouchInput { moveTo(Offset(width + 60.dp.toPx(), center.y)) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(VoiceState.IDLE, voiceManager.state.value)
    }

    /** Pulsing under a held finger moved the edge of the hit area; releasing there sent nothing. */
    @Test
    fun `lifting the finger just past the edge still sends`() {
        show()

        mic().performTouchInput { down(center) }
        settle(800)
        mic().performTouchInput { moveTo(Offset(width + 10.dp.toPx(), center.y)) }
        releaseAndTranscribe()

        assertEquals(listOf("ajoute du lait"), sent)
    }

    /**
     * The chat is a bottom sheet: a finger that creeps down while the owner speaks is
     * a drag to it, and a sheet that follows the finger takes the button with it.
     */
    @Test
    fun `a parent that drags does not take the touch away from a held mic`() {
        var dragged = 0f
        show { content ->
            Box(
                Modifier
                    .fillMaxSize()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { dragged += it },
                    ),
            ) { content() }
        }

        mic().performTouchInput { down(center) }
        settle(800)
        mic().performTouchInput { moveTo(Offset(center.x, center.y + 30.dp.toPx())) }
        settle(1000)

        assertEquals(0f, dragged, 0.001f)
        assertEquals(VoiceState.LISTENING, voiceManager.state.value)

        releaseAndTranscribe()
        assertEquals(listOf("ajoute du lait"), sent)
    }
}
