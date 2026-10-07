package com.maggie.app.voice

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The journeys' engine, tested where it is compiled: `src/testE2e/` only exists for the
 * `e2e` flavor, like [ScriptedDeviceSpeech] itself.
 */
class ScriptedDeviceSpeechTest {

    private class Heard : DeviceSpeechRecognizer.Listener {
        val partials = mutableListOf<String>()
        val results = mutableListOf<DeviceSpeechResult>()

        override fun onPartial(text: String) {
            partials += text
        }

        override fun onResult(result: DeviceSpeechResult) {
            results += result
        }

        override fun onUnavailable(reason: String, fatal: Boolean) =
            throw AssertionError("the scripted engine is never out: $reason")
    }

    @Test
    fun `the e2e flavor hands the voice path the scripted engine`() {
        val factory = deviceSpeechFactory(mockk<Context>())
        val engine = factory()

        assertTrue(engine is ScriptedDeviceSpeech)
        assertTrue(engine.isAvailable)
        assertNotSame("one engine per hold, as on a phone", engine, factory())
    }

    @Test
    fun `it listens for itself and shows the sentence while the button is held`() {
        val heard = Heard()

        assertNull(ScriptedDeviceSpeech().start(heard))

        assertEquals(listOf(ScriptedDeviceSpeech.SENTENCE), heard.partials)
        assertTrue(heard.results.isEmpty())
    }

    @Test
    fun `the release delivers the fixed sentence once`() {
        val heard = Heard()
        val engine = ScriptedDeviceSpeech()
        engine.start(heard)

        engine.stopListening()
        engine.stopListening()

        assertEquals(listOf(DeviceSpeechResult(ScriptedDeviceSpeech.SENTENCE, ScriptedDeviceSpeech.CONFIDENCE)), heard.results)
    }

    @Test
    fun `the quality judge takes the sentence, so Whisper is never asked`() {
        // Ten seconds is longer than any `longPressOn` holds: the sentence is still
        // long enough for it, and rated above the floor.
        assertTrue(TranscriptionQuality.isGoodEnough(ScriptedDeviceSpeech.SENTENCE, ScriptedDeviceSpeech.CONFIDENCE, 10_000L))
    }

    @Test
    fun `what reaches Maggie is the sentence without its hesitation`() {
        assertEquals(
            "ajoute des tomates à la liste de courses s'il te plaît",
            HesitationFilter.strip(ScriptedDeviceSpeech.SENTENCE),
        )
    }
}
