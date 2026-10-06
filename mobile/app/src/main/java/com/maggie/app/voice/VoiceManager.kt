package com.maggie.app.voice

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TranscriptCleanup
import com.maggie.app.data.repository.UserPreferenceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.OutputStream

enum class VoiceState {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    PROCESSING,
    SPEAKING,
    ERROR,
}

private const val DEFAULT_VOICE = "fr-FR-DeniseNeural"

/**
 * The voice path: the phone's own recognition first, Whisper in reserve (MAG-222).
 *
 * While the button is held, the microphone is captured once and the bytes go two ways —
 * into a file, and into the embedded engine, which shows what it hears as it hears it.
 * On release the result is judged on the spot, without a model
 * ([TranscriptionQuality]); good enough and it is used as is, otherwise the recording
 * already on disk goes to Whisper and the owner never repeats himself. Nothing on
 * this path asks the model to tidy a transcript any more: Maggie reads through a
 * hesitation on her own, and the bubble gets [HesitationFilter].
 */
class VoiceManager(
    private val context: Context,
    private val apiService: MaggieApiService,
    private val userPreferenceRepository: UserPreferenceRepository,
    private val recorderFactory: () -> AudioRecorder = { PcmAudioRecorder() },
    private val deviceSpeechFactory: () -> DeviceSpeechRecognizer = { NoDeviceSpeech },
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    companion object {
        private const val TAG = "VoiceManager"
        const val MIN_HOLD_MS = 300L
        private const val HOLD_HINT_MS = 2500L

        /**
         * How long the engine gets to hand back its final sentence after the button
         * comes up. It has already heard everything, so this is the time it needs to
         * close the sentence, not to listen; past it, Whisper takes the recording.
         */
        const val DEVICE_RESULT_TIMEOUT_MS = 3000L
    }

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state

    private val _duration = MutableStateFlow(0)
    val duration: StateFlow<Int> = _duration

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _holdHint = MutableStateFlow(false)
    val holdHint: StateFlow<Boolean> = _holdHint

    /** What the phone's engine has heard so far, shown while the button is held. Empty when it is not listening. */
    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText

    // Owned by the recording in progress, handed in by whoever started it: the
    // manager is a singleton shared by the chat sheet and the assistant overlay, so
    // a field they both wrote was won by the last one in, and lost to the first.
    private var onResult: ((String) -> Unit)? = null

    private var recorder: AudioRecorder? = null
    private var audioFile: File? = null
    private var timerJob: Job? = null
    private var hintJob: Job? = null

    /**
     * One run of the phone's engine, owned by whoever started it. Held together rather
     * than as three fields so that a recording started while the previous one is still
     * being transcribed cannot release the new engine (`startListening` can do that:
     * the assistant gesture does not wait for anything).
     */
    private class DeviceSession(
        val engine: DeviceSpeechRecognizer,
        val pending: CompletableDeferred<DeviceSpeechResult?>,
    ) {
        var sink: OutputStream? = null

        /**
         * Set the moment the sentence is over (the button comes up). An engine that
         * answers before that closed the sentence on a pause of its own: what it
         * heard is only the beginning, and only the release ends a sentence.
         */
        var sentenceOver = false
    }

    private var deviceSession: DeviceSession? = null

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

    fun startListening(onResult: (String) -> Unit) = beginRecording(handsFree = true, onResult)

    fun pressDown(onResult: (String) -> Unit) {
        when (_state.value) {
            VoiceState.IDLE, VoiceState.ERROR -> beginRecording(handsFree = false, onResult)
            VoiceState.SPEAKING -> {
                stopSpeaking()
                beginRecording(handsFree = false, onResult)
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

    private fun beginRecording(handsFree: Boolean, onResult: (String) -> Unit) {
        cancelListening()
        this.onResult = onResult
        hintJob?.cancel()
        _holdHint.value = false
        _handsFree.value = handsFree
        recordingStartedAt = clock()
        _duration.value = 0
        _errorMessage.value = null
        _partialText.value = ""
        _state.value = VoiceState.LISTENING

        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.${PcmAudioRecorder.FILE_EXTENSION}")
        audioFile = file

        try {
            recorder = recorderFactory()
            recorder?.start(file, startDeviceSpeech())

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

    /**
     * Ask the phone's engine to listen along, and hand back the stream the recorder has
     * to copy the microphone into. Null — no engine, or it opens the microphone itself
     * — leaves the recorder writing to the file alone.
     */
    private fun startDeviceSpeech(): OutputStream? {
        val engine = deviceSpeechFactory().takeIf { it.isAvailable } ?: return null
        val pending = CompletableDeferred<DeviceSpeechResult?>()
        val session = DeviceSession(engine, pending)

        return try {
            session.sink = engine.start(
                object : DeviceSpeechRecognizer.Listener {
                    override fun onPartial(text: String) {
                        if (!session.sentenceOver && _state.value == VoiceState.LISTENING) _partialText.value = text
                    }

                    override fun onResult(result: DeviceSpeechResult) {
                        // Before the release it is a segment, never the sentence:
                        // the recording goes on and Whisper reads all of it.
                        pending.complete(result.takeIf { session.sentenceOver })
                    }

                    override fun onUnavailable(reason: String) {
                        pending.complete(null)
                    }
                },
            )
            deviceSession = session
            session.sink
        } catch (e: Exception) {
            // An engine that refuses to start is not an error the owner should see:
            // the recording is still running and Whisper is still there.
            Log.w(TAG, "On-device recognition would not start", e)
            engine.destroy()
            null
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

        val spokenMillis = clock() - recordingStartedAt

        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recorder", e)
        }
        recorder?.release()
        recorder = null

        val session = deviceSession
        deviceSession = null
        session?.sentenceOver = true
        // The recorder closed the pipe as it stopped, which is what tells the engine
        // the sentence is over; asking it to stop after that is what makes it answer.
        session?.engine?.stopListening()

        val file = audioFile ?: run {
            release(session)
            _state.value = VoiceState.ERROR
            return
        }

        _state.value = VoiceState.TRANSCRIBING

        val callback = onResult
        onResult = null
        scope.launch {
            val heard = awaitDeviceResult(session)
            if (heard != null && TranscriptionQuality.isGoodEnough(heard.text, heard.confidence, spokenMillis)) {
                file.delete()
                audioFile = null
                _partialText.value = ""
                deliver(callback, HesitationFilter.strip(heard.text))
                return@launch
            }

            // Not good enough, or nothing at all: Whisper reads the clip the same hold
            // produced. The owner sees which of the two answered without being told —
            // the phone's engine writes as he speaks, Whisper only once he lets go.
            _partialText.value = ""

            try {
                val text = apiService.transcribe(file, TranscriptCleanup.NONE)
                if (text.isNotBlank()) {
                    deliver(callback, text)
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

    private fun deliver(callback: ((String) -> Unit)?, text: String) {
        callback?.invoke(text)
        _state.value = VoiceState.PROCESSING
    }

    /** The engine's last word, or null when there is no engine, it gave up, or it took too long. */
    private suspend fun awaitDeviceResult(session: DeviceSession?): DeviceSpeechResult? {
        if (session == null) return null
        val heard = withTimeoutOrNull(DEVICE_RESULT_TIMEOUT_MS) { session.pending.await() }
        release(session)
        return heard
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
        onResult = null
        try {
            recorder?.stop()
        } catch (_: Exception) { }
        recorder?.release()
        recorder = null
        audioFile?.delete()
        audioFile = null
        _partialText.value = ""
        release(deviceSession)
        deviceSession = null
    }

    private fun release(session: DeviceSession?) {
        if (session == null) return
        // Normally the recorder's stop closed it already; this covers the engine that
        // was started and then never handed to a recorder at all.
        try {
            session.sink?.close()
        } catch (_: Exception) { }
        session.engine.destroy()
        // Anything still waiting on it gets « nothing heard » rather than a timeout.
        session.pending.complete(null)
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
