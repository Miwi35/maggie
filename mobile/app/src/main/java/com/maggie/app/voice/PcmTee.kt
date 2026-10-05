package com.maggie.app.voice

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Copies the microphone into the recognition engine's pipe, on its own thread, and
 * drops what the engine is too slow to read.
 *
 * It exists because the engine's end of that pipe is a ~64 kB kernel buffer — two
 * seconds of audio — and writing to a full pipe blocks. Two behaviours the chain
 * expects would hit exactly that: an engine that ignores `EXTRA_AUDIO_SOURCE` and
 * listens on the microphone itself, and one that closes its sentence at the first
 * pause while the button is still held. If the recorder wrote the pipe itself, either
 * would freeze the capture mid-hold — and the clip Whisper is supposed to fall back on
 * is the one thing that must survive. So the recording never waits for the engine: it
 * hands buffers over, and a buffer that does not fit is lost to the engine alone.
 */
internal class PcmTee(private val sink: OutputStream) {
    companion object {
        /** About a second of 16 kHz mono at the recorder's buffer size. */
        const val QUEUED_BUFFERS = 16

        private const val POLL_MS = 10L
        private const val FINISH_TIMEOUT_MS = 500L
        private const val TAG = "PcmTee"
    }

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUED_BUFFERS)

    @Volatile
    private var open = true

    /** How many buffers the engine was too slow to take. Read by the tests. */
    @Volatile
    var dropped = 0
        private set

    private val writer = Thread({ drain() }, "voice-pcm-tee").apply {
        isDaemon = true
        start()
    }

    /** Hand the first [length] bytes of [buffer] to the engine, or drop them. Never blocks. */
    fun offer(buffer: ByteArray, length: Int) {
        if (!open) return
        if (!queue.offer(buffer.copyOf(length))) dropped += 1
    }

    /**
     * Stop feeding the engine and close its end of the pipe — its end-of-sentence
     * signal. Returns once the queue is drained, or gives up on a writer still stuck
     * in a blocked write: that thread is a daemon and ends by itself when the engine
     * lets go of the pipe.
     */
    fun finish() {
        open = false
        writer.join(FINISH_TIMEOUT_MS)
    }

    private fun drain() {
        try {
            while (true) {
                // A chunk already queued is always written before the flag is read, so
                // finish() loses nothing it had room for.
                val chunk = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    if (open) continue else break
                }
                sink.write(chunk)
            }
            sink.flush()
        } catch (e: IOException) {
            // The engine let go of its end: it has what it needs, or it never wanted
            // our audio. Either way the recording carries on without us.
            Log.i(TAG, "The recognition engine stopped reading", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            try {
                sink.close()
            } catch (_: IOException) { }
        }
    }
}
