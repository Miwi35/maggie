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
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class OnDeviceSpeechRecognizer(
    private val context: Context,
    private val language: String = "fr-FR",
) : DeviceSpeechRecognizer {

    companion object {
        private const val TAG = "OnDeviceSpeech"
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
                deliverUnavailable(listener, "empty result")
            } else if (!delivered) {
                delivered = true
                listener.onResult(best)
            }
        }

        override fun onError(error: Int) = deliverUnavailable(listener, "error $error")

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun deliverUnavailable(listener: DeviceSpeechRecognizer.Listener, reason: String) {
        if (delivered) return
        delivered = true
        Log.i(TAG, "On-device recognition gave up ($reason) — going to Whisper")
        listener.onUnavailable(reason)
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
