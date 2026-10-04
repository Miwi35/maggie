package com.maggie.app.voice

import android.content.Context
import com.maggie.app.data.api.MaggieApiService
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
import java.io.File
import java.nio.file.Files

private class FakeRecorder : AudioRecorder {
    var started = false
    var stopped = false
    var released = false

    override fun start(file: File) {
        started = true
        file.writeText("audio")
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {
        released = true
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
        coEvery { apiService.transcribe(any()) } returns "bonjour Maggie"
        userPreferenceRepository = mockk(relaxed = true)
        testScope = TestScope()
        recorder = FakeRecorder()
        now = 0L
        voiceManager = VoiceManager(
            context,
            apiService,
            userPreferenceRepository,
            recorderFactory = { recorder },
            clock = { now },
            scope = testScope,
        )
    }

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
        coVerify(exactly = 0) { apiService.transcribe(any()) }
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
        coVerify(exactly = 0) { apiService.transcribe(any()) }
        assertEquals(0, cacheDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `a hold under one second is still sent`() {
        voiceManager.pressDown()
        advance(500)
        voiceManager.pressRelease()
        testScope.runCurrent()

        coVerify(exactly = 1) { apiService.transcribe(any()) }
    }

    @Test
    fun `listening opened hands-free is ended by a tap on the button`() {
        voiceManager.startListening()
        assertTrue(voiceManager.handsFree.value)
        advance(2000)

        voiceManager.pressDown()
        voiceManager.pressRelease()
        testScope.runCurrent()

        coVerify(exactly = 1) { apiService.transcribe(any()) }
    }

    @Test
    fun `sliding out does not cancel a hands-free listening`() {
        voiceManager.startListening()
        advance(2000)

        voiceManager.pressDown()
        voiceManager.pressCancel()

        assertEquals(VoiceState.LISTENING, voiceManager.state.value)
    }

    @Test
    fun `press down is ignored while a request is being processed`() {
        voiceManager.pressDown()
        advance(600)
        voiceManager.pressRelease()
        testScope.runCurrent()
        assertEquals(VoiceState.PROCESSING, voiceManager.state.value)

        voiceManager.pressDown()

        assertEquals(VoiceState.PROCESSING, voiceManager.state.value)
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
