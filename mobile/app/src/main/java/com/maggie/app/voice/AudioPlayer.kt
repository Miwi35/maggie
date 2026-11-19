package com.maggie.app.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource

interface SpeechPlayer {
    val durationMs: Int
    val positionMs: Int

    fun play(audio: SpeechStream, onFinished: () -> Unit)

    fun stop()

    fun release()
}

/** Plays a synthesis while it is still arriving; ExoPlayer lives on the main thread, whoever calls. */
class StreamingSpeechPlayer(
    private val context: Context,
    private val newPlayer: () -> ExoPlayer = { buildPlayer(context) },
    private val runOnMain: (Runnable) -> Unit = ::postToMain,
) : SpeechPlayer {
    @Volatile
    private var player: ExoPlayer? = null
    @Volatile
    private var released = false
    private var stream: SpeechStream? = null

    override val durationMs: Int
        get() = stream?.estimatedDurationMs?.toInt() ?: 0

    override val positionMs: Int
        get() = runCatching { player?.currentPosition?.toInt() }.getOrNull() ?: 0

    override fun play(audio: SpeechStream, onFinished: () -> Unit) {
        stream = audio
        runOnMain {
            if (released) return@runOnMain
            var finished = false
            val finish = {
                if (!finished) {
                    finished = true
                    onFinished()
                }
            }
            val exo = newPlayer()
            player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) finish()
                }

                override fun onPlayerError(error: PlaybackException) = finish()
            })
            val source = ProgressiveMediaSource.Factory(SpeechStreamDataSource.Factory(audio))
                .createMediaSource(MediaItem.fromUri("maggie-speech://live"))
            exo.setMediaSource(source)
            exo.prepare()
            exo.play()
        }
    }

    override fun stop() {
        runOnMain {
            try {
                player?.stop()
            } catch (_: Exception) { }
        }
    }

    override fun release() {
        released = true
        runOnMain {
            val exo = player
            player = null
            try {
                exo?.release()
            } catch (_: Exception) { }
        }
    }

    private companion object {
        fun buildPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                false,
            )
            .setLoadControl(
                DefaultLoadControl.Builder().setBufferDurationsMs(1000, 30000, 250, 500).build(),
            )
            .build()

        fun postToMain(task: Runnable) {
            if (Looper.myLooper() == Looper.getMainLooper()) task.run() else Handler(Looper.getMainLooper()).post(task)
        }
    }
}
