package com.maggie.app.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.maggie.app.data.api.MaggieApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

enum class VoiceState {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    PROCESSING,
    SPEAKING,
    ERROR,
}

class VoiceManager(
    private val context: Context,
    private val apiService: MaggieApiService,
) {
    companion object {
        private const val TAG = "VoiceManager"
    }

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state

    private val _duration = MutableStateFlow(0)
    val duration: StateFlow<Int> = _duration

    var onFinalResult: ((String) -> Unit)? = null

    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var timerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun initialize() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRENCH
                ttsReady = true
            }
        }
    }

    fun startListening() {
        cancelListening()
        _duration.value = 0
        _state.value = VoiceState.LISTENING

        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        audioFile = file

        try {
            recorder = createMediaRecorder(file).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            timerJob = scope.launch {
                while (true) {
                    delay(1000)
                    _duration.value += 1
                }
            }
        } catch (e: Exception) {
            Log.e("VoiceManager", "Failed to start recording", e)
            cleanupRecording()
            _state.value = VoiceState.ERROR
        }
    }

    fun stopAndTranscribe() {
        if (_state.value != VoiceState.LISTENING) return

        timerJob?.cancel()
        timerJob = null

        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recorder", e)
        }
        recorder?.release()
        recorder = null

        val file = audioFile ?: run {
            _state.value = VoiceState.ERROR
            return
        }

        _state.value = VoiceState.TRANSCRIBING

        scope.launch {
            try {
                val text = apiService.transcribe(file)
                if (text.isNotBlank()) {
                    onFinalResult?.invoke(text)
                    _state.value = VoiceState.PROCESSING
                } else {
                    _state.value = VoiceState.IDLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                _state.value = VoiceState.ERROR
            } finally {
                file.delete()
                audioFile = null
            }
        }
    }

    fun cancelListening() {
        timerJob?.cancel()
        timerJob = null
        cleanupRecording()
        if (_state.value == VoiceState.LISTENING) {
            _state.value = VoiceState.IDLE
        }
    }

    fun speak(text: String) {
        if (!ttsReady) return
        _state.value = VoiceState.SPEAKING
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                _state.value = VoiceState.IDLE
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _state.value = VoiceState.IDLE
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "maggie_response")
    }

    fun stopSpeaking() {
        tts?.stop()
        if (_state.value == VoiceState.SPEAKING) {
            _state.value = VoiceState.IDLE
        }
    }

    fun destroy() {
        cancelListening()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        _state.value = VoiceState.IDLE
    }

    private fun cleanupRecording() {
        try {
            recorder?.stop()
        } catch (_: Exception) { }
        recorder?.release()
        recorder = null
        audioFile?.delete()
        audioFile = null
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(file: File): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }
}
