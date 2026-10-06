package com.maggie.app.voice

import kotlin.math.sqrt

/** Loudness and length of a buffer of 16-bit little-endian mono PCM. */
object PcmLevel {
    /** Root mean square of the samples, 0 (silence) to 1 (full scale). */
    fun rms(buffer: ByteArray, length: Int): Float {
        val samples = length / 2
        if (samples == 0) return 0f
        var sum = 0.0
        for (i in 0 until samples) {
            val sample = ((buffer[2 * i + 1].toInt() shl 8) or (buffer[2 * i].toInt() and 0xFF)).toShort()
            sum += sample.toDouble() * sample
        }
        return (sqrt(sum / samples) / Short.MAX_VALUE).toFloat()
    }

    fun durationMs(length: Int): Long =
        length / 2 * 1000L / PcmAudioRecorder.SAMPLE_RATE
}
