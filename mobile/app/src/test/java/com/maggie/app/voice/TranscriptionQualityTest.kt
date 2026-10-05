package com.maggie.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One test per criterion of the judge that picks between the phone and Whisper (MAG-222). */
class TranscriptionQualityTest {

    @Test
    fun `a good sentence with a good score is used`() {
        assertTrue(
            TranscriptionQuality.isGoodEnough(
                text = "ajoute des tomates à la liste de courses",
                confidence = 0.9f,
                spokenMillis = 3_000,
            ),
        )
    }

    @Test
    fun `an empty result goes to Whisper`() {
        assertFalse(TranscriptionQuality.isGoodEnough("", confidence = 0.99f, spokenMillis = 3_000))
    }

    @Test
    fun `a result made of spaces goes to Whisper`() {
        assertFalse(TranscriptionQuality.isGoodEnough("   ", confidence = 0.99f, spokenMillis = 3_000))
    }

    @Test
    fun `a low confidence goes to Whisper`() {
        assertFalse(
            TranscriptionQuality.isGoodEnough(
                text = "ajoute des tomates à la liste de courses",
                confidence = 0.3f,
                spokenMillis = 3_000,
            ),
        )
    }

    @Test
    fun `a confidence just under the bar goes to Whisper, just over it does not`() {
        val text = "ajoute des tomates à la liste de courses"
        assertFalse(TranscriptionQuality.isGoodEnough(text, TranscriptionQuality.MIN_CONFIDENCE - 0.01f, 3_000))
        assertTrue(TranscriptionQuality.isGoodEnough(text, TranscriptionQuality.MIN_CONFIDENCE, 3_000))
    }

    @Test
    fun `an engine that reports no confidence is judged on the text alone`() {
        assertTrue(TranscriptionQuality.isGoodEnough("ajoute des tomates", confidence = null, spokenMillis = 2_000))
    }

    @Test
    fun `three words for ten seconds of speech go to Whisper`() {
        // What the engine used to do: stop at the first pause and keep the beginning.
        assertFalse(TranscriptionQuality.isGoodEnough("ajoute des tomates", confidence = 0.95f, spokenMillis = 10_000))
    }

    @Test
    fun `a short answer to a short hold is used`() {
        assertTrue(TranscriptionQuality.isGoodEnough("oui", confidence = null, spokenMillis = 900))
    }

    @Test
    fun `the rate is not applied below the floor`() {
        val justUnder = TranscriptionQuality.RATE_FLOOR_MS - 1
        assertTrue(TranscriptionQuality.isGoodEnough("ok", confidence = null, spokenMillis = justUnder))
        assertFalse(TranscriptionQuality.isGoodEnough("ok", confidence = null, spokenMillis = 5_000))
    }

    @Test
    fun `the expected length follows the spoken duration`() {
        assertEquals(6, TranscriptionQuality.minCharsFor(2_000))
        assertEquals(30, TranscriptionQuality.minCharsFor(10_000))
    }
}
