package com.maggie.app.voice

import android.util.Log
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TranscriptCleanup
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

private class LevelRecorder : AudioRecorder {
    var started = false
    var stopped = false
    var levels: ((Float, Long) -> Unit)? = null

    override fun setLevelListener(listener: ((level: Float, durationMs: Long) -> Unit)?) {
        levels = listener
    }

    override fun start(file: File, pcmSink: OutputStream?) {
        started = true
        file.writeText("audio")
    }

    override fun stop() {
        stopped = true
    }

    override fun release() = Unit
}

private class ScriptedEngine(
    private val result: DeviceSpeechResult? = null,
    private val answers: Boolean = true,
) : DeviceSpeechRecognizer {
    override val isAvailable = true
    var listener: DeviceSpeechRecognizer.Listener? = null
    var destroyed = false

    override fun start(listener: DeviceSpeechRecognizer.Listener): OutputStream {
        this.listener = listener
        return ByteArrayOutputStream()
    }

    override fun stopListening() {
        if (!answers) return
        result?.let { listener?.onResult(it) } ?: listener?.onUnavailable("no match")
    }

    override fun destroy() {
        destroyed = true
    }
}

private class Heard : DictationSession.Listener {
    val events = mutableListOf<String>()
    var text: String? = null
    var confidence: Float? = null
    var error: DictationError? = null
    val partials = mutableListOf<String>()

    override fun onListening() { events += "listening" }
    override fun onSpeechStarted() { events += "started" }
    override fun onSpeechEnded() { events += "ended" }
    override fun onLevel(level: Float) = Unit
    override fun onPartial(text: String) { partials += text }
    override fun onResult(text: String, confidence: Float?) {
        this.text = text
        this.confidence = confidence
    }
    override fun onError(error: DictationError) { this.error = error }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DictationSessionTest {

    private lateinit var apiService: MaggieApiService
    private lateinit var scope: TestScope
    private lateinit var recorder: LevelRecorder
    private lateinit var cacheDir: File
    private val heard = Heard()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        cacheDir = Files.createTempDirectory("dictation-test").toFile()
        apiService = mockk(relaxed = true)
        coEvery { apiService.transcribe(any(), any()) } returns "bonjour de Whisper"
        scope = TestScope()
        recorder = LevelRecorder()
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun session(engine: DeviceSpeechRecognizer) = DictationSession(
        cacheDir = cacheDir,
        apiService = apiService,
        recorder = recorder,
        engine = engine,
        detector = EndOfSpeechDetector(silenceMs = 1_000, noSpeechMs = 3_000, maxMs = 30_000),
        scope = scope,
        listener = heard,
    )

    private fun feed(level: Float, ms: Long) {
        for (i in 1..ms / 100) recorder.levels?.invoke(level, 100L)
        scope.runCurrent()
    }

    private fun speak(ms: Long) = feed(0.2f, ms)

    private fun pause(ms: Long) = feed(0.0f, ms)

    private fun settle() {
        scope.advanceTimeBy(DictationSession.DEVICE_RESULT_TIMEOUT_MS + 1)
        scope.runCurrent()
    }

    @Test
    fun `starting opens the microphone and says so`() {
        session(NoDeviceSpeech).start()

        assertTrue(recorder.started)
        assertEquals(listOf("listening"), heard.events)
    }

    @Test
    fun `a good result from the phone is delivered untouched, with no call to the server`() {
        val engine = ScriptedEngine(DeviceSpeechResult("ben je rentre demain", 0.9f))
        session(engine).start()

        speak(1_000)
        pause(1_000)
        settle()

        assertEquals(listOf("listening", "started", "ended"), heard.events)
        assertEquals("ben je rentre demain", heard.text)
        assertEquals(0.9f, heard.confidence!!, 0f)
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }
        assertTrue(engine.destroyed)
        assertTrue(recorder.stopped)
    }

    @Test
    fun `a phone result with a low confidence goes to Whisper, which may clean it`() {
        val engine = ScriptedEngine(DeviceSpeechResult("bonjour", 0.2f))
        session(engine).start()

        speak(1_000)
        pause(1_000)
        settle()

        assertEquals("bonjour de Whisper", heard.text)
        assertNull(heard.confidence)
        coVerify(exactly = 1) { apiService.transcribe(any(), TranscriptCleanup.AUTO) }
    }

    @Test
    fun `no phone engine at all is Whisper alone`() {
        session(NoDeviceSpeech).start()

        speak(1_000)
        pause(1_000)
        settle()

        assertEquals("bonjour de Whisper", heard.text)
    }

    @Test
    fun `an engine that never answers is waited for, then Whisper takes over`() {
        session(ScriptedEngine(answers = false)).start()

        speak(1_000)
        pause(1_000)
        scope.runCurrent()
        assertNull(heard.text)
        settle()

        assertEquals("bonjour de Whisper", heard.text)
    }

    @Test
    fun `Whisper failing falls back on a middling phone text instead of an error`() {
        coEvery { apiService.transcribe(any(), any()) } throws IllegalStateException("down")
        session(ScriptedEngine(DeviceSpeechResult("bonjour", 0.2f))).start()

        speak(1_000)
        pause(1_000)
        settle()

        assertEquals("bonjour", heard.text)
        assertNull(heard.error)
    }

    @Test
    fun `Whisper failing with nothing from the phone is a network error`() {
        coEvery { apiService.transcribe(any(), any()) } throws IllegalStateException("down")
        session(NoDeviceSpeech).start()

        speak(1_000)
        pause(1_000)
        settle()

        assertEquals(DictationError.NETWORK, heard.error)
        assertNull(heard.text)
    }

    @Test
    fun `Whisper hearing nothing is no match`() {
        coEvery { apiService.transcribe(any(), any()) } returns "  "
        session(NoDeviceSpeech).start()

        speak(1_000)
        pause(1_000)
        settle()

        assertEquals(DictationError.NO_MATCH, heard.error)
    }

    @Test
    fun `silence from the start times out without a call to the server`() {
        session(NoDeviceSpeech).start()

        pause(3_500)
        settle()

        assertEquals(DictationError.NO_SPEECH, heard.error)
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }
        assertTrue(recorder.stopped)
    }

    @Test
    fun `the app stopping the dictation before anything was said is no speech too`() {
        val dictation = session(NoDeviceSpeech)
        dictation.start()
        pause(500)

        dictation.stop()
        settle()

        assertEquals(DictationError.NO_SPEECH, heard.error)
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }
    }

    @Test
    fun `the app stopping the dictation mid-sentence transcribes what was heard`() {
        val dictation = session(ScriptedEngine(DeviceSpeechResult("je rentre", 0.9f)))
        dictation.start()
        speak(500)

        dictation.stop()
        settle()

        assertEquals("je rentre", heard.text)
    }

    @Test
    fun `what the phone engine hears while the person talks is passed on`() {
        val engine = ScriptedEngine(DeviceSpeechResult("je rentre", 0.9f))
        session(engine).start()

        engine.listener!!.onPartial("je")

        assertEquals(listOf("je"), heard.partials)
    }

    @Test
    fun `cancelling reports nothing and ignores a late answer`() {
        val engine = ScriptedEngine(DeviceSpeechResult("je rentre", 0.9f))
        val dictation = session(engine)
        dictation.start()
        speak(500)

        dictation.cancel()
        engine.listener!!.onResult(DeviceSpeechResult("trop tard", 0.9f))
        speak(2_000)
        settle()

        assertNull(heard.text)
        assertNull(heard.error)
        assertTrue(recorder.stopped)
        assertEquals(0, cacheDir.listFiles()!!.size)
    }

    @Test
    fun `a recorder that cannot open the microphone is an audio error`() {
        val broken = object : AudioRecorder {
            override fun start(file: File, pcmSink: OutputStream?) = throw IllegalStateException("busy")
            override fun stop() = Unit
            override fun release() = Unit
        }
        DictationSession(cacheDir, apiService, broken, NoDeviceSpeech, EndOfSpeechDetector(), scope, heard).start()

        assertEquals(DictationError.AUDIO, heard.error)
        assertFalse(heard.events.contains("listening"))
    }
}
