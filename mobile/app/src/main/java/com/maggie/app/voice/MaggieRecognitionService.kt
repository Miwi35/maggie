package com.maggie.app.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.maggie.app.data.api.MaggieApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.android.ext.android.inject

/**
 * Maggie as the phone's speech recognition (MAG-216).
 *
 * Declared as the `recognitionService` of [MaggieVoiceInteractionService]: Android
 * refuses a voice interaction service without one, and then keeps the plain
 * `ACTION_ASSIST` activity instead — the overlay opens, but the session that carries
 * the screen context is never bound. Being the assistant also makes this service the
 * phone's dictation engine, so it is a real one: every app that asks for speech
 * recognition (keyboard, browser) gets the chain of MAG-222 through [DictationSession].
 *
 * Android calls the three `on…` methods on the main thread, one client at a time as
 * far as we are concerned: a second caller is told the recognizer is busy.
 */
class MaggieRecognitionService : RecognitionService() {

    private val apiService: MaggieApiService by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: DictationSession? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        if (session != null) {
            listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        val silenceMs = recognizerIntent
            .getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 0L)
            .takeIf { it > 0 }
            ?.coerceAtLeast(EndOfSpeechDetector.MIN_SILENCE_MS)
            ?: EndOfSpeechDetector.DEFAULT_SILENCE_MS

        val dictation = DictationSession(
            cacheDir = cacheDir,
            apiService = apiService,
            recorder = audioRecorderFactory(this)(),
            engine = deviceSpeechFactory(this)(),
            detector = EndOfSpeechDetector(silenceMs = silenceMs),
            scope = scope,
            listener = adapt(listener),
        )
        session = dictation
        dictation.start()
    }

    override fun onStopListening(listener: Callback) {
        session?.stop()
    }

    override fun onCancel(listener: Callback) {
        session?.cancel()
        session = null
    }

    override fun onDestroy() {
        session?.cancel()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun adapt(callback: Callback) = object : DictationSession.Listener {
        override fun onListening() = guarded { callback.readyForSpeech(Bundle()) }

        override fun onSpeechStarted() = guarded { callback.beginningOfSpeech() }

        override fun onSpeechEnded() = guarded { callback.endOfSpeech() }

        // The scale the framework documents is dB, roughly -2 (quiet) to 10 (loud).
        override fun onLevel(level: Float) = guarded { callback.rmsChanged(level * RMS_SCALE) }

        override fun onPartial(text: String) = guarded {
            callback.partialResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
            })
        }

        override fun onResult(text: String, confidence: Float?) {
            session = null
            guarded {
                callback.results(Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                    // Whisper has no score; 1f is not an invention, it is « not asked ».
                    putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(confidence ?: 1f))
                })
            }
        }

        override fun onError(error: DictationError) {
            session = null
            guarded {
                callback.error(
                    when (error) {
                        DictationError.AUDIO -> SpeechRecognizer.ERROR_AUDIO
                        DictationError.NO_SPEECH -> SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        DictationError.NO_MATCH -> SpeechRecognizer.ERROR_NO_MATCH
                        DictationError.NETWORK -> SpeechRecognizer.ERROR_NETWORK
                    },
                )
            }
        }
    }

    /** The client may have gone away between two callbacks; that is its business, not a crash of ours. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "The client of the recognition service is gone", e)
        }
    }

    private companion object {
        const val TAG = "MaggieRecognition"
        const val RMS_SCALE = 10f
    }
}
