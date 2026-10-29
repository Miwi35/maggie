package com.maggie.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.PowerManager

/**
 * The engine under a voice bubble of the chat (MAG-686): one signed URL, play, pause, seek, speed.
 * Behind an interface so [VoicePlayback] is tested on the JVM with a fake. Callbacks come on the main thread.
 */
interface VoiceMessagePlayer {
    interface Listener {
        fun onPrepared(durationMs: Int)
        fun onCompleted()
        fun onError()

        /** Another app (a call, a video) took the audio: playback is already paused. */
        fun onFocusLost()
    }

    val positionMs: Int

    fun prepare(url: String, listener: Listener)
    fun start()
    fun pause()
    fun seekTo(positionMs: Int)
    fun setSpeed(speed: Float)
    fun release()
}

/**
 * [MediaPlayer] with a partial wake lock (playback goes on with the screen off) and audio focus
 * (a call or another player pauses it). Speed is applied only while playing: on some versions
 * setting the parameters of a paused player starts it.
 */
class MediaVoiceMessagePlayer(private val context: Context) : VoiceMessagePlayer {
    private var player: MediaPlayer? = null
    private var listener: VoiceMessagePlayer.Listener? = null
    private var speed = 1f
    private var focusRequest: AudioFocusRequest? = null
    private val audioManager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override val positionMs: Int
        get() = runCatching { player?.currentPosition }.getOrNull() ?: 0

    override fun prepare(url: String, listener: VoiceMessagePlayer.Listener) {
        this.listener = listener
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            setOnPreparedListener { listener.onPrepared(duration) }
            setOnCompletionListener {
                abandonFocus()
                listener.onCompleted()
            }
            setOnErrorListener { _, _, _ ->
                abandonFocus()
                listener.onError()
                true
            }
            try {
                setDataSource(url)
                prepareAsync()
            } catch (_: Exception) {
                listener.onError()
            }
        }
    }

    override fun start() {
        if (!requestFocus()) {
            listener?.onFocusLost()
            return
        }
        runCatching {
            player?.start()
            applySpeed()
        }.onFailure { listener?.onError() }
    }

    override fun pause() {
        runCatching { player?.pause() }
        abandonFocus()
    }

    override fun seekTo(positionMs: Int) {
        runCatching { player?.seekTo(positionMs) }
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        if (runCatching { player?.isPlaying }.getOrNull() == true) applySpeed()
    }

    private fun applySpeed() {
        runCatching { player?.playbackParams = PlaybackParams().setSpeed(speed) }
    }

    override fun release() {
        abandonFocus()
        runCatching { player?.release() }
        player = null
        listener = null
    }

    private fun requestFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                // A short duck is not worth stopping for; a call or another player is.
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    runCatching { player?.pause() }
                    listener?.onFocusLost()
                }
            }
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
