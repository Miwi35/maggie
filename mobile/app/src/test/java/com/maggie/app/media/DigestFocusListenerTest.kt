package com.maggie.app.media

import androidx.media3.common.Player
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class DigestFocusListenerTest {

    private class CountingGateway(private val grant: Boolean = true) : AudioFocusGateway {
        var requests = 0
        var abandons = 0
        override fun request(gain: Int, onChange: (Int) -> Unit): Boolean {
            requests++
            return grant
        }

        override fun abandon() {
            abandons++
        }
    }

    private val player = mockk<Player>(relaxed = true)

    private fun listener(gateway: CountingGateway) = DigestFocusListener(player, DigestAudioFocus(gateway) {})

    @Test
    fun `asked to play, the focus is taken first`() {
        val gateway = CountingGateway()

        listener(gateway).onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

        assert(gateway.requests == 1)
        verify(exactly = 0) { player.pause() }
    }

    @Test
    fun `a refused focus keeps the digest from playing`() {
        listener(CountingGateway(grant = false)).onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

        verify { player.pause() }
    }

    @Test
    fun `pause and the end give the focus back`() {
        val gateway = CountingGateway()
        val listener = listener(gateway)

        listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        listener.onPlaybackStateChanged(Player.STATE_ENDED)

        assert(gateway.requests == 2)
        assert(gateway.abandons == 2)
    }

    @Test
    fun `a stop gives the focus back`() {
        val gateway = CountingGateway()
        val listener = listener(gateway)

        listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        listener.onPlaybackStateChanged(Player.STATE_IDLE)

        assert(gateway.abandons == 1)
    }
}
