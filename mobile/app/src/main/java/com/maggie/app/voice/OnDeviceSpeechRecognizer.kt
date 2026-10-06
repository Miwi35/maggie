package com.maggie.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.OutputStream

/**
 * Google's embedded recognition, fed with the app's own microphone capture.
 *
 * Android 13 is what makes the whole chain possible: `createOnDeviceSpeechRecognizer`
 * runs the model on the phone, and `EXTRA_AUDIO_SOURCE` lets us hand it a pipe instead
 * of letting it open the microphone. One capture, two readers — the engine listens
 * live while the same bytes land in a file, so when the result is not good enough
 * Whisper gets the audio and the owner never repeats himself (MAG-222).
 *
 * Feeding our own audio is best-effort by contract: an engine may ignore the extras and
 * listen for itself. That is survivable — it then captures in parallel, and whichever
 * of the two gets silence loses to the other, which is exactly what the judge and the
 * fallback are for.
 *
 * This is **one run**, which the engine ends when it decides the sentence is over —
 * including on a silence, with the button still held, which is what the owner refused
 * in recette. The silence windows below ask it to wait much longer, and
 * [SegmentedDeviceSpeech] carries the hold across the runs it ends anyway.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class OnDeviceSpeechRecognizer(
    private val context: Context,
    private val language: String = "fr-FR",
) : DeviceSpeechRecognizer {

    companion object {
        private const val TAG = "OnDeviceSpeech"

        /**
         * How long a silence the engine should sit through before closing a sentence.
         * Ten seconds rather than its default second or two: the owner hesitates, and
         * while the button is down a pause is a pause, not an end. Documented as a
         * hint an engine may ignore — hence the segments.
         */
        private const val COMPLETE_SILENCE_MS = 10_000

        /** A longer window still for a sentence it merely *suspects* is finished. */
        private const val POSSIBLY_COMPLETE_SILENCE_MS = 15_000

        /** It must not close a run before the owner has had time to start. */
        private const val MINIMUM_LENGTH_MS = 2_000

        /**
         * Errors a restart cannot help: the engine, not the speech, is what failed.
         * Everything else — no match, speech timeout — is a run that simply heard no
         * words, and the next one gets its turn.
         *
         * `ERROR_RECOGNIZER_BUSY` is deliberately not here: it is the likeliest answer
         * to the destroy-create-start that [SegmentedDeviceSpeech] does from inside the
         * previous run's own callback. Calling it fatal would turn off segmentation —
         * the behaviour the owner refused, by another route — while retrying costs at
         * most [SegmentedDeviceSpeech.MAX_RUNS] quick runs before the hold falls back to
         * Whisper, which is the chain's floor anyway.
         */
        private val FATAL_ERRORS = setOf(
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
        )
    }

    private var recognizer: SpeechRecognizer? = null
    private var audioSource: ParcelFileDescriptor? = null
    private var delivered = false

    override val isAvailable: Boolean
        get() = try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } catch (e: Exception) {
            Log.w(TAG, "Could not ask for on-device recognition", e)
            false
        }

    override fun start(listener: DeviceSpeechRecognizer.Listener): OutputStream? {
        delivered = false
        val engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = engine

        val pipe = ParcelFileDescriptor.createPipe()
        audioSource = pipe[0]

        engine.setRecognitionListener(adapt(listener))
        engine.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PcmAudioRecorder.SAMPLE_RATE)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, PcmAudioRecorder.CHANNEL_COUNT)
                // « Hold to talk » means held: a silence is not the end of anything
                // while the button is down (retour de recette MAG-222).
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    POSSIBLY_COMPLETE_SILENCE_MS,
                )
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MINIMUM_LENGTH_MS)
            },
        )
        return ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
    }

    override fun stopListening() {
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop the engine", e)
        }
    }

    override fun destroy() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Could not release the engine", e)
        }
        recognizer = null
        try {
            audioSource?.close()
        } catch (_: Exception) { }
        audioSource = null
    }

    private fun adapt(listener: DeviceSpeechRecognizer.Listener) = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            bestOf(partialResults)?.let { listener.onPartial(it.text) }
        }

        override fun onResults(results: Bundle?) {
            val best = bestOf(results)
            if (best == null) {
                deliverUnavailable(listener, "empty result", fatal = false)
            } else if (!delivered) {
                delivered = true
                listener.onResult(best)
            }
        }

        override fun onError(error: Int) =
            deliverUnavailable(listener, "error $error", fatal = error in FATAL_ERRORS)

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun deliverUnavailable(listener: DeviceSpeechRecognizer.Listener, reason: String, fatal: Boolean) {
        if (delivered) return
        delivered = true
        Log.i(TAG, "This run of on-device recognition gave up ($reason, fatal=$fatal)")
        listener.onUnavailable(reason, fatal)
    }

    /** The engine's first guess, with its confidence when it reports one. */
    private fun bestOf(results: Bundle?): DeviceSpeechResult? {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            ?.firstOrNull()
            ?.takeIf { it > 0f }
        return DeviceSpeechResult(text, confidence)
    }
}
