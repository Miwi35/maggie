package com.maggie.app.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Build
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

        // Skip transcription for very short recordings (< 1s) — likely accidental tap
        if (_duration.value == 0) {
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
                    playbackParams = PlaybackParams().setSpeed(1.5f)
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

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(file: File): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }
}
