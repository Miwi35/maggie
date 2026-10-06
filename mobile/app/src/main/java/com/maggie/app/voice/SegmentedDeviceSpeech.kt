package com.maggie.app.voice

import android.util.Log
import java.io.IOException
import java.io.OutputStream

/**
 * Makes the phone's recognition listen for as long as the button is held
 * (retour de recette MAG-222).
 *
 * « Maintenir pour parler n'est pas fiable : l'écoute s'arrête avant que le
 * propriétaire relâche le bouton. » Google's engine closes its sentence on a silence
 * of its own accord — the February failure (`bdcb51b`) that MAG-221's button was
 * supposed to answer, and which came back because the button only governs *our*
 * recording, not the engine's idea of a sentence. Asking for longer silence windows
 * ([OnDeviceSpeechRecognizer]) is a hint an engine may ignore; this is the guarantee.
 *
 * Each run the engine closes by itself becomes a segment, the next run starts at once
 * on the same microphone, and the pieces are joined. Nothing is delivered until
 * [stopListening] — the release, and the release alone, ends the sentence.
 *
 * It wraps a factory of single-run recognizers rather than one engine: a
 * `SpeechRecognizer` that has delivered its result is done, and only a fresh one
 * listens again.
 */
class SegmentedDeviceSpeech(
    private val runs: () -> DeviceSpeechRecognizer,
) : DeviceSpeechRecognizer {

    companion object {
        private const val TAG = "SegmentedSpeech"

        /**
         * How many times a hold may restart the engine. Far above any real sentence —
         * the owner would have to pause a dozen times — and there to bound an engine
         * that ends every run instantly: past it the hold keeps recording and Whisper
         * reads the clip, which is the chain's floor anyway.
         */
        const val MAX_RUNS = 12
    }

    /** The current run, also the one asked whether this phone can transcribe at all. */
    private var run: DeviceSpeechRecognizer? = null
    private var runsStarted = 0
    private var listening = false

    private var listener: DeviceSpeechRecognizer.Listener? = null
    private var relay: RelayOutputStream? = null

    private val segments = mutableListOf<String>()
    private var worstConfidence: Float? = null
    private var lastReason = "no match"
    private var fatal = false

    // Set by endSentence(), which the relay's close() runs on PcmTee's thread, and read
    // on the main one when the engine answers. Volatile so the answer cannot find a
    // cached « the button is still down » and open another run.
    @Volatile
    private var sentenceOver = false
    private var delivered = false

    override val isAvailable: Boolean get() = engine().isAvailable

    private fun engine(): DeviceSpeechRecognizer = run ?: runs().also { run = it }

    override fun start(listener: DeviceSpeechRecognizer.Listener): OutputStream? {
        this.listener = listener
        val sink = startRun() ?: return null
        // The recorder closes what it is handed when it stops, and that close is the
        // end of the sentence — so it has to reach us, not only the pipe underneath:
        // whatever the engine answers after it must not open another run.
        return RelayOutputStream(sink, onClose = ::endSentence).also { relay = it }
    }

    /**
     * The audio is over — no more runs. Not an answer yet: the engine that is still
     * listening owes us its last words, and [stopListening] is what asks for them.
     */
    private fun endSentence() {
        sentenceOver = true
    }

    override fun stopListening() {
        sentenceOver = true
        val live = run.takeIf { listening }
        if (live == null) {
            // Nothing is listening: the engine gave up earlier in the hold. Answering
            // now rather than letting the caller time out is what keeps the segments
            // already heard.
            deliver()
            return
        }
        live.stopListening()
    }

    override fun destroy() {
        listening = false
        // Before the engine is touched, not after the relay as it used to be: an engine
        // can answer from inside its own destroy(), and that answer must not open
        // another run on a hold that is over.
        sentenceOver = true
        // The engine's read end goes first: letting go of it is what ends a write
        // blocked on a full pipe, where closing the relay would have to wait on it.
        run?.destroy()
        run = null
        try {
            relay?.close()
        } catch (_: IOException) { }
        relay = null
    }

    /** Opens [next] as the following run and returns the stream its engine wants the audio in. */
    private fun startRun(next: DeviceSpeechRecognizer = engine()): OutputStream? {
        runsStarted += 1
        listening = true
        return next.start(runListener)
    }

    private val runListener = object : DeviceSpeechRecognizer.Listener {
        override fun onPartial(text: String) {
            listener?.onPartial(join(text))
        }

        override fun onResult(result: DeviceSpeechResult) {
            listening = false
            if (result.text.isNotBlank()) {
                segments += result.text.trim()
                worstConfidence = weakest(worstConfidence, result.confidence)
            }
            if (sentenceOver) deliver() else restart()
        }

        override fun onUnavailable(reason: String, fatal: Boolean) {
            listening = false
            lastReason = reason
            this@SegmentedDeviceSpeech.fatal = fatal
            when {
                sentenceOver -> deliver()
                fatal -> Log.i(TAG, "The engine is out for good ($reason); the clip is Whisper's")
                else -> restart()
            }
        }
    }

    /** The engine closed a segment while the button is still down: give it a new run. */
    private fun restart() {
        if (runsStarted >= MAX_RUNS) {
            Log.w(TAG, "The engine closed $runsStarted runs in one hold; leaving the clip to Whisper")
            return
        }
        run?.destroy()
        run = null
        val mine = engine()
        val sink = try {
            startRun(mine)
        } catch (e: Exception) {
            // A refused restart is not an error the owner should see: the recording is
            // still running and Whisper still has the clip.
            Log.w(TAG, "The engine would not take another run", e)
            listening = false
            run?.destroy()
            run = null
            return
        }
        // A run that answers from inside its own start() — [NoDeviceSpeech] does — has
        // already come back through here and opened a newer one. That newer run is the
        // live engine, and this frame has destroyed the run it started: retargeting
        // now would leave the live one deaf for the rest of the hold.
        if (run === mine) relay?.retarget(sink)
    }

    private fun deliver() {
        if (delivered) return
        delivered = true
        val text = join()
        val heard = listener
        if (text.isBlank()) {
            heard?.onUnavailable(lastReason, fatal)
        } else {
            heard?.onResult(DeviceSpeechResult(text, worstConfidence))
        }
    }

    /** Everything heard so far, plus [live] when a run is still writing it. */
    private fun join(live: String = ""): String =
        (segments + live.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * A sentence is worth its weakest piece: one segment the engine guessed at is
     * enough to send the whole hold to Whisper, which has the audio for all of it.
     */
    private fun weakest(current: Float?, next: Float?): Float? = when {
        current == null -> next
        next == null -> current
        else -> minOf(current, next)
    }
}

/**
 * An [OutputStream] whose destination can be swapped while it is being written to, and
 * which never fails the writer.
 *
 * Both halves are what [SegmentedDeviceSpeech] needs. The recorder is handed one stream
 * for the whole hold ([AudioRecorder.start]) while the engine behind it is replaced at
 * every segment, hence the swap. And a write that threw would end [PcmTee] — it closes
 * the sink and stops pumping on the first `IOException` — so a single broken pipe at a
 * segment boundary would leave every later run deaf; hence the swallowing. What the
 * engine misses, Whisper reads from the file.
 */
internal class RelayOutputStream(
    initial: OutputStream?,
    private val onClose: () -> Unit,
) : OutputStream() {

    companion object {
        private const val TAG = "RelayStream"
    }

    // The destination is read under the lock and written to outside it. Holding the
    // monitor across the write is what would turn a full pipe into an ANR: the write
    // happens on PcmTee's own thread precisely because it can block for as long as the
    // engine keeps its read end without draining it, and close() and retarget() are
    // called from the main one.
    private val lock = Any()
    private var target: OutputStream? = initial
    private var open = true

    /** Send what comes next to [next] instead, and let go of the previous destination. */
    fun retarget(next: OutputStream?) {
        val previous = synchronized(lock) {
            if (!open) return
            target.also { target = next }
        }
        closeQuietly(previous)
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        val to = synchronized(lock) { target.takeIf { open } } ?: return
        try {
            to.write(b, off, len)
        } catch (e: IOException) {
            // This engine stopped reading. Drop what it will not take until it is
            // replaced; the recording goes on either way.
            Log.i(TAG, "The recognition engine stopped reading", e)
            forget(to)
        }
    }

    override fun flush() {
        val to = synchronized(lock) { target.takeIf { open } } ?: return
        try {
            to.flush()
        } catch (_: IOException) {
            forget(to)
        }
    }

    override fun close() {
        val previous = synchronized(lock) {
            if (!open) return
            open = false
            target.also { target = null }
        }
        // Before closing the pipe: the engine answers on that close, and the answer
        // must find a sentence already declared over.
        onClose()
        closeQuietly(previous)
    }

    /** Drop [stale] — unless a [retarget] has already put a live run's pipe in its place. */
    private fun forget(stale: OutputStream) = synchronized(lock) {
        if (target === stale) target = null
    }

    private fun closeQuietly(stream: OutputStream?) {
        try {
            stream?.close()
        } catch (_: IOException) { }
    }
}
