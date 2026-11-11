package com.maggie.app.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** The system's audio focus, behind an interface so [DigestAudioFocus] is tested on the JVM with a fake. */
interface AudioFocusGateway {
    /** True when the focus was granted; [onChange] gets the `AUDIOFOCUS_*` changes that follow. */
    fun request(gain: Int, onChange: (Int) -> Unit): Boolean
    fun abandon()
}

/**
 * The focus the morning digest plays under (MAG-773): a transient one that lets the music duck while
 * Maggie speaks. Taken before playing, given back at the end, on pause and on stop, so the music resumes.
 */
class DigestAudioFocus(private val gateway: AudioFocusGateway, private val onLost: () -> Unit) {
    private var held = false

    fun request(): Boolean {
        if (held) return true
        held = gateway.request(GAIN) { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                held = false
                gateway.abandon()
                onLost()
            }
        }
        return held
    }

    fun abandon() {
        if (!held) return
        held = false
        gateway.abandon()
    }

    companion object {
        const val GAIN = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    }
}

class AndroidAudioFocusGateway(context: Context) : AudioFocusGateway {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: AudioFocusRequest? = null

    override fun request(gain: Int, onChange: (Int) -> Unit): Boolean {
        val built = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            .setOnAudioFocusChangeListener(onChange)
            .build()
        request = built
        return audioManager.requestAudioFocus(built) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
        request = null
    }
}
