package com.maggie.app.voice

/**
 * Decides, from loudness alone, when a dictation is over (MAG-216).
 *
 * « Hold to talk » has a button whose release ends the sentence; a text field asking
 * Maggie for dictation has none, and the engine that used to stop on silence is the
 * one this chain wraps. So the end is ours to find: a pause long enough after speech.
 * Generous by default, because the owner hesitates and a cut-off sentence is the
 * February failure all over again (`bdcb51b`). A fixed threshold is a known limit — a
 * noisy room never goes quiet — and the way out is the app's own stop and [MAX_MS].
 */
class EndOfSpeechDetector(
    private val silenceMs: Long = DEFAULT_SILENCE_MS,
    private val noSpeechMs: Long = NO_SPEECH_MS,
    private val maxMs: Long = MAX_MS,
    private val speechLevel: Float = SPEECH_LEVEL,
) {
    companion object {
        const val DEFAULT_SILENCE_MS = 2_000L
        const val MIN_SILENCE_MS = 500L
        const val NO_SPEECH_MS = 8_000L
        const val MAX_MS = 60_000L

        /** About -36 dBFS: a quiet voice at arm's length, well above a quiet room. */
        const val SPEECH_LEVEL = 0.015f
    }

    enum class Signal { NONE, SPEECH_STARTED, SPEECH_ENDED, NO_SPEECH, TOO_LONG }

    private var elapsedMs = 0L
    private var silentMs = 0L
    private var speechStartMs = -1L
    private var lastSpeechEndMs = 0L
    private var finished = false

    val hasSpeech: Boolean get() = speechStartMs >= 0

    /** How long the voice was heard, first to last loud buffer — what the quality judge compares the text with. */
    val spokenMs: Long get() = if (hasSpeech) lastSpeechEndMs - speechStartMs else 0L

    /** One buffer of [durationMs] at [level]. Each terminal signal is returned once; after it, everything is [Signal.NONE]. */
    fun feed(level: Float, durationMs: Long): Signal {
        if (finished) return Signal.NONE
        val start = elapsedMs
        elapsedMs += durationMs

        if (level >= speechLevel) {
            silentMs = 0
            lastSpeechEndMs = elapsedMs
            if (!hasSpeech) {
                speechStartMs = start
                return Signal.SPEECH_STARTED
            }
        } else if (hasSpeech) {
            silentMs += durationMs
            if (silentMs >= silenceMs) return end(Signal.SPEECH_ENDED)
        } else if (elapsedMs >= noSpeechMs) {
            return end(Signal.NO_SPEECH)
        }

        return if (elapsedMs >= maxMs) end(Signal.TOO_LONG) else Signal.NONE
    }

    private fun end(signal: Signal): Signal {
        finished = true
        return signal
    }
}
