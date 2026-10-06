package com.maggie.app.voice

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * One run of a phone engine, driven by hand. A real one closes its own sentence on a
 * silence, whatever the button is doing — which is the behaviour refused in recette.
 */
private class ScriptedRun(
    override val isAvailable: Boolean = true,
    val sink: OutputStream? = ByteArrayOutputStream(),
) : DeviceSpeechRecognizer {
    var listener: DeviceSpeechRecognizer.Listener? = null
    var started = false
    var stopped = false
    var destroyed = false

    override fun start(listener: DeviceSpeechRecognizer.Listener): OutputStream? {
        this.listener = listener
        started = true
        return sink
    }

    override fun stopListening() {
        stopped = true
    }

    override fun destroy() {
        destroyed = true
    }

    /** The engine closing a sentence by itself — on a silence, or when asked to stop. */
    fun hears(text: String, confidence: Float? = 0.9f) =
        listener?.onResult(DeviceSpeechResult(text, confidence))

    fun givesUp(reason: String = "no match", fatal: Boolean = false) =
        listener?.onUnavailable(reason, fatal)

    fun partial(text: String) = listener?.onPartial(text)
}

private class Recorded : DeviceSpeechRecognizer.Listener {
    var partials = mutableListOf<String>()
    var result: DeviceSpeechResult? = null
    var unavailable: String? = null
    var calls = 0

    override fun onPartial(text: String) {
        partials += text
    }

    override fun onResult(result: DeviceSpeechResult) {
        this.result = result
        calls += 1
    }

    override fun onUnavailable(reason: String, fatal: Boolean) {
        unavailable = reason
        calls += 1
    }
}

/**
 * Hold to talk must mean held (retour de recette MAG-222).
 *
 * « L'écoute s'arrête avant que le propriétaire relâche le bouton » — Google's engine
 * closes its sentence on a silence of its own accord, so the second half of what was
 * said never arrived. [SegmentedDeviceSpeech] makes the release, and nothing else, the
 * end of the sentence: every run the engine closes by itself becomes a segment and the
 * next one starts at once, on the same microphone.
 */
class SegmentedDeviceSpeechTest {

    private val runs = ArrayDeque<ScriptedRun>()
    private val started = mutableListOf<ScriptedRun>()

    @Before
    fun silenceLogcat() {
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun segmented(vararg scripted: ScriptedRun): SegmentedDeviceSpeech {
        runs.addAll(scripted)
        return SegmentedDeviceSpeech {
            (runs.removeFirstOrNull() ?: ScriptedRun()).also { started += it }
        }
    }

    @Test
    fun `a sentence with a pause in the middle arrives whole`() {
        val first = ScriptedRun()
        val second = ScriptedRun()
        val engine = segmented(first, second)
        val heard = Recorded()

        engine.start(heard)
        // Google closes its sentence on the pause, button still held.
        first.hears("ajoute des tomates")
        assertNull("nothing may be delivered while the button is held", heard.result)
        assertTrue("the next run must start at once, or the rest is lost", second.started)

        // Speech resumes and the new run follows it; only then does the owner let go,
        // and the engine closes on the release.
        second.partial("et aussi du")
        engine.stopListening()
        second.hears("et aussi du pain")

        assertEquals("ajoute des tomates et aussi du pain", heard.result?.text)
        assertEquals(1, heard.calls)
    }

    @Test
    fun `the release is what ends the sentence`() {
        val first = ScriptedRun()
        val engine = segmented(first)
        val heard = Recorded()

        engine.start(heard)
        engine.stopListening()
        first.hears("ajoute des tomates")

        assertEquals("ajoute des tomates", heard.result?.text)
        assertTrue(first.stopped)
    }

    @Test
    fun `a run that hears nothing is still a chance for the next one`() {
        val first = ScriptedRun()
        val second = ScriptedRun()
        val engine = segmented(first, second)
        val heard = Recorded()

        engine.start(heard)
        first.givesUp("no match")
        assertTrue(second.started)

        engine.stopListening()
        second.hears("ajoute des tomates")

        assertEquals("ajoute des tomates", heard.result?.text)
    }

    @Test
    fun `what was heard before the release survives a last run that hears nothing`() {
        val first = ScriptedRun()
        val second = ScriptedRun()
        val engine = segmented(first, second)
        val heard = Recorded()

        engine.start(heard)
        first.hears("ajoute des tomates")
        engine.stopListening()
        second.givesUp("no match")

        assertEquals("ajoute des tomates", heard.result?.text)
    }

    @Test
    fun `an engine that is out for good is not restarted`() {
        val first = ScriptedRun()
        val engine = segmented(first)
        val heard = Recorded()

        engine.start(heard)
        first.givesUp("insufficient permissions", fatal = true)

        assertEquals(1, started.size)
        engine.stopListening()
        assertEquals("insufficient permissions", heard.unavailable)
        assertEquals(1, heard.calls)
    }

    @Test
    fun `an engine that keeps failing stops being restarted`() {
        val engine = segmented()
        val heard = Recorded()

        engine.start(heard)
        repeat(SegmentedDeviceSpeech.MAX_RUNS + 4) { started.last().givesUp("no match") }

        assertEquals(SegmentedDeviceSpeech.MAX_RUNS, started.size)
        engine.stopListening()
        assertEquals(1, heard.calls)
        assertNull(heard.result)
    }

    @Test
    fun `releasing while nothing is listening still answers`() {
        val first = ScriptedRun()
        val engine = segmented(first)
        val heard = Recorded()

        engine.start(heard)
        first.givesUp("insufficient permissions", fatal = true)
        engine.stopListening()

        // Nothing is live to call us back: the answer cannot wait for the timeout, or
        // the segments already heard would be thrown away for nothing.
        assertEquals(1, heard.calls)
    }

    @Test
    fun `the live text is the whole sentence so far`() {
        val first = ScriptedRun()
        val second = ScriptedRun()
        val engine = segmented(first, second)
        val heard = Recorded()

        engine.start(heard)
        first.partial("ajoute des")
        first.hears("ajoute des tomates")
        second.partial("et du")

        assertEquals(listOf("ajoute des", "ajoute des tomates et du"), heard.partials)
    }

    @Test
    fun `the weakest segment is what the whole sentence is worth`() {
        val first = ScriptedRun()
        val second = ScriptedRun()
        val engine = segmented(first, second)
        val heard = Recorded()

        engine.start(heard)
        first.hears("ajoute des tomates", confidence = 0.9f)
        engine.stopListening()
        second.hears("et du pain", confidence = 0.3f)

        assertEquals(0.3f, heard.result?.confidence ?: 0f, 0.0001f)
    }

    @Test
    fun `a sentence nobody rated is handed over unrated`() {
        val first = ScriptedRun()
        val engine = segmented(first)
        val heard = Recorded()

        engine.start(heard)
        first.hears("ajoute des tomates", confidence = null)
        engine.stopListening()

        assertNull(heard.result?.confidence)
    }

    // --- The microphone follows the runs ---

    @Test
    fun `the audio goes to whichever run is listening`() {
        val first = ScriptedRun()
        val second = ScriptedRun()
        val engine = segmented(first, second)

        val sink = engine.start(Recorded())!!
        sink.write("un".toByteArray())
        first.hears("un")
        sink.write("deux".toByteArray())

        assertEquals("un", first.sink.toString())
        assertEquals("deux", second.sink.toString())
    }

    @Test
    fun `closing the audio is the release, and the recorder never sees the engine fail`() {
        val first = ScriptedRun(sink = RefusingSink())
        val engine = segmented(first)
        val heard = Recorded()

        val sink = engine.start(heard)!!
        // A pipe whose reader let go: the recording must not care, it is the clip
        // Whisper falls back on.
        sink.write("un".toByteArray())
        sink.close()
        first.hears("ajoute des tomates")

        assertEquals("ajoute des tomates", heard.result?.text)
    }

    @Test
    fun `an engine that opens the microphone itself is left to it`() {
        val first = ScriptedRun(sink = null)
        val engine = segmented(first)

        assertNull(engine.start(Recorded()))
    }

    @Test
    fun `a phone with no engine says so without starting anything`() {
        val engine = SegmentedDeviceSpeech { ScriptedRun(isAvailable = false) }

        assertFalse(engine.isAvailable)
    }

    @Test
    fun `releasing everything destroys the run that was listening`() {
        val first = ScriptedRun()
        val engine = segmented(first)

        engine.start(Recorded())
        engine.destroy()

        assertTrue(first.destroyed)
    }
}

/** A pipe the engine stopped reading: every write fails. */
private class RefusingSink : OutputStream() {
    override fun write(b: Int) = throw IOException("broken pipe")

    override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("broken pipe")
}
