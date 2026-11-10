package com.maggie.app.media

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestAudioFocusTest {

    private class FakeGateway(var grant: Boolean = true) : AudioFocusGateway {
        var requests = mutableListOf<Int>()
        var abandons = 0
        var listener: ((Int) -> Unit)? = null

        override fun request(gain: Int, onChange: (Int) -> Unit): Boolean {
            requests += gain
            listener = onChange
            return grant
        }

        override fun abandon() {
            abandons++
        }
    }

    private val gateway = FakeGateway()
    private var lost = 0
    private val focus = DigestAudioFocus(gateway) { lost++ }

    @Test
    fun `playing asks for the transient focus that lets the music duck`() {
        assertTrue(focus.request())

        assertEquals(listOf(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK), gateway.requests)
    }

    @Test
    fun `a refused focus is not held`() {
        gateway.grant = false

        assertFalse(focus.request())
        focus.abandon()

        assertEquals(0, gateway.abandons)
    }

    @Test
    fun `the focus is given back once, on pause, end or stop`() {
        focus.request()

        focus.abandon()
        focus.abandon()

        assertEquals(1, gateway.abandons)
    }

    @Test
    fun `asking twice does not ask the system twice`() {
        focus.request()
        focus.request()

        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun `a call taking the focus pauses and gives it up`() {
        focus.request()

        gateway.listener!!(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertEquals(1, lost)
        assertEquals(1, gateway.abandons)
    }

    @Test
    fun `a duck asked by another app does not pause`() {
        focus.request()

        gateway.listener!!(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertEquals(0, lost)
        assertEquals(0, gateway.abandons)
    }
}
