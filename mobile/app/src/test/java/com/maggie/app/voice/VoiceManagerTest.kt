package com.maggie.app.voice

import android.content.Context
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TranscriptCleanup
import com.maggie.app.data.repository.UserPreferenceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

private class FakeSpeechPlayer : SpeechPlayer {
    var played = false
    var stopped = false
    var released = false
    override var durationMs = 10_000
    override var positionMs = 0
    private var onFinished: (() -> Unit)? = null

    override fun play(file: File, onFinished: () -> Unit) {
        played = true
        this.onFinished = onFinished
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {
        released = true
    }

    fun finish() = onFinished?.invoke()
}

@OptIn(ExperimentalCoroutinesApi::class)

class VoiceManagerTest {

    private lateinit var context: Context
    private lateinit var apiService: MaggieApiService
    private lateinit var userPreferenceRepository: UserPreferenceRepository
    private lateinit var voiceManager: VoiceManager
    private lateinit var testScope: TestScope
    private lateinit var recorder: FakeRecorder
    private lateinit var players: MutableList<FakeSpeechPlayer>
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
        players = mutableListOf()
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
        ioDispatcher = StandardTestDispatcher(testScope.testScheduler),
        playerFactory = { FakeSpeechPlayer().also { players.add(it) } },
    )

    private fun advance(ms: Long) {
        now += ms
        testScope.advanceTimeBy(ms)
        testScope.runCurrent()
    }

    @Test
    fun `press down starts recording in hold mode`() {
        voiceManager.pressDown()

        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
        assertTrue(recorder.started)
        assertFalse(voiceManager.handsFree.value)
    }

    @Test
    fun `release after a real hold transcribes and sends the text`() {
        var sent: String? = null
        voiceManager.onFinalResult = { sent = it }

        voiceManager.pressDown()
        advance(600)
        voiceManager.pressRelease()
        assertEquals(VoiceState.TRANSCRIBING, voiceManager.state.value)
        testScope.runCurrent()

        assertEquals("bonjour Maggie", sent)
        assertEquals(VoiceState.PROCESSING, voiceManager.state.value)
        assertTrue(recorder.stopped)
    }

    @Test
    fun `release after a short press sends nothing and shows the hint`() {
        var sent: String? = null
        voiceManager.onFinalResult = { sent = it }

        voiceManager.pressDown()
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
        voiceManager.pressDown()
        advance(100)
        voiceManager.pressRelease()
        assertTrue(voiceManager.holdHint.value)

        advance(3000)

        assertFalse(voiceManager.holdHint.value)
    }

    @Test
    fun `sliding out cancels the recording and sends nothing`() {
        var sent: String? = null
        voiceManager.onFinalResult = { sent = it }

        voiceManager.pressDown()
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
        voiceManager.pressDown()
        advance(500)
        voiceManager.pressRelease()
        testScope.runCurrent()

        coVerify(exactly = 1) { apiService.transcribe(any(), any()) }
    }

    @Test
    fun `listening opened hands-free is ended by a tap on the button`() {
        voiceManager.startListening()
        assertTrue(voiceManager.handsFree.value)
        advance(2000)

        voiceManager.pressDown()
        voiceManager.pressRelease()
        testScope.runCurrent()

        coVerify(exactly = 1) { apiService.transcribe(any(), any()) }
    }

    @Test
    fun `sliding out does not cancel a hands-free listening`() {
        voiceManager.startListening()
        advance(2000)

        voiceManager.pressDown()
        voiceManager.pressCancel()

        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
    }

    private fun reachProcessing() {
        voiceManager.pressDown()
        advance(600)
        voiceManager.pressRelease()
        testScope.runCurrent()
        assertEquals(VoiceState.PROCESSING, voiceManager.state.value)
    }

    private fun speakWhenSynthesized(text: String): CompletableDeferred<ByteArray> {
        val synthesized = CompletableDeferred<ByteArray>()
        coEvery { apiService.synthesizeSpeech(any(), any()) } coAnswers { synthesized.await() }
        voiceManager.speak(text)
        testScope.runCurrent()
        return synthesized
    }

    private fun speakNow(text: String): FakeSpeechPlayer {
        speakWhenSynthesized(text).complete(ByteArray(4))
        testScope.runCurrent()
        return players.single()
    }

    @Test
    fun `press down while a request is being prepared interrupts it and starts listening`() {
        reachProcessing()
        val interruptions = mutableListOf<String?>()
        voiceManager.onInterrupt = { interruptions.add(it) }

        voiceManager.pressDown()

        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
        assertEquals(listOf<String?>(null), interruptions)
        assertFalse(voiceManager.handsFree.value)
    }

    @Test
    fun `press down while the voice is being synthesized says nothing was heard`() {
        val interruptions = mutableListOf<String?>()
        voiceManager.onInterrupt = { interruptions.add(it) }
        speakWhenSynthesized("Il était une fois un roi.")
        assertEquals(VoiceState.SPEAKING, voiceManager.state.value)

        voiceManager.pressDown()

        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
        assertEquals(listOf<String?>(""), interruptions)
    }

    @Test
    fun `a synthesis cancelled by the user never plays afterwards`() {
        val synthesized = speakWhenSynthesized("Il était une fois un roi.")

        voiceManager.pressDown()
        synthesized.complete(ByteArray(4))
        testScope.runCurrent()

        assertTrue(players.none { it.played })
        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
        assertEquals(0, cacheDir.listFiles { f -> f.name.startsWith("tts_") }?.size ?: 0)
    }

    @Test
    fun `a synthesis that fails after being cancelled does not reset the new listening`() {
        coEvery { apiService.synthesizeSpeech(any(), any()) } coAnswers { error("network down") }
        voiceManager.speak("Il était une fois un roi.")
        voiceManager.pressDown()
        testScope.runCurrent()

        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
    }

    @Test
    fun `press down during playback stops it and reports what was heard`() {
        val interruptions = mutableListOf<String?>()
        voiceManager.onInterrupt = { interruptions.add(it) }
        val player = speakNow("Il était une fois un roi qui avait trois fils.")
        player.positionMs = 5_500

        voiceManager.pressDown()

        assertTrue(player.stopped)
        assertTrue(player.released)
        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
        assertEquals(listOf<String?>("Il était une fois un roi"), interruptions)
    }

    @Test
    fun `playback that ends by itself is not an interruption`() {
        val interruptions = mutableListOf<String?>()
        voiceManager.onInterrupt = { interruptions.add(it) }
        val player = speakNow("Bonjour.")

        player.finish()
        voiceManager.pressDown()

        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
        assertTrue(interruptions.isEmpty())
    }

    @Test
    fun `starting a hands-free listening while speaking interrupts the voice`() {
        val interruptions = mutableListOf<String?>()
        voiceManager.onInterrupt = { interruptions.add(it) }
        val player = speakNow("Il était une fois un roi qui avait trois fils.")
        player.positionMs = 5_500

        voiceManager.startListening()

        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
        assertTrue(voiceManager.handsFree.value)
        assertEquals(listOf<String?>("Il était une fois un roi"), interruptions)
    }

    @Test
    fun `interrupt reports nothing when Maggie is not busy`() {
        val interruptions = mutableListOf<String?>()
        voiceManager.onInterrupt = { interruptions.add(it) }

        assertFalse(voiceManager.interrupt())
        voiceManager.pressDown()
        assertFalse(voiceManager.interrupt())

        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
        assertTrue(interruptions.isEmpty())
    }

    @Test
    fun `a second interrupt is ignored once the first has taken effect`() {
        val interruptions = mutableListOf<String?>()
        voiceManager.onInterrupt = { interruptions.add(it) }
        speakNow("Il était une fois un roi.")

        assertTrue(voiceManager.interrupt())
        assertFalse(voiceManager.interrupt())

        assertEquals(1, interruptions.size)
        assertEquals(VoiceState.IDLE, voiceManager.state.value)
    }

    @Test
    fun `heard text is cut at the last whole word`() {
        val text = "Il était une fois un roi"

        assertEquals("", heardPart(text, 0f))
        assertEquals("Il", heardPart(text, 0.1f))
        assertEquals("Il était une", heardPart(text, 0.5f))
        assertEquals("Il était une fois", heardPart(text, 0.74f))
        assertEquals(text, heardPart(text, 1f))
        assertEquals(text, heardPart(text, 1.4f))
        assertEquals("", heardPart(text, -1f))
    }

    // --- The phone first, Whisper in reserve (MAG-222) ---

    @Test
    fun `a sentence the phone heard well never reaches Whisper`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("ajoute des tomates", 0.9f))
        voiceManager = managerWith(engine)
        var sent: String? = null
        voiceManager.onFinalResult = { sent = it }

        voiceManager.pressDown()
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

        voiceManager.pressDown()

        assertEquals(1, engine.startCount)
        assertEquals(engine.sink, recorder.pcmSink)
    }

    @Test
    fun `the engine is told the sentence is over before it is asked to answer`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("oui", 0.9f))
        voiceManager = managerWith(engine)

        voiceManager.pressDown()
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
        voiceManager.onFinalResult = { sent = it }

        voiceManager.pressDown()
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
        voiceManager.onFinalResult = { sent = it }

        voiceManager.pressDown()
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

        voiceManager.pressDown()
        advance(2000)
        voiceManager.pressRelease()
        testScope.runCurrent()

        coVerify(exactly = 1) { apiService.transcribe(any(), any()) }
    }

    @Test
    fun `an engine that never answers stops holding up the sentence`() {
        val engine = FakeDeviceSpeech(resultOnStop = DeviceSpeechResult("ajoute", 0.9f), answers = false)
        voiceManager = managerWith(engine)

        voiceManager.pressDown()
        advance(2000)
        voiceManager.pressRelease()
        testScope.runCurrent()
        coVerify(exactly = 0) { apiService.transcribe(any(), any()) }

        advance(VoiceManager.DEVICE_RESULT_TIMEOUT_MS)

        coVerify(exactly = 1) { apiService.transcribe(any(), any()) }
    }

    @Test
    fun `a phone with no recognition records for Whisper alone`() {
        val engine = FakeDeviceSpeech(isAvailable = false)
        voiceManager = managerWith(engine)

        voiceManager.pressDown()
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

        voiceManager.pressDown()
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

        voiceManager.pressDown()
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
    fun `onFinalResult callback is null by default`() {
        assertNull(voiceManager.onFinalResult)
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

    @Test
    fun `onFinalResult callback can be set and cleared`() {
        var captured: String? = null
        voiceManager.onFinalResult = { captured = it }
        voiceManager.onFinalResult?.invoke("test")
        assertEquals("test", captured)

        voiceManager.onFinalResult = null
        assertNull(voiceManager.onFinalResult)
    }
}
