package com.maggie.app.voice

import android.media.AudioManager

object ListeningPolicy {

    private val CALL_MODES = setOf(
        AudioManager.MODE_RINGTONE,
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        AudioManager.MODE_CALL_SCREENING,
    )

    fun isCallMode(audioMode: Int): Boolean = audioMode in CALL_MODES

    fun shouldListen(callActive: Boolean, mediaPlaying: Boolean): Boolean =
        !callActive && !mediaPlaying
}
