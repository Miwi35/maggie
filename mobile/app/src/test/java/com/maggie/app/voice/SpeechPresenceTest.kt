package com.maggie.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first gate against « Thank you for watching » (retour de recette MAG-222): a
 * clip that was never loud enough never leaves the phone, so Whisper is never given
 * silence to invent over.
 */
class SpeechPresenceTest {

    /** One buffer of the recorder's size, at 16 kHz mono: 64 ms. */
    private val bufferMs = 64L

    private fun presence() = SpeechPresence()

    private fun SpeechPresence.hold(level: Float, millis: Long) {
        repeat((millis / bufferMs).toInt()) { feed(level, bufferMs) }
    }

    @Test
    fun `a hold with a voice in it heard speech`() {
        val presence = presence()

        presence.hold(0.002f, 320) // the room before he starts
        presence.hold(0.12f, 1_500) // speaking
        presence.hold(0.002f, 320)

        assertTrue(presence.heardSpeech)
    }

    @Test
    fun `a silent hold heard nothing`() {
        val presence = presence()

        presence.hold(0.0008f, 3_000)

        assertFalse(presence.heardSpeech)
    }

    @Test
    fun `a hold of plain noise heard nothing`() {
        // A fan, a street: loud enough to be audible, far below a voice at arm's
        // length — this is the level EndOfSpeechDetector was tuned on.
        val presence = presence()

        presence.hold(0.009f, 4_000)

        assertFalse(presence.heardSpeech)
    }

    @Test
    fun `a single loud buffer is not a sentence`() {
        // A door, a tap on the phone: loud, and over before it means anything.
        val presence = presence()

        presence.hold(0.0008f, 1_000)
        presence.feed(0.4f, bufferMs)
        presence.hold(0.0008f, 1_000)

        assertFalse(presence.heardSpeech)
    }

    @Test
    fun `a recorder that reports no level is given the benefit of the doubt`() {
        // The e2e flavor's placeholder recorder, and any device that cannot measure:
        // the server's own gate has the last word, so this one does not refuse blind.
        val presence = presence()

        assertTrue(presence.heardSpeech)
        assertFalse(presence.measured)
    }

    @Test
    fun `a pause in the middle does not end the measurement`() {
        // What the release alone may end (MAG-221). The voice after the pause counts,
        // and the span covers the whole sentence.
        val presence = presence()

        presence.hold(0.12f, 640)
        presence.hold(0.001f, 3_000)
        presence.hold(0.12f, 640)

        assertTrue(presence.heardSpeech)
        assertTrue("the span should cover the pause", presence.spokenMs >= 4_000)
    }

    @Test
    fun `the spoken span runs from the first loud buffer to the last`() {
        val presence = presence()

        presence.hold(0.001f, 1_024) // 16 buffers of silence first
        presence.hold(0.2f, 1_024)

        assertTrue(presence.measured)
        assertEquals(1_024, presence.spokenMs)
    }

    @Test
    fun `nothing spoken leaves an empty span`() {
        val presence = presence()

        presence.hold(0.0005f, 2_000)

        assertEquals(0L, presence.spokenMs)
    }
}
