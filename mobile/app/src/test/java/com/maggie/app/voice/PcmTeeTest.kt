package com.maggie.app.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The guarantee the fallback rests on: feeding the recognition engine never holds the
 * recording up. If it could, the clip Whisper reads when the engine does badly would be
 * cut short — and that clip is the reason the owner never has to repeat himself.
 */
class PcmTeeTest {

    @Test
    fun `what is offered reaches the engine, and only the bytes offered`() {
        val sink = ByteArrayOutputStream()
        val tee = PcmTee(sink)

        // A buffer is handed over with a length: the tail is last round's audio.
        tee.offer(byteArrayOf(1, 2, 3, 9, 9), 3)
        tee.offer(byteArrayOf(4, 5), 2)
        tee.finish()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), sink.toByteArray())
        assertEquals(0, tee.dropped)
    }

    @Test
    fun `an engine that stopped reading does not block the recording`() {
        val blocked = CountDownLatch(1)
        val stuck = object : OutputStream() {
            override fun write(b: Int) = Unit

            override fun write(b: ByteArray, off: Int, len: Int) {
                blocked.await(5, TimeUnit.SECONDS)
            }
        }
        val tee = PcmTee(stuck)

        // More buffers than the queue holds, with the writer thread stuck on the first.
        val offers = Thread {
            repeat(PcmTee.QUEUED_BUFFERS + 8) { tee.offer(byteArrayOf(it.toByte()), 1) }
        }
        offers.start()
        offers.join(2_000)

        assertTrue("offering must not wait for the engine", !offers.isAlive)
        assertTrue("the buffers the engine could not take are dropped", tee.dropped > 0)

        blocked.countDown()
        tee.finish()
    }

    @Test
    fun `finishing closes the engine's end — its end-of-sentence signal`() {
        var closed = false
        val sink = object : OutputStream() {
            override fun write(b: Int) = Unit

            override fun close() {
                closed = true
            }
        }

        PcmTee(sink).finish()

        assertTrue(closed)
    }

    @Test
    fun `nothing is offered after finishing`() {
        val sink = ByteArrayOutputStream()
        val tee = PcmTee(sink)

        tee.offer(byteArrayOf(1), 1)
        tee.finish()
        tee.offer(byteArrayOf(2), 1)

        assertArrayEquals(byteArrayOf(1), sink.toByteArray())
    }
}
