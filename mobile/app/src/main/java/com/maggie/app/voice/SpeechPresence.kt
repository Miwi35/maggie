package com.maggie.app.voice

/**
 * Whether a recording being made actually holds a voice, measured from loudness alone
 * (retour de recette MAG-222).
 *
 * Whisper does not answer nothing when it is given nothing: it writes the subtitle
 * boilerplate it was trained on, and « Thank you for watching » reached the owner's
 * chat without his having said a word. The clip is ours before it is Whisper's, so the
 * cheapest fix is not to send it: a hold that was never loud enough goes nowhere, and
 * the owner is told « Je n'ai rien entendu » instead of being answered about a video.
 * The server keeps its own gate for the clips this one cannot judge — the web's, and
 * any recorder that reports no level.
 *
 * Unlike [EndOfSpeechDetector] this one never ends anything. While the button is held,
 * only the release ends the sentence (MAG-221), so a three-second pause in the middle
 * must not stop the measurement — which is the other half of what was refused.
 */
class SpeechPresence(
    /** The same threshold the dictation's end-of-speech uses: about -36 dBFS. */
    private val speechLevel: Float = EndOfSpeechDetector.SPEECH_LEVEL,
    private val minSpeechMs: Long = MIN_SPEECH_MS,
) {
    companion object {
        /**
         * Under a third of a second of voice there is no sentence — a door closing, a
         * tap on the case, a finger slipping off the button.
         */
        const val MIN_SPEECH_MS = 300L
    }

    // Written on the recording thread, read on the main one once the recorder has
    // stopped (which joins that thread). Volatile so a read before the join still
    // sees the latest value rather than a cached one.
    @Volatile
    private var elapsedMs = 0L

    @Volatile
    private var voicedMs = 0L

    @Volatile
    private var firstVoicedAtMs = -1L

    @Volatile
    private var lastVoicedAtMs = 0L

    /** One buffer of [durationMs] at [level] (0..1), as the recorder reads it. */
    fun feed(level: Float, durationMs: Long) {
        val start = elapsedMs
        elapsedMs = start + durationMs
        if (level < speechLevel) return
        voicedMs += durationMs
        if (firstVoicedAtMs < 0) firstVoicedAtMs = start
        lastVoicedAtMs = elapsedMs
    }

    /** Whether anything was measured at all. False for a recorder that reports no level. */
    val measured: Boolean get() = elapsedMs > 0L

    /**
     * Whether this clip is worth sending. A recorder that never reported a level gets
     * the benefit of the doubt: refusing what was never measured would silence the
     * voice path on any device whose capture cannot be read, and the server's gate
     * catches the hallucination anyway.
     */
    val heardSpeech: Boolean get() = !measured || voicedMs >= minSpeechMs

    /**
     * First to last loud buffer — how long the voice lasted, which is what the quality
     * judge compares the text with. Closer to the truth than the length of the hold:
     * the owner holds the button before he starts and after he stops, and the pause in
     * the middle is now his to take.
     */
    val spokenMs: Long get() = if (firstVoicedAtMs >= 0) lastVoicedAtMs - firstVoicedAtMs else 0L
}
