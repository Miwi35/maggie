package com.maggie.app.voice

import java.io.OutputStream

/** What the phone's own recognition heard, with the engine's own opinion of it. */
data class DeviceSpeechResult(val text: String, val confidence: Float?)

/**
 * The phone's own speech recognition — the first leg of the voice path (MAG-222).
 *
 * It was dropped in February 2026 (`bdcb51b`) for cutting off at the first pause and
 * for stumbling on natural speech. « Hold to talk » (MAG-221) answers the first: the
 * end of the sentence is the button coming up, not a silence. The second is why
 * nothing here is trusted blindly — [TranscriptionQuality] judges the result and
 * Whisper takes over on the audio already recorded.
 *
 * Deliberately free of Android types so the orchestration can be unit-tested: an
 * implementation hands back the stream its engine wants the audio in, or null when it
 * opens the microphone by itself.
 */
interface DeviceSpeechRecognizer {
    /** Whether this phone can transcribe on its own, right now, in French. */
    val isAvailable: Boolean

    /**
     * Start listening and return the sink to write raw PCM into — the app owns the
     * microphone and feeds the engine, so the same capture serves Whisper if the
     * result is not good enough. Null means the engine listens for itself and the
     * recording runs alongside it.
     */
    fun start(listener: Listener): OutputStream?

    /** The sentence is over: deliver a final result (or an error) and stop. */
    fun stopListening()

    /** Release the engine. Safe to call without a [start]. */
    fun destroy()

    interface Listener {
        /** Text so far, while the button is still held. */
        fun onPartial(text: String)

        /** The final sentence. Called at most once per [start]. */
        fun onResult(result: DeviceSpeechResult)

        /** Nothing usable will come — go to Whisper. Called at most once per [start]. */
        fun onUnavailable(reason: String)
    }
}

/**
 * The stand-in for a phone with no on-device recognition, and the default: the voice
 * path then behaves exactly as it did before MAG-222, Whisper alone.
 */
object NoDeviceSpeech : DeviceSpeechRecognizer {
    override val isAvailable = false

    override fun start(listener: DeviceSpeechRecognizer.Listener): OutputStream? {
        listener.onUnavailable("no on-device recognition")
        return null
    }

    override fun stopListening() = Unit

    override fun destroy() = Unit
}
