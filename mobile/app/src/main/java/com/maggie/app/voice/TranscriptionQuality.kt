package com.maggie.app.voice

import kotlin.math.roundToInt

/**
 * Whether what the phone heard is good enough to use, judged without a model (MAG-222).
 *
 * The voice path now starts with the phone's own recognition and keeps Whisper as the
 * fallback, so something has to pick between the two the moment the button is
 * released — before anything is sent, and in no time at all. These are the three signs
 * the owner named: Google's own confidence, an empty result, and a result far too
 * short for how long he spoke.
 */
object TranscriptionQuality {
    /**
     * Below this, Google is telling us it guessed. Picked loose on purpose: the
     * fallback costs a Whisper call and a second of latency, so a result worth using
     * should not be thrown away over a middling score.
     */
    const val MIN_CONFIDENCE = 0.55f

    /**
     * Natural French runs at roughly twelve characters a second; a fifth of that means
     * the engine dropped most of what was said — the failure the owner met in
     * February, when it stopped at the first pause.
     *
     * Set low because the two mistakes do not cost the same, and because the duration
     * here is how long the button was held, not how long he spoke: calling a good
     * result bad costs one Whisper call, calling a truncated one good puts half a
     * sentence in front of Maggie.
     */
    const val MIN_CHARS_PER_SECOND = 2.5

    /**
     * Under this, there is not enough speech for the rate above to mean anything: a
     * one-second « oui » is three characters and perfectly good.
     */
    const val RATE_FLOOR_MS = 2_000L

    fun isGoodEnough(text: String, confidence: Float?, spokenMillis: Long): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (confidence != null && confidence < MIN_CONFIDENCE) return false
        if (spokenMillis < RATE_FLOOR_MS) return true
        return trimmed.length >= minCharsFor(spokenMillis)
    }

    /** How many characters [spokenMillis] of speech should have produced, at the floor rate. */
    fun minCharsFor(spokenMillis: Long): Int = (spokenMillis / 1000.0 * MIN_CHARS_PER_SECOND).roundToInt()
}
