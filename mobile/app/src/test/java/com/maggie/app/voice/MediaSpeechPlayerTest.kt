package com.maggie.app.voice

import android.media.MediaPlayer
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import org.junit.Test

/** Maggie's live voice plays at normal speed; only chat audio bubbles are speed-adjustable (MAG-699). */
class MediaSpeechPlayerTest {

    @Test
    fun `live speech is played without any speed setting`() {
        val mediaPlayer = mockk<MediaPlayer>(relaxed = true)
        val player = MediaSpeechPlayer(newPlayer = { mediaPlayer })

        player.play(File("speech.mp3")) {}

        verify(exactly = 1) { mediaPlayer.start() }
        verify(exactly = 0) { mediaPlayer.playbackParams = any() }
    }
}
