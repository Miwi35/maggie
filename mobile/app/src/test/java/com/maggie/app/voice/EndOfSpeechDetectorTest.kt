package com.maggie.app.voice

import com.maggie.app.voice.EndOfSpeechDetector.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndOfSpeechDetectorTest {
    private val loud = 0.2f
    private val quiet = 0.001f

    private fun detector() = EndOfSpeechDetector(silenceMs = 1_000, noSpeechMs = 3_000, maxMs = 10_000)

    private fun EndOfSpeechDetector.feedFor(level: Float, ms: Long): List<Signal> =
        (1..ms / 100).map { feed(level, 100) }.filter { it != Signal.NONE }

    @Test
    fun `the first loud buffer says the speech started, once`() {
        val d = detector()

        assertEquals(listOf(Signal.SPEECH_STARTED), d.feedFor(loud, 500))
        assertTrue(d.hasSpeech)
    }

    @Test
    fun `a pause shorter than the silence length does not end the speech`() {
        val d = detector()
        d.feedFor(loud, 500)

        assertEquals(emptyList<Signal>(), d.feedFor(quiet, 900))
        assertEquals(emptyList<Signal>(), d.feedFor(loud, 300))
    }

    @Test
    fun `a pause as long as the silence length ends the speech, once`() {
        val d = detector()
        d.feedFor(loud, 500)

        assertEquals(listOf(Signal.SPEECH_ENDED), d.feedFor(quiet, 2_000))
    }

    @Test
    fun `a hesitation in the middle of a sentence restarts the count`() {
        val d = detector()
        d.feedFor(loud, 300)
        d.feedFor(quiet, 800)
        d.feedFor(loud, 100)

        assertEquals(emptyList<Signal>(), d.feedFor(quiet, 800))
        assertEquals(listOf(Signal.SPEECH_ENDED), d.feedFor(quiet, 300))
    }

    @Test
    fun `nobody speaking for the no-speech delay is reported as no speech`() {
        val d = detector()

        assertEquals(listOf(Signal.NO_SPEECH), d.feedFor(quiet, 5_000))
        assertFalse(d.hasSpeech)
    }

    @Test
    fun `a voice that never stops is cut at the maximum length`() {
        val d = detector()

        assertEquals(listOf(Signal.SPEECH_STARTED, Signal.TOO_LONG), d.feedFor(loud, 20_000))
    }

    @Test
    fun `the spoken length runs from the first loud buffer to the last, not the silence around it`() {
        val d = detector()
        d.feedFor(quiet, 500)
        d.feedFor(loud, 1_500)
        d.feedFor(quiet, 1_000)

        assertEquals(1_500L, d.spokenMs)
    }

    @Test
    fun `nothing spoken is zero milliseconds of speech`() {
        val d = detector()
        d.feedFor(quiet, 500)

        assertEquals(0L, d.spokenMs)
    }
}
