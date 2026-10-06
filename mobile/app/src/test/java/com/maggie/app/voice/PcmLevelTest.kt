package com.maggie.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmLevelTest {
    private fun pcm(vararg samples: Int): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            bytes[2 * i] = (s and 0xFF).toByte()
            bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun `silence has no level`() {
        val buffer = pcm(0, 0, 0, 0)

        assertEquals(0f, PcmLevel.rms(buffer, buffer.size), 0f)
    }

    @Test
    fun `full scale square wave is a level of one, whatever its sign`() {
        val buffer = pcm(32767, -32767, 32767, -32767)

        assertEquals(1f, PcmLevel.rms(buffer, buffer.size), 0.001f)
    }

    @Test
    fun `only the bytes that were read count`() {
        val buffer = pcm(0, 0, 32767, 32767)

        assertEquals(0f, PcmLevel.rms(buffer, 4), 0f)
    }

    @Test
    fun `an empty read has no level`() {
        assertEquals(0f, PcmLevel.rms(ByteArray(8), 0), 0f)
    }

    @Test
    fun `a buffer lasts its samples at the recording rate`() {
        // 16 000 samples a second, two bytes each: 3 200 bytes are a fifth of a second.
        assertEquals(100L, PcmLevel.durationMs(3_200))
    }
}
