package com.maggie.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The header Whisper reads the fallback clip through. Nothing crashes when a field is
 * wrong — the transcription just comes back as nonsense — so it is checked field by
 * field, at the offsets the RIFF layout fixes.
 */
class WavHeaderTest {

    private val dataBytes = 32_000L // one second of 16 kHz mono 16-bit
    private val header = WavHeader.forPcm(
        dataBytes,
        PcmAudioRecorder.SAMPLE_RATE,
        PcmAudioRecorder.CHANNEL_COUNT,
        PcmAudioRecorder.BITS_PER_SAMPLE,
    )

    private fun ascii(bytes: ByteArray, at: Int, length: Int) = String(bytes, at, length, Charsets.US_ASCII)

    private fun int32(bytes: ByteArray, at: Int) =
        ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun int16(bytes: ByteArray, at: Int) =
        ByteBuffer.wrap(bytes, at, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

    @Test
    fun `the header is 44 bytes`() {
        assertEquals(44, header.size)
        assertEquals(WavHeader.BYTES, header.size)
    }

    @Test
    fun `the chunk markers are where a reader looks for them`() {
        assertEquals("RIFF", ascii(header, 0, 4))
        assertEquals("WAVE", ascii(header, 8, 4))
        assertEquals("fmt ", ascii(header, 12, 4))
        assertEquals("data", ascii(header, 36, 4))
    }

    @Test
    fun `the RIFF size counts everything after its own field`() {
        assertEquals(36 + dataBytes.toInt(), int32(header, 4))
    }

    @Test
    fun `the format chunk says uncompressed mono PCM`() {
        assertEquals(16, int32(header, 16)) // size of the fmt chunk
        assertEquals(1, int16(header, 20)) // 1 = PCM, uncompressed
        assertEquals(PcmAudioRecorder.CHANNEL_COUNT, int16(header, 22))
        assertEquals(PcmAudioRecorder.BITS_PER_SAMPLE, int16(header, 34))
    }

    @Test
    fun `the rates follow the sampling rate`() {
        assertEquals(PcmAudioRecorder.SAMPLE_RATE, int32(header, 24))
        assertEquals(32_000, int32(header, 28)) // byte rate: 16 kHz × 1 channel × 2 bytes
        assertEquals(2, int16(header, 32)) // block align: one 16-bit mono frame
    }

    @Test
    fun `the data size is the audio handed over`() {
        assertEquals(dataBytes.toInt(), int32(header, 40))
        assertEquals(0, int32(WavHeader.forPcm(0, 16_000, 1, 16), 40))
    }

    @Test
    fun `stereo at 44 kHz moves the rates with it`() {
        val cd = WavHeader.forPcm(1_000, 44_100, 2, 16)

        assertEquals(2, int16(cd, 22))
        assertEquals(44_100, int32(cd, 24))
        assertEquals(176_400, int32(cd, 28)) // 44 100 × 2 channels × 2 bytes
        assertEquals(4, int16(cd, 32))
    }
}
