package com.maggie.app.voice

import android.content.Context
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.repository.UserPreferenceRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class VoiceManagerTest {

    private lateinit var context: Context
    private lateinit var apiService: MaggieApiService
    private lateinit var userPreferenceRepository: UserPreferenceRepository
    private lateinit var voiceManager: VoiceManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        apiService = mockk(relaxed = true)
        userPreferenceRepository = mockk(relaxed = true)
        voiceManager = VoiceManager(context, apiService, userPreferenceRepository)
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
