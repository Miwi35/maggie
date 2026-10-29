package com.maggie.app.voice

import com.maggie.app.voice.VoicePlaybackState.Status
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePlayer : VoiceMessagePlayer {
    var listener: VoiceMessagePlayer.Listener? = null
    var url: String? = null
    var playing = false
    var released = false
    var appliedSpeed = 1f
    var seek: Int? = null
    var focusRefused = false
    override var positionMs: Int = 0

    override fun prepare(url: String, listener: VoiceMessagePlayer.Listener) {
        this.url = url
        this.listener = listener
    }

    override fun start() {
        if (focusRefused) listener?.onFocusLost() else playing = true
    }
    override fun pause() { playing = false }
    override fun seekTo(positionMs: Int) { seek = positionMs; this.positionMs = positionMs }
    override fun setSpeed(speed: Float) { appliedSpeed = speed }
    override fun release() { released = true }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoicePlaybackTest {
    private val players = mutableListOf<FakePlayer>()

    private fun TestScope.playback(resolve: suspend (String) -> String = { "https://signed/$it" }) =
        VoicePlayback(this, { FakePlayer().also(players::add) }, resolve)

    @Test
    fun `playing a message resolves its signed URL then plays and shows the duration`() = runTest {
        val playback = playback()
        playback.toggle("m1", knownDurationMs = 4000)
        runCurrent()
        assertEquals(Status.LOADING, playback.state.value.status)
        assertEquals("https://signed/m1", players[0].url)

        players[0].listener!!.onPrepared(4200)

        assertEquals(Status.PLAYING, playback.state.value.status)
        assertEquals(4200, playback.state.value.durationMs)
        assertTrue(players[0].playing)
        playback.stop()
    }

    @Test
    fun `pausing then resuming keeps the position`() = runTest {
        val playback = playback()
        playback.toggle("m1")
        runCurrent()
        players[0].listener!!.onPrepared(4000)
        players[0].positionMs = 1500

        playback.toggle("m1")
        assertEquals(Status.PAUSED, playback.state.value.status)
        assertEquals(1500, playback.state.value.positionMs)
        assertTrue(!players[0].playing)

        playback.toggle("m1")
        assertEquals(Status.PLAYING, playback.state.value.status)
        assertTrue(players[0].playing)
        playback.stop()
    }

    @Test
    fun `the end of the message goes back to the start, ready to play again`() = runTest {
        val playback = playback()
        playback.toggle("m1")
        runCurrent()
        players[0].listener!!.onPrepared(4000)
        players[0].positionMs = 4000

        players[0].listener!!.onCompleted()

        assertEquals(Status.PAUSED, playback.state.value.status)
        assertEquals(0, playback.state.value.positionMs)
        assertEquals(0, players[0].seek)
    }

    @Test
    fun `a playback error turns the bubble into an error`() = runTest {
        val playback = playback()
        playback.toggle("m1")
        runCurrent()

        players[0].listener!!.onError()

        assertEquals(Status.ERROR, playback.state.value.statusOf("m1"))
        assertEquals(Status.IDLE, playback.state.value.statusOf("other"))
        assertTrue(players[0].released)
    }

    @Test
    fun `an audio that cannot be found is an error in the bubble and nothing else`() = runTest {
        val playback = playback { throw IllegalStateException("404") }

        playback.toggle("m1")
        runCurrent()

        assertEquals(Status.ERROR, playback.state.value.statusOf("m1"))
        assertEquals(Status.IDLE, playback.state.value.statusOf("m2"))
        assertTrue(players.isEmpty())
        // The error is not a dead end: a tap tries again.
        playback.toggle("m1")
        assertEquals(Status.LOADING, playback.state.value.status)
    }

    @Test
    fun `the speed goes x1, x1,5, x2 then back to x1 and reaches the player`() = runTest {
        val playback = playback()
        playback.toggle("m1")
        runCurrent()
        players[0].listener!!.onPrepared(4000)

        val seen = mutableListOf(playback.state.value.speed)
        repeat(3) {
            playback.cycleSpeed()
            seen += playback.state.value.speed
        }

        assertEquals(listOf(1f, 1.5f, 2f, 1f), seen)
        assertEquals(1f, players[0].appliedSpeed)
        playback.cycleSpeed()
        assertEquals(1.5f, players[0].appliedSpeed)
        playback.stop()
    }

    @Test
    fun `the speed chosen is kept for the next message`() = runTest {
        val playback = playback()
        playback.cycleSpeed()
        playback.toggle("m1")
        runCurrent()
        players[0].listener!!.onPrepared(4000)

        assertEquals(1.5f, players[0].appliedSpeed)
        playback.stop()
    }

    @Test
    fun `dragging the progress seeks the player`() = runTest {
        val playback = playback()
        playback.toggle("m1")
        runCurrent()
        players[0].listener!!.onPrepared(4000)

        playback.seekTo("m1", 2500)

        assertEquals(2500, players[0].seek)
        assertEquals(2500, playback.state.value.positionMs)
        playback.stop()
    }

    @Test
    fun `losing the audio focus to a call pauses playback`() = runTest {
        val playback = playback()
        playback.toggle("m1")
        runCurrent()
        players[0].listener!!.onPrepared(4000)

        players[0].listener!!.onFocusLost()

        assertEquals(Status.PAUSED, playback.state.value.status)
        playback.stop()
    }

    @Test
    fun `a refused focus at the start leaves the bubble paused, not playing`() = runTest {
        val playback = VoicePlayback(this, { FakePlayer().also { it.focusRefused = true; players.add(it) } }, { "https://signed/$it" })
        playback.toggle("m1")
        runCurrent()

        players[0].listener!!.onPrepared(4000)

        assertEquals(Status.PAUSED, playback.state.value.status)
        playback.stop()
    }

    @Test
    fun `starting another message stops the first`() = runTest {
        val playback = playback()
        playback.toggle("m1")
        runCurrent()
        players[0].listener!!.onPrepared(4000)

        playback.toggle("m2")
        runCurrent()

        assertTrue(players[0].released)
        assertEquals("m2", playback.state.value.messageId)
        assertEquals(Status.IDLE, playback.state.value.statusOf("m1"))
        playback.stop()
    }

    @Test
    fun `the position follows the player while it plays`() = runTest {
        val playback = playback()
        playback.toggle("m1")
        runCurrent()
        players[0].listener!!.onPrepared(4000)
        runCurrent()

        players[0].positionMs = 1000
        advanceTimeBy(300)
        runCurrent()

        assertEquals(1000, playback.state.value.positionMs)
        playback.stop()
    }
}
