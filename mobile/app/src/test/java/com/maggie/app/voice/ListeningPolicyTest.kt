package com.maggie.app.voice

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningPolicyTest {

    @Test
    fun `listens when nothing else uses the audio`() {
        assertTrue(ListeningPolicy.shouldListen(callActive = false, mediaPlaying = false))
    }

    @Test
    fun `pauses during a call`() {
        assertFalse(ListeningPolicy.shouldListen(callActive = true, mediaPlaying = false))
    }

    @Test
    fun `pauses while media is playing`() {
        assertFalse(ListeningPolicy.shouldListen(callActive = false, mediaPlaying = true))
    }

    @Test
    fun `resumes once both the call and the media are over`() {
        assertFalse(ListeningPolicy.shouldListen(callActive = true, mediaPlaying = true))
        assertTrue(ListeningPolicy.shouldListen(callActive = false, mediaPlaying = false))
    }

    @Test
    fun `ringing, in-call and in-communication are call modes`() {
        listOf(
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING,
        ).forEach { assertTrue("mode $it", ListeningPolicy.isCallMode(it)) }
    }

    @Test
    fun `normal mode is not a call mode`() {
        assertFalse(ListeningPolicy.isCallMode(AudioManager.MODE_NORMAL))
    }
}
