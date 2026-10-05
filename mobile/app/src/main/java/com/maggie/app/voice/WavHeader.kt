package com.maggie.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 44-byte RIFF header that turns the microphone's raw PCM into a file Whisper
 * accepts. Its own unit, because it is the one piece of the recording path that can be
 * checked without a microphone — and a wrong field here does not crash anything, it
 * just makes Whisper hear nonsense.
 */
internal object WavHeader {
    const val BYTES = 44

    private const val PCM_UNCOMPRESSED: Short = 1

    /** The header for [dataBytes] of PCM at [sampleRate], [channelCount], [bitsPerSample]. */
    fun forPcm(dataBytes: Long, sampleRate: Int, channelCount: Int, bitsPerSample: Int): ByteArray {
        val bytesPerFrame = channelCount * bitsPerSample / 8
        return ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt((36 + dataBytes).toInt()) // everything after this field
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16) // size of the fmt chunk
            putShort(PCM_UNCOMPRESSED)
            putShort(channelCount.toShort())
            putInt(sampleRate)
            putInt(sampleRate * bytesPerFrame) // byte rate
            putShort(bytesPerFrame.toShort()) // block align
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(dataBytes.toInt())
        }.array()
    }
}
