package com.maggie.app.media

import androidx.media3.common.Player

/** Ties the audio focus to the player (MAG-773): taken when it is asked to play, given back on pause, end and error. */
class DigestFocusListener(private val player: Player, private val focus: DigestAudioFocus) : Player.Listener {

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) {
            if (!focus.request()) player.pause()
        } else {
            focus.abandon()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) focus.abandon()
    }
}
