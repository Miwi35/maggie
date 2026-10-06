package com.maggie.app.voice

import android.media.MediaPlayer
import android.util.Log
import java.io.File

interface SpeechPlayer {
    val durationMs: Int
    val positionMs: Int

    fun play(file: File, onFinished: () -> Unit)

    fun stop()

    fun release()
}

class MediaSpeechPlayer : SpeechPlayer {
    private var player: MediaPlayer? = null

    override val durationMs: Int
        get() = runCatching { player?.duration }.getOrNull() ?: 0

    override val positionMs: Int
        get() = runCatching { player?.currentPosition }.getOrNull() ?: 0

    override fun play(file: File, onFinished: () -> Unit) {
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            setOnCompletionListener { onFinished() }
            setOnErrorListener { _, _, _ ->
                onFinished()
                true
            }
            start()
            // Speed-up after start() so a setPlaybackParams failure on certain
            // devices doesn't prevent playback; reuse the existing params so
            // sampling rate, fallback mode, etc. keep their defaults.
            try {
                playbackParams = playbackParams.setSpeed(PLAYBACK_SPEED)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set TTS playback speed", e)
            }
        }
    }

    override fun stop() {
        try {
            player?.apply { if (isPlaying) stop() }
        } catch (_: Exception) { }
    }

    override fun release() {
        try {
            player?.release()
        } catch (_: Exception) { }
        player = null
    }

    private companion object {
        const val TAG = "MediaSpeechPlayer"
        const val PLAYBACK_SPEED = 1.5f
    }
}
