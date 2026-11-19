package com.maggie.app.voice

import java.io.IOException

/**
 * One synthesis being received: the MP3 bytes arrive on one thread while the player reads
 * them on another. Every byte is kept, so the player may read again from any position.
 */
class SpeechStream {
    private val lock = Object()
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0
    private var complete = false
    private var cancelled = false
    private var failure: Throwable? = null
    private var bitrateKbps = 0

    val receivedBytes: Long get() = synchronized(lock) { size.toLong() }
    val isComplete: Boolean get() = synchronized(lock) { complete }
    val failed: Boolean get() = synchronized(lock) { failure != null }

    /** Duration of the bytes received so far, at the bitrate of the first MP3 frame. */
    val estimatedDurationMs: Long
        get() = synchronized(lock) {
            if (bitrateKbps == 0) bitrateKbps = readBitrateKbps(buffer, size)
            size * 8L / (if (bitrateKbps > 0) bitrateKbps else FALLBACK_KBPS)
        }

    fun append(bytes: ByteArray) {
        synchronized(lock) {
            if (cancelled || complete) return
            if (size + bytes.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + bytes.size))
            bytes.copyInto(buffer, size)
            size += bytes.size
            lock.notifyAll()
        }
    }

    fun complete() {
        synchronized(lock) {
            complete = true
            lock.notifyAll()
        }
    }

    fun fail(cause: Throwable) {
        synchronized(lock) {
            if (failure == null) failure = cause
            lock.notifyAll()
        }
    }

    fun cancel() {
        synchronized(lock) {
            cancelled = true
            lock.notifyAll()
        }
    }

    /**
     * Blocks until there are bytes at [position], the end (-1), the failure ([IOException])
     * or the cancellation (-1).
     */
    fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        synchronized(lock) {
            var result: Int? = null
            while (result == null) {
                result = when {
                    cancelled -> -1
                    position < size -> {
                        val count = minOf(length.toLong(), size - position).toInt()
                        buffer.copyInto(target, offset, position.toInt(), position.toInt() + count)
                        count
                    }
                    failure != null -> throw IOException("Speech stream failed", failure)
                    complete -> -1
                    else -> {
                        lock.wait()
                        null
                    }
                }
            }
            return result
        }
    }

    companion object {
        private const val INITIAL_CAPACITY = 64 * 1024
        const val FALLBACK_KBPS = 128

        private val MPEG1_LAYER3 = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val MPEG2_LAYER3 = intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

        /** Bitrate of the first MPEG layer III frame, after an ID3 tag if any; 0 while unreadable. */
        internal fun readBitrateKbps(data: ByteArray, size: Int): Int {
            var start = 0
            if (size >= 10 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()) {
                val tag = (0 until 4).fold(0) { acc, i -> (acc shl 7) or (data[6 + i].toInt() and 0x7F) }
                val footer = if (data[5].toInt() and 0x10 != 0) 10 else 0
                start = 10 + tag + footer
            }
            if (start + 4 > size) return 0
            val b1 = data[start + 1].toInt() and 0xFF
            val b2 = data[start + 2].toInt() and 0xFF
            if (data[start].toInt() and 0xFF != 0xFF || b1 and 0xE0 != 0xE0) return 0
            val version = (b1 shr 3) and 3
            val layer = (b1 shr 1) and 3
            val index = (b2 shr 4) and 0xF
            if (layer != 1 || version == 1 || index == 0 || index == 15) return 0
            return (if (version == 3) MPEG1_LAYER3 else MPEG2_LAYER3)[index - 1]
        }
    }
}
