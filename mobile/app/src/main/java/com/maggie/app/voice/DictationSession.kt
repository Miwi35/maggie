package com.maggie.app.voice

import android.util.Log
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TranscriptCleanup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.OutputStream

/** Why a dictation ended without text. Mapped to `SpeechRecognizer.ERROR_*` by the service. */
enum class DictationError { AUDIO, NO_SPEECH, NO_MATCH, NETWORK }

/**
 * One dictation for another app (MAG-216): the voice path of MAG-222, minus the button.
 *
 * Same chain as [VoiceManager] — the phone's engine listens along with the recording,
 * [TranscriptionQuality] judges its answer, Whisper reads the clip when it is not good
 * enough — with two differences that come from the text being written as is:
 *
 *  - the end of the sentence is found by [EndOfSpeechDetector], not by a release;
 *  - a good phone result goes out untouched and without any server call, and the
 *    Whisper leg asks for `auto` cleanup, so only a text that needs it meets the fast
 *    model. [HesitationFilter] is not applied: « ben » is also a first name, and a
 *    word dropped silently from what someone typed is worse than an « euh » kept.
 *
 * Free of Android types so the orchestration can be tested; the service is the glue.
 */
class DictationSession(
    private val cacheDir: File,
    private val apiService: MaggieApiService,
    private val recorder: AudioRecorder,
    private val engine: DeviceSpeechRecognizer,
    private val detector: EndOfSpeechDetector,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        /** The microphone is open. */
        fun onListening()

        fun onSpeechStarted()

        fun onSpeechEnded()

        /** Loudness of the last buffer, 0..1. */
        fun onLevel(level: Float)

        fun onPartial(text: String)

        fun onResult(text: String, confidence: Float?)

        fun onError(error: DictationError)
    }

    companion object {
        private const val TAG = "DictationSession"

        /** As in [VoiceManager]: what the engine needs to close a sentence it already heard. */
        const val DEVICE_RESULT_TIMEOUT_MS = 3_000L
    }

    private enum class Phase { IDLE, LISTENING, FINISHING, DONE }

    private var phase = Phase.IDLE
    private var file: File? = null
    private var sink: OutputStream? = null
    private var transcription: Job? = null
    private val pending = CompletableDeferred<DeviceSpeechResult?>()

    fun start() {
        if (phase != Phase.IDLE) return
        phase = Phase.LISTENING

        val clip = File(cacheDir, "dictation_${System.nanoTime()}.${PcmAudioRecorder.FILE_EXTENSION}")
        file = clip

        try {
            sink = startEngine()
            recorder.setLevelListener { level, durationMs -> scope.launch { onLevel(level, durationMs) } }
            recorder.start(clip, sink)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the recording", e)
            fail(DictationError.AUDIO)
            return
        }
        listener.onListening()
    }

    /** The app says it has heard enough. Transcribes what was recorded; nothing to do if idle or already finishing. */
    fun stop() {
        if (phase == Phase.LISTENING) finish()
    }

    /** Throw everything away; nothing is reported. */
    fun cancel() {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        transcription?.cancel()
        teardown()
    }

    private fun startEngine(): OutputStream? {
        if (!engine.isAvailable) return null
        return try {
            engine.start(
                object : DeviceSpeechRecognizer.Listener {
                    override fun onPartial(text: String) {
                        if (phase == Phase.LISTENING) listener.onPartial(text)
                    }

                    override fun onResult(result: DeviceSpeechResult) {
                        pending.complete(result)
                    }

                    override fun onUnavailable(reason: String) {
                        pending.complete(null)
                    }
                },
            )
        } catch (e: Exception) {
            // Not an error for the caller: the recording runs and Whisper is still there.
            Log.w(TAG, "On-device recognition would not start", e)
            engine.destroy()
            null
        }
    }

    private fun onLevel(level: Float, durationMs: Long) {
        if (phase != Phase.LISTENING) return
        listener.onLevel(level)
        when (detector.feed(level, durationMs)) {
            EndOfSpeechDetector.Signal.NONE -> Unit
            EndOfSpeechDetector.Signal.SPEECH_STARTED -> listener.onSpeechStarted()
            EndOfSpeechDetector.Signal.SPEECH_ENDED, EndOfSpeechDetector.Signal.TOO_LONG -> finish()
            EndOfSpeechDetector.Signal.NO_SPEECH -> fail(DictationError.NO_SPEECH)
        }
    }

    private fun finish() {
        phase = Phase.FINISHING

        // Nothing was ever loud enough: no clip is worth a server call, and Whisper
        // makes sentences up out of silence.
        if (!detector.hasSpeech) {
            fail(DictationError.NO_SPEECH)
            return
        }
        listener.onSpeechEnded()

        try {
            recorder.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recorder", e)
        }
        recorder.release()
        // The recorder closed the pipe as it stopped — the engine's end-of-sentence
        // signal; asking it to stop after that is what makes it answer.
        engine.stopListening()

        val clip = file
        val spokenMs = detector.spokenMs
        transcription = scope.launch {
            val heard = withTimeoutOrNull(DEVICE_RESULT_TIMEOUT_MS) { pending.await() }
            engine.destroy()

            if (phase != Phase.FINISHING) return@launch

            if (heard != null && TranscriptionQuality.isGoodEnough(heard.text, heard.confidence, spokenMs)) {
                succeed(heard.text.trim(), heard.confidence)
                return@launch
            }

            if (clip == null) {
                fail(DictationError.NO_MATCH)
                return@launch
            }
            try {
                val text = apiService.transcribe(clip, TranscriptCleanup.AUTO)
                if (phase != Phase.FINISHING) return@launch
                if (text.isNotBlank()) succeed(text.trim(), null) else fail(DictationError.NO_MATCH)
            } catch (e: Exception) {
                Log.w(TAG, "Whisper did not answer", e)
                if (phase != Phase.FINISHING) return@launch
                // A middling local text beats an error the person typing can do nothing about.
                if (heard != null && heard.text.isNotBlank()) {
                    succeed(heard.text.trim(), heard.confidence)
                } else {
                    fail(DictationError.NETWORK)
                }
            }
        }
    }

    private fun succeed(text: String, confidence: Float?) {
        phase = Phase.DONE
        teardown()
        listener.onResult(text, confidence)
    }

    private fun fail(error: DictationError) {
        phase = Phase.DONE
        teardown()
        listener.onError(error)
    }

    private fun teardown() {
        try {
            recorder.stop()
        } catch (_: Exception) { }
        recorder.release()
        try {
            sink?.close()
        } catch (_: Exception) { }
        sink = null
        engine.destroy()
        pending.complete(null)
        file?.delete()
        file = null
    }
}
