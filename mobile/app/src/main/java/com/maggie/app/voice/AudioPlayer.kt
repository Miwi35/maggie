package com.maggie.app.voice

import android.media.MediaPlayer
import java.io.File

interface SpeechPlayer {
    val durationMs: Int
    val positionMs: Int

    fun play(file: File, onFinished: () -> Unit)

    fun stop()

    fun release()
}

class MediaSpeechPlayer(
    private val newPlayer: () -> MediaPlayer = { MediaPlayer() },
) : SpeechPlayer {
    private var player: MediaPlayer? = null

    override val durationMs: Int
        get() = runCatching { player?.duration }.getOrNull() ?: 0

    override val positionMs: Int
        get() = runCatching { player?.currentPosition }.getOrNull() ?: 0

    override fun play(file: File, onFinished: () -> Unit) {
        player = newPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            setOnCompletionListener { onFinished() }
            setOnErrorListener { _, _, _ ->
                onFinished()
                true
            }
            start()
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
}
