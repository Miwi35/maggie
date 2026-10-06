package com.maggie.app.voice

import android.content.Context
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TranscriptCleanup
import com.maggie.app.data.repository.UserPreferenceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

private class FakeRecorder : AudioRecorder {
    var started = false
    var stopped = false
    var released = false
    var pcmSink: OutputStream? = null

    override fun start(file: File, pcmSink: OutputStream?) {
        started = true
        this.pcmSink = pcmSink
        file.writeText("audio")
    }

    override fun stop() {
        stopped = true
        // The recorder owns the sink it was handed and closes it here — that close is
        // what tells the engine the sentence is over (MAG-222).
        pcmSink?.close()
    }

    override fun release() {
        released = true
    }
}

/** The engine's end of the audio pipe, which remembers being closed. */
private class ClosingSink : ByteArrayOutputStream() {
    var closed = false

    override fun close() {
        closed = true
        super.close()
    }
}

/**
 * The phone's engine, scripted. [resultOnStop] is what it hands back when the button
 * comes up — null for « nothing usable », and [answers] false for an engine that never
 * answers at all, which is what the timeout is for.
 */
private class FakeDeviceSpeech(
    override val isAvailable: Boolean = true,
    private val resultOnStop: DeviceSpeechResult? = null,
    private val answers: Boolean = true,
) : DeviceSpeechRecognizer {
    val sink = ClosingSink()
    var listener: DeviceSpeechRecognizer.Listener? = null
    var startCount = 0
    var stopped = false
    var destroyed = false

    /** Whether the audio pipe was closed — the end-of-sentence signal — before we were asked to stop. */
    var heardTheEndFirst = false
        private set

    override fun start(listener: DeviceSpeechRecognizer.Listener): OutputStream {
        this.listener = listener
        startCount += 1
        return sink
    }

    override fun stopListening() {
        heardTheEndFirst = sink.closed
        stopped = true
        if (!answers) return
        resultOnStop?.let { listener?.onResult(it) } ?: listener?.onUnavailable("no match")
    }

    override fun destroy() {
        destroyed = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)

class VoiceManagerTest {

    private lateinit var context: Context
    private lateinit var apiService: MaggieApiService
    private lateinit var userPreferenceRepository: UserPreferenceRepository
    private lateinit var voiceManager: VoiceManager
    private lateinit var testScope: TestScope
    private lateinit var recorder: FakeRecorder
    private lateinit var cacheDir: File
    private var now = 0L

    @Before
    fun setup() {
        cacheDir = Files.createTempDirectory("voice-test").toFile()
        context = mockk(relaxed = true)
        every { context.cacheDir } returns cacheDir
        apiService = mockk(relaxed = true)
        coEvery { apiService.transcribe(any(), any()) } returns "bonjour Maggie"
        userPreferenceRepository = mockk(relaxed = true)
        testScope = TestScope()
        recorder = FakeRecorder()
        now = 0L
        voiceManager = managerWith(NoDeviceSpeech)
    }

    /** A manager whose phone recognition is [engine] — none of it, by default. */
    private fun managerWith(engine: DeviceSpeechRecognizer) = VoiceManager(
        context,
        apiService,
        userPreferenceRepository,
        recorderFactory = { recorder },
        deviceSpeechFactory = { engine },
        clock = { now },
        scope = testScope,
    )

    private fun advance(ms: Long) {
        now += ms
        testScope.advanceTimeBy(ms)
        testScope.runCurrent()
    }

    @Test
    fun `press down starts recording in hold mode`() {
        voiceManager.pressDown {}

        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
        assertTrue(recorder.started)
        assertFalse(voiceManager.handsFree.value)
    }

    @Test
    fun `release after a real hold transcribes and sends the text`() {
        var sent: String? = null
        voiceManager.pressDown { sent = it }
        advance(600)
        voiceManager.pressRelease()
        assertEquals(VoiceState.TRANSCRIBING, voiceManager.state.value)
        testScope.runCurrent()

        assertEquals("bonjour Maggie", sent)
        assertEquals(VoiceState.PROCESSING, voiceManager.state.value)
        assertTrue(recorder.stopped)
    }

    @Test
    fun `the result goes to whoever started the listening, however many came before`() {
        var chat: String? = null
        var overlay: String? = null

        voiceManager.startListening { overlay = it }
        voiceManager.cancelListening()
        voiceManager.pressDown { chat = it }
        advance(600)
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertEquals("bonjour Maggie", chat)
        assertNull(overlay)
    }

    @Test
    fun `a hands-free listening still delivers to its own requester after a button press`() {
        var overlay: String? = null
        var chat: String? = null

        voiceManager.startListening { overlay = it }
        advance(2000)
        voiceManager.pressDown { chat = it }
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertEquals("bonjour Maggie", overlay)
        assertNull(chat)
    }

    @Test
    fun `release after a short press sends nothing and shows the hint`() {
        var sent: String? = null
        voiceManager.pressDown { sent = it }
        advance(100)
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertEquals(VoiceState.IDLE, voiceManager.state.value)
        assertNull(sent)
        assertTrue(voiceManager.holdHint.value)
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }
        assertTrue(recorder.released)
    }

    @Test
    fun `hold hint goes away after a moment`() {
        voiceManager.pressDown {}
        advance(100)
        voiceManager.pressRelease()
        assertTrue(voiceManager.holdHint.value)

        advance(3000)

        assertFalse(voiceManager.holdHint.value)
    }

    @Test
    fun `sliding out cancels the recording and sends nothing`() {
        var sent: String? = null
        voiceManager.pressDown { sent = it }
        advance(800)
        voiceManager.pressCancel()
        testScope.runCurrent()

        assertEquals(VoiceState.IDLE, voiceManager.state.value)
        assertNull(sent)
        assertFalse(voiceManager.holdHint.value)
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }
        assertEquals(0, cacheDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `a hold under one second is still sent`() {
        voiceManager.pressDown {}
        advance(500)
        voiceManager.pressRelease()
        testScope.runCurrent()

        coVerify(exactly = 1) { apiService.transcribe(any(), any()) }
    }

    @Test
    fun `listening opened hands-free is ended by a tap on the button`() {
        voiceManager.startListening {}
        assertTrue(voiceManager.handsFree.value)
        advance(2000)

        voiceManager.pressDown {}
        voiceManager.pressRelease()
        testScope.runCurrent()

        coVerify(exactly = 1) { apiService.transcribe(any(), any()) }
    }

    @Test
    fun `sliding out does not cancel a hands-free listening`() {
        voiceManager.startListening {}
        advance(2000)

        voiceManager.pressDown {}
        voiceManager.pressCancel()

        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
    }

    @Test
    fun `press down is ignored while a request is being processed`() {
        voiceManager.pressDown {}
        advance(600)
        voiceManager.pressRelease()
        testScope.runCurrent()
        assertEquals(VoiceState.PROCESSING, voiceManager.state.value)

        voiceManager.pressDown {}

        assertEquals(VoiceState.PROCESSING, voiceManager.state.value)
    }

    // --- The phone first, Whisper in reserve (MAG-222) ---

    @Test
    fun `a sentence the phone heard well never reaches Whisper`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("ajoute des tomates", 0.9f))
        voiceManager = managerWith(engine)
        var sent: String? = null

        voiceManager.pressDown { sent = it }
        advance(2000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertEquals("ajoute des tomates", sent)
        assertEquals(VoiceState.PROCESSING, voiceManager.state.value)
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }
        assertTrue(engine.stopped)
        assertTrue(engine.destroyed)
    }

    @Test
    fun `the microphone is fed to the engine while the button is held`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("oui", 0.9f))
        voiceManager = managerWith(engine)

        voiceManager.pressDown {}

        assertEquals(1, engine.startCount)
        assertEquals(engine.sink, recorder.pcmSink)
    }

    @Test
    fun `the engine is told the sentence is over before it is asked to answer`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("oui", 0.9f))
        voiceManager = managerWith(engine)

        voiceManager.pressDown {}
        advance(2000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        // The pipe closing is the engine's end-of-sentence; stopping it before that
        // would make it answer on half a hold.
        assertTrue(engine.heardTheEndFirst)
    }

    @Test
    fun `the fillers are dropped from what the phone heard`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("euh ajoute des tomates", 0.9f))
        voiceManager = managerWith(engine)
        var sent: String? = null

        voiceManager.pressDown { sent = it }
        advance(2000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertEquals("ajoute des tomates", sent)
    }

    @Test
    fun `a result the judge refuses goes to Whisper on the audio already recorded`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("ajoute", 0.2f))
        voiceManager = managerWith(engine)
        var sent: String? = null

        voiceManager.pressDown { sent = it }
        advance(2000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        // Nothing was asked of the owner: the same hold produced the clip Whisper read.
        coVerify(exactly = 1) { apiService.transcribe(any(), TranscriptCleanup.NONE) }
        assertEquals("bonjour Maggie", sent)
    }

    @Test
    fun `an engine that heard nothing goes to Whisper`() {
        val engine = FakeDeviceSpeech(resultOnStop = null)
        voiceManager = managerWith(engine)

        voiceManager.pressDown {}
        advance(2000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        coVerify(exactly = 1) { apiService.transcribe(any(), any()) }
    }

    @Test
    fun `an engine that never answers stops holding up the sentence`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("ajoute", 0.9f), answers = false)
        voiceManager = managerWith(engine)

        voiceManager.pressDown {}
        advance(2000)
        voiceManager.pressRelease()
        testScope.runCurrent()
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }

        advance(VoiceManager.DEVICE_RESULT_TIMEOUT_MS)

        coVerify(exactly = 1) { apiService.transcribe(any(), any()) }
    }

    @Test
    fun `an engine that closes the sentence on a pause while the button is held never ends it`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("ajoute du lait à la liste de courses", 0.9f))
        voiceManager = managerWith(engine)
        var sent: String? = null

        voiceManager.pressDown { sent = it }
        advance(2000)
        // Three seconds of silence in the middle of the sentence: the engine answers
        // on its own with the first half, the button is still down.
        engine.listener?.onResult(DeviceSpeechResult("ajoute du lait à la liste de courses", 0.9f))
        advance(3000)
        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
        advance(2000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        // Only the release ends the sentence: the whole recording is read, not the
        // first segment the engine closed early.
        coVerify(exactly = 1) { apiService.transcribe(any(), TranscriptCleanup.NONE) }
        assertEquals("bonjour Maggie", sent)
    }

    @Test
    fun `an engine that gives up on a pause while the button is held leaves the rest to Whisper`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("ajoute du lait", 0.9f))
        voiceManager = managerWith(engine)
        var sent: String? = null

        voiceManager.pressDown { sent = it }
        advance(1000)
        engine.listener?.onUnavailable("error 7")
        advance(5000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        coVerify(exactly = 1) { apiService.transcribe(any(), any()) }
        assertEquals("bonjour Maggie", sent)
    }

    @Test
    fun `a phone with no recognition records for Whisper alone`() {
        val engine = FakeDeviceSpeech(isAvailable = false)
        voiceManager = managerWith(engine)

        voiceManager.pressDown {}
        advance(2000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertEquals(0, engine.startCount)
        assertNull(recorder.pcmSink)
        coVerify(exactly = 1) { apiService.transcribe(any(), any()) }
    }

    @Test
    fun `what the engine hears shows up live and is cleared afterwards`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("ajoute des tomates", 0.9f))
        voiceManager = managerWith(engine)

        voiceManager.pressDown {}
        assertEquals("", voiceManager.partialText.value)

        engine.listener?.onPartial("ajoute des")
        assertEquals("ajoute des", voiceManager.partialText.value)

        advance(2000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertEquals("", voiceManager.partialText.value)
    }

    @Test
    fun `sliding out releases the engine`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("ajoute", 0.9f))
        voiceManager = managerWith(engine)

        voiceManager.pressDown {}
        advance(800)
        voiceManager.pressCancel()
        testScope.runCurrent()

        assertTrue(engine.destroyed)
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }
    }

    @Test
    fun `initial state is IDLE`() {
        assertEquals(VoiceState.IDLE, voiceManager.state.value)
    }

    @Test
    fun `initial duration is zero`() {
        assertEquals(0, voiceManager.duration.value)
    }

    @Test
    fun `cancelListening from IDLE stays IDLE`() {
        voiceManager.cancelListening()
        assertEquals(VoiceState.IDLE, voiceManager.state.value)
    }

    @Test
    fun `stopSpeaking from IDLE stays IDLE`() {
        voiceManager.stopSpeaking()
        assertEquals(VoiceState.IDLE, voiceManager.state.value)
    }

    @Test
    fun `destroy resets state to IDLE`() {
        voiceManager.destroy()
        assertEquals(VoiceState.IDLE, voiceManager.state.value)
    }
}
