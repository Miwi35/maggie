package com.maggie.app.voice

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpeechStreamTest {

    private fun frame(versionBits: Int, bitrateIndex: Int): ByteArray =
        byteArrayOf(0xFF.toByte(), (0xE0 or (versionBits shl 3) or (1 shl 1) or 1).toByte(), (bitrateIndex shl 4).toByte(), 0)

    @Test
    fun `a read waits for the next chunk`() {
        val stream = SpeechStream()
        val read = CountDownLatch(1)
        var count = 0
        val target = ByteArray(8)
        thread {
            count = stream.readAt(0, target, 0, 8)
            read.countDown()
        }
        assertTrue(!read.await(100, TimeUnit.MILLISECONDS))

        stream.append(byteArrayOf(1, 2, 3))

        assertTrue(read.await(2, TimeUnit.SECONDS))
        assertEquals(3, count)
    }

    @Test
    fun `a read after complete returns the end`() {
        val stream = SpeechStream()
        stream.append(byteArrayOf(1))
        stream.complete()

        assertEquals(1, stream.readAt(0, ByteArray(4), 0, 4))
        assertEquals(-1, stream.readAt(1, ByteArray(4), 0, 4))
    }

    @Test
    fun `a failed stream throws on read`() {
        val stream = SpeechStream()
        stream.fail(IllegalStateException("boom"))

        try {
            stream.readAt(0, ByteArray(4), 0, 4)
            fail("expected an IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `a cancelled stream releases a blocked reader`() {
        val stream = SpeechStream()
        val read = CountDownLatch(1)
        var count = 0
        thread {
            count = stream.readAt(0, ByteArray(4), 0, 4)
            read.countDown()
        }
        Thread.sleep(50)

        stream.cancel()

        assertTrue(read.await(2, TimeUnit.SECONDS))
        assertEquals(-1, count)
    }

    @Test
    fun `a read at an offset starts there`() {
        val stream = SpeechStream()
        stream.append(byteArrayOf(1, 2, 3, 4))
        val target = ByteArray(4)

        val count = stream.readAt(2, target, 0, 4)

        assertEquals(2, count)
        assertEquals(listOf<Byte>(3, 4), target.take(2))
    }

    @Test
    fun `duration follows the bitrate of the first MPEG-1 frame`() {
        val stream = SpeechStream()
        stream.append(frame(3, 9) + ByteArray(16_000 - 4))

        assertEquals(1_000L, stream.estimatedDurationMs)
    }

    @Test
    fun `an MPEG-2 frame gives its own bitrate`() {
        val stream = SpeechStream()
        stream.append(frame(2, 6) + ByteArray(6_000 - 4))

        assertEquals(1_000L, stream.estimatedDurationMs)
    }

    @Test
    fun `an ID3 tag is skipped before the first frame`() {
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 6) + ByteArray(6)
        val stream = SpeechStream()
        stream.append(tag + frame(2, 6) + ByteArray(6_000 - tag.size - 4))

        assertEquals(1_000L, stream.estimatedDurationMs)
    }

    @Test
    fun `an unreadable header falls back to 128 kbps`() {
        val stream = SpeechStream()
        stream.append(ByteArray(16_000))

        assertEquals(1_000L, stream.estimatedDurationMs)
    }
}
