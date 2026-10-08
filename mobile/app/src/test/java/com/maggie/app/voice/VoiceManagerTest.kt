package com.maggie.app.voice

import android.content.Context
import android.util.Log
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TranscriptCleanup
import com.maggie.app.data.repository.UserPreferenceRepository
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

private class FakeRecorder : AudioRecorder {
    var started = false
    var stopped = false
    var released = false
    var pcmSink: OutputStream? = null
    override var engineGaps = 0

    /**
     * How loud the hold was, replayed to whoever asks for levels when [start] is
     * called. Null — the default — is a recorder that cannot measure, like the e2e
     * flavor's placeholder.
     */
    var levels: List<Float>? = null
    private var levelListener: ((Float, Long) -> Unit)? = null

    override fun setLevelListener(listener: ((level: Float, durationMs: Long) -> Unit)?) {
        levelListener = listener
    }

    override fun start(file: File, pcmSink: OutputStream?) {
        started = true
        this.pcmSink = pcmSink
        file.writeText("audio")
        levels?.forEach { levelListener?.invoke(it, 64L) }
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
        resultOnStop?.let { listener?.onResult(it) } ?: listener?.onUnavailable("no match", false)
    }

    override fun destroy() {
        destroyed = true
    }

    /** The engine closing its sentence on a silence, with the button still held. */
    fun closesEarlyWith(text: String, confidence: Float? = 0.9f) =
        listener?.onResult(DeviceSpeechResult(text, confidence))
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
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>(), any()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        cacheDir = Files.createTempDirectory("voice-test").toFile()
        context = mockk(relaxed = true)
        every { context.cacheDir } returns cacheDir
        apiService = mockk(relaxed = true)
        coEvery { apiService.transcribe(any(), any()) } returns "bonjour Maggie"
        userPreferenceRepository = mockk(relaxed = true)
        testScope = TestScope()
        recorder = FakeRecorder()
        // Loud enough to be a voice, long enough to be a sentence: the presence gate
        // (MAG-222) is exercised by its own tests below, not by every one of these.
        recorder.levels = List(16) { 0.12f }
        now = 0L
        voiceManager = managerWith(NoDeviceSpeech)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
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
    fun `a reply arriving while the button is held does not cut the listening`() {
        var sent: String? = null
        voiceManager.pressDown { sent = it }
        advance(600)

        voiceManager.speak("Voici votre liste")
        testScope.runCurrent()

        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
        voiceManager.pressRelease()
        testScope.runCurrent()
        assertEquals("bonjour Maggie", sent)
    }

    @Test
    fun `an answer handled on the spot frees the microphone`() {
        voiceManager.pressDown { voiceManager.answerHandled() }
        advance(600)
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertEquals(VoiceState.IDLE, voiceManager.state.value)
    }

    @Test
    fun `handling an answer outside a delivery changes nothing`() {
        voiceManager.answerHandled()
        assertEquals(VoiceState.IDLE, voiceManager.state.value)

        voiceManager.pressDown {}
        voiceManager.answerHandled()
        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
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
        assertEquals(VoiceHint.HOLD_LONGER, voiceManager.hint.value)
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }
        assertTrue(recorder.released)
    }

    @Test
    fun `hold hint goes away after a moment`() {
        voiceManager.pressDown {}
        advance(100)
        voiceManager.pressRelease()
        assertEquals(VoiceHint.HOLD_LONGER, voiceManager.hint.value)

        advance(3000)

        assertNull(voiceManager.hint.value)
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
        assertNull(voiceManager.hint.value)
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
    fun `a hands-free listening is not ended by the engine's own pause either`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("ajoute du lait à la liste de courses", 0.9f))
        voiceManager = managerWith(engine)
        var sent: String? = null

        voiceManager.startListening { sent = it }
        advance(2000)
        engine.listener?.onResult(DeviceSpeechResult("ajoute du lait à la liste de courses", 0.9f))
        advance(5000)
        voiceManager.stopAndTranscribe()
        testScope.runCurrent()

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
        engine.listener?.onUnavailable("error 7", fatal = false)
        advance(5000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        coVerify(exactly = 1) { apiService.transcribe(any(), any()) }
        assertEquals("bonjour Maggie", sent)
    }

    @Test
    fun `an engine too slow for fast speech has a gap in what it heard so Whisper reads the whole clip`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("ajoute du lait à la liste", 0.9f))
        voiceManager = managerWith(engine)
        var sent: String? = null

        voiceManager.pressDown { sent = it }
        advance(6000)
        // The engine could not keep up: buffers were dropped for it, not for the clip.
        recorder.engineGaps = 4
        voiceManager.pressRelease()
        testScope.runCurrent()

        // Its confident, long-enough answer covers only part of the sentence.
        coVerify(exactly = 1) { apiService.transcribe(any(), TranscriptCleanup.NONE) }
        assertEquals("bonjour Maggie", sent)
        assertTrue(engine.destroyed)
    }

    @Test
    fun `a gap in what the engine heard does not wait for its answer`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("ajoute", 0.9f), answers = false)
        voiceManager = managerWith(engine)

        voiceManager.pressDown {}
        advance(6000)
        recorder.engineGaps = 1
        voiceManager.pressRelease()
        testScope.runCurrent()

        coVerify(exactly = 1) { apiService.transcribe(any(), any()) }
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

    // --- Nothing said, nothing sent (retour de recette MAG-222) ---

    @Test
    fun `a silent hold sends nothing and says so`() {
        // The refused bug: « Thank you for watching » appeared in the chat. Whisper
        // never gets this clip, so it never gets the chance to invent over it.
        recorder.levels = List(48) { 0.0008f }
        var sent: String? = null

        voiceManager.pressDown { sent = it }
        advance(3_000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertNull(sent)
        assertEquals(VoiceState.IDLE, voiceManager.state.value)
        assertEquals(VoiceHint.NOTHING_HEARD, voiceManager.hint.value)
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }
        assertEquals("the clip must not be left behind", 0, cacheDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `a hold of plain noise sends nothing`() {
        recorder.levels = List(60) { 0.009f }
        var sent: String? = null

        voiceManager.pressDown { sent = it }
        advance(4_000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertNull(sent)
        assertEquals(VoiceHint.NOTHING_HEARD, voiceManager.hint.value)
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }
    }

    @Test
    fun `a silent hold the engine heard nothing in releases it and sends nothing`() {
        // The engine heard no more than the microphone did, so nothing outranks the
        // loudness gate: the clip goes nowhere rather than to Whisper, which answers
        // a silence with the credits it was trained on.
        recorder.levels = List(48) { 0.0008f }
        val engine = FakeDeviceSpeech(resultOnStop = null)
        voiceManager = managerWith(engine)
        var sent: String? = null

        voiceManager.pressDown { sent = it }
        advance(3_000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertNull(sent)
        assertEquals(VoiceHint.NOTHING_HEARD, voiceManager.hint.value)
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }
        assertTrue(engine.destroyed)
    }

    @Test
    fun `an unrated text from a hold with no voice in it is not trusted`() {
        // Most engines report no confidence at all, and a silent hold has no voiced
        // span either — so the quality judge has nothing left to compare and would take
        // any text as good. A sentence on a clip of silence is the thing being
        // refused, so it takes a rating to outrank the meter.
        recorder.levels = List(48) { 0.0008f }
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("Thank you for watching", null))
        voiceManager = managerWith(engine)
        var sent: String? = null

        voiceManager.pressDown { sent = it }
        advance(3_000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertNull(sent)
        assertEquals(VoiceHint.NOTHING_HEARD, voiceManager.hint.value)
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }
    }

    @Test
    fun `an unrated text is trusted as soon as a voice was heard`() {
        // The common case on a phone that reports no confidence: the meter heard the
        // voice, so the engine's word is all the chain needs.
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("ajoute des tomates", null))
        voiceManager = managerWith(engine)
        var sent: String? = null

        voiceManager.pressDown { sent = it }
        advance(2_000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertEquals("ajoute des tomates", sent)
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }
    }

    @Test
    fun `a sentence the phone transcribed survives a hold too quiet to measure`() {
        // Recette step 6: he whispers, close to the phone. VOICE_RECOGNITION applies
        // no automatic gain, so every buffer sits under the loudness gate — while the
        // engine had the sentence all along. A result it is confident about is proof
        // someone spoke, and it outranks an estimate made from loudness alone; the
        // gate guards the Whisper leg, which is where the invention came from.
        recorder.levels = List(48) { 0.004f }
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("ajoute des tomates", 0.9f))
        voiceManager = managerWith(engine)
        var sent: String? = null

        voiceManager.pressDown { sent = it }
        advance(3_000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertEquals("ajoute des tomates", sent)
        assertNull("nothing was lost, so there is nothing to apologise for", voiceManager.hint.value)
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }
        assertEquals("the clip must not be left behind", 0, cacheDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `a transcript the server refused is reported as nothing heard`() {
        // The server's own gate found no speech behind the clip and answered empty
        // (agent/app/llm/transcription.py). Nothing is sent, and the owner is told.
        coEvery { apiService.transcribe(any(), any()) } returns ""
        var sent: String? = null

        voiceManager.pressDown { sent = it }
        advance(2_000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertNull(sent)
        assertEquals(VoiceState.IDLE, voiceManager.state.value)
        assertEquals(VoiceHint.NOTHING_HEARD, voiceManager.hint.value)
    }

    @Test
    fun `a recorder that cannot measure is still allowed to send`() {
        // The e2e flavor's placeholder, and any device whose capture cannot be read:
        // refusing what was never measured would silence the voice path entirely.
        recorder.levels = null

        voiceManager.pressDown {}
        advance(2_000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        coVerify(exactly = 1) { apiService.transcribe(any(), any()) }
    }

    // --- Held means held (retour de recette MAG-222) ---

    @Test
    fun `a sentence the engine cut on a pause arrives whole`() {
        // The second refused bug: Google closes its sentence on a silence, button
        // still down, and what came after the pause was lost.
        val first = FakeDeviceSpeech()
        val second = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("et aussi du pain", 0.9f))
        val engines = ArrayDeque(listOf(first, second))
        voiceManager = managerWith(SegmentedDeviceSpeech { engines.removeFirst() })
        var sent: String? = null

        voiceManager.pressDown { sent = it }
        advance(2_000)
        first.closesEarlyWith("ajoute des tomates")
        assertNull("the hold is not over until the button comes up", sent)

        advance(3_000) // the pause, then the rest of the sentence
        voiceManager.pressRelease()
        testScope.runCurrent()

        assertEquals("ajoute des tomates et aussi du pain", sent)
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }
    }

    @Test
    fun `the audio follows the engine across a pause`() {
        val first = FakeDeviceSpeech()
        val second = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("du pain", 0.9f))
        val engines = ArrayDeque(listOf(first, second))
        voiceManager = managerWith(SegmentedDeviceSpeech { engines.removeFirst() })

        voiceManager.pressDown {}
        val sink = recorder.pcmSink!!
        sink.write("un".toByteArray())
        first.closesEarlyWith("ajoute")
        sink.write("deux".toByteArray())

        assertEquals("un", first.sink.toString())
        assertEquals("deux", second.sink.toString())
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
