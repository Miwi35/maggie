package com.maggie.app.voice

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.repository.UserPreferenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class VoiceState {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    PROCESSING,
    SPEAKING,
    ERROR,
}

private const val DEFAULT_VOICE = "fr-FR-DeniseNeural"

class VoiceManager(
    private val context: Context,
    private val apiService: MaggieApiService,
    private val userPreferenceRepository: UserPreferenceRepository,
    private val recorderFactory: () -> AudioRecorder = { MediaAudioRecorder(context) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    companion object {
        private const val TAG = "VoiceManager"
        const val MIN_HOLD_MS = 300L
        private const val HOLD_HINT_MS = 2500L
    }

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state

    private val _duration = MutableStateFlow(0)
    val duration: StateFlow<Int> = _duration

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _holdHint = MutableStateFlow(false)
    val holdHint: StateFlow<Boolean> = _holdHint

    var onFinalResult: ((String) -> Unit)? = null

    private var recorder: AudioRecorder? = null
    private var audioFile: File? = null
    private var timerJob: Job? = null
    private var hintJob: Job? = null

    // Listening opened without a button held down (assistant gesture): the
    // button ends it with a tap, since there was no press to release.
    private val _handsFree = MutableStateFlow(false)
    val handsFree: StateFlow<Boolean> = _handsFree
    private var recordingStartedAt = 0L

    private var mediaPlayer: MediaPlayer? = null
    private var ttsVoice: String = DEFAULT_VOICE

    fun initialize() {
        scope.launch {
            try {
                ttsVoice = apiService.getTtsVoice()
            } catch (e: Exception) {
                Log.w(TAG, "Could not load TTS voice preference, using default", e)
            }
        }
    }

    fun setVoice(voice: String) {
        ttsVoice = voice
    }

    fun startListening() = beginRecording(handsFree = true)

    fun pressDown() {
        when (_state.value) {
            VoiceState.IDLE, VoiceState.ERROR -> beginRecording(handsFree = false)
            VoiceState.SPEAKING -> {
                stopSpeaking()
                beginRecording(handsFree = false)
            }
            VoiceState.LISTENING, VoiceState.TRANSCRIBING, VoiceState.PROCESSING -> Unit
        }
    }

    fun pressRelease() {
        if (_state.value != VoiceState.LISTENING) return
        if (_handsFree.value) {
            stopAndTranscribe()
            return
        }
        if (clock() - recordingStartedAt < MIN_HOLD_MS) {
            cancelListening()
            showHoldHint()
        } else {
            stopAndTranscribe()
        }
    }

    fun pressCancel() {
        if (_state.value == VoiceState.LISTENING && !_handsFree.value) cancelListening()
    }

    private fun showHoldHint() {
        hintJob?.cancel()
        _holdHint.value = true
        hintJob = scope.launch {
            delay(HOLD_HINT_MS)
            _holdHint.value = false
        }
    }

    private fun beginRecording(handsFree: Boolean) {
        cancelListening()
        hintJob?.cancel()
        _holdHint.value = false
        _handsFree.value = handsFree
        recordingStartedAt = clock()
        _duration.value = 0
        _errorMessage.value = null
        _state.value = VoiceState.LISTENING

        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        audioFile = file

        try {
            recorder = recorderFactory()
            recorder?.start(file)

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

        // Hands-free only: a hold is filtered by its press length in pressRelease()
        if (_handsFree.value && _duration.value == 0) {
            cleanupRecording()
            _state.value = VoiceState.IDLE
            return
        }

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
                _errorMessage.value = extractTranscribeErrorMessage(e)
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
        _state.value = VoiceState.SPEAKING
        scope.launch(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                val audioBytes = apiService.synthesizeSpeech(text, ttsVoice)
                tempFile = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
                tempFile.writeBytes(audioBytes)

                val player = MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    prepare()
                    setOnCompletionListener {
                        _state.value = VoiceState.IDLE
                        it.release()
                        tempFile.delete()
                        mediaPlayer = null
                    }
                    setOnErrorListener { mp, _, _ ->
                        _state.value = VoiceState.IDLE
                        mp.release()
                        tempFile.delete()
                        mediaPlayer = null
                        true
                    }
                    start()
                    // Speed-up after start() so a setPlaybackParams failure on certain
                    // devices doesn't prevent playback; reuse the existing params so
                    // sampling rate, fallback mode, etc. keep their defaults.
                    try {
                        playbackParams = playbackParams.setSpeed(1.5f)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to set TTS playback speed", e)
                    }
                }
                mediaPlayer = player
            } catch (e: Exception) {
                Log.e(TAG, "TTS synthesis failed", e)
                _state.value = VoiceState.IDLE
                tempFile?.delete()
            }
        }
    }

    fun stopSpeaking() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) { }
        mediaPlayer = null
        if (_state.value == VoiceState.SPEAKING) {
            _state.value = VoiceState.IDLE
        }
    }

    fun destroy() {
        cancelListening()
        stopSpeaking()
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
}

/**
 * The agent returns FastAPI HTTPException payloads as {"detail": "..."}.
 * Ktor wraps those in ResponseException-style messages. Try to pull the
 * detail out so the UI can show what actually went wrong (quota, auth, …)
 * instead of a generic "Erreur".
 */
private fun extractTranscribeErrorMessage(e: Throwable): String {
    val detailRegex = Regex("\"detail\"\\s*:\\s*\"([^\"]+)\"")
    val raw = e.message ?: e.cause?.message
    if (raw != null) {
        detailRegex.find(raw)?.groupValues?.getOrNull(1)?.let { return it }
    }
    return raw?.take(140) ?: "Erreur de transcription"
}
