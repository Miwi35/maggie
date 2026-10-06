package com.maggie.app.voice

import java.io.OutputStream

/** What the phone's own recognition heard, with the engine's own opinion of it. */
data class DeviceSpeechResult(val text: String, val confidence: Float?)

/**
 * The phone's own speech recognition — the first leg of the voice path (MAG-222).
 *
 * It was dropped in February 2026 (`bdcb51b`) for cutting off at the first pause and
 * for stumbling on natural speech. « Hold to talk » (MAG-221) was supposed to answer
 * the first — and did not, because the button governs our recording and not the
 * engine's idea of a sentence: it still closed on a silence with the button held
 * (retour de recette MAG-222). An implementation here is therefore *one run*, which an
 * engine may end whenever it likes, and [SegmentedDeviceSpeech] is what makes a hold
 * last across as many runs as it takes. The second reason is why nothing here is
 * trusted blindly — [TranscriptionQuality] judges the result and Whisper takes over on
 * the audio already recorded.
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

        /** The final sentence of this run. Called at most once per [start]. */
        fun onResult(result: DeviceSpeechResult)

        /**
         * This run produced nothing usable. Called at most once per [start].
         *
         * [fatal] separates « it heard no words » — a pause, a silence, which another
         * run can follow — from « the engine itself is out »: no permission, busy,
         * language unavailable. A restart would fail the same way, so only the second
         * sends the hold to Whisper for good ([SegmentedDeviceSpeech]).
         */
        fun onUnavailable(reason: String, fatal: Boolean)
    }
}

/**
 * The stand-in for a phone with no on-device recognition, and the default: the voice
 * path then behaves exactly as it did before MAG-222, Whisper alone.
 */
object NoDeviceSpeech : DeviceSpeechRecognizer {
    override val isAvailable = false

    override fun start(listener: DeviceSpeechRecognizer.Listener): OutputStream? {
        listener.onUnavailable("no on-device recognition", fatal = true)
        return null
    }

    override fun stopListening() = Unit

    override fun destroy() = Unit
}
