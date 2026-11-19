package com.maggie.app.voice

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Maggie's live voice plays at normal speed; only chat audio bubbles are speed-adjustable (MAG-699). */
@RunWith(RobolectricTestRunner::class)
class StreamingSpeechPlayerTest {

    private val exo = mockk<ExoPlayer>(relaxed = true)
    private val listener = slot<Player.Listener>()

    private fun player(): StreamingSpeechPlayer {
        every { exo.addListener(capture(listener)) } returns Unit
        return StreamingSpeechPlayer(ApplicationProvider.getApplicationContext(), newPlayer = { exo }, runOnMain = { it.run() })
    }

    @Test
    fun `live speech is played without any speed setting`() {
        player().play(SpeechStream()) {}

        verify(exactly = 1) { exo.play() }
        verify(exactly = 0) { exo.setPlaybackSpeed(any()) }
        verify(exactly = 0) { exo.playbackParameters = any() }
    }

    @Test
    fun `the end of the audio finishes the playback once`() {
        var finished = 0
        player().play(SpeechStream()) { finished++ }

        listener.captured.onPlaybackStateChanged(Player.STATE_ENDED)
        listener.captured.onPlaybackStateChanged(Player.STATE_ENDED)

        assertEquals(1, finished)
    }

    @Test
    fun `a player error finishes the playback`() {
        var finished = 0
        player().play(SpeechStream()) { finished++ }

        listener.captured.onPlayerError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))

        assertEquals(1, finished)
    }
}
