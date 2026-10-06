package com.maggie.app.voice

import android.content.Context
import android.util.Log
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TranscriptCleanup
import com.maggie.app.data.repository.UserPreferenceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val playerFactory: () -> SpeechPlayer = { MediaSpeechPlayer() },
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

    var onFinalResult: ((String) -> Unit)? = null

    // Maggie was cut off while preparing an answer (null) or while saying it (what was
    // heard so far, "" if nothing yet): whoever owns the answer says it in the history.
    var onInterrupt: ((String?) -> Unit)? = null

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
        val sink: OutputStream?,
        val pending: CompletableDeferred<DeviceSpeechResult?>,
    )

    private var deviceSession: DeviceSession? = null

    // Listening opened without a button held down (assistant gesture): the
    // button ends it with a tap, since there was no press to release.
    private val _handsFree = MutableStateFlow(false)
    val handsFree: StateFlow<Boolean> = _handsFree
    private var recordingStartedAt = 0L

    private val speakLock = Any()
    private var speakGeneration = 0
    private var speakJob: Job? = null
    private var speechPlayer: SpeechPlayer? = null
    private var speechFile: File? = null
    private var spokenText: String = ""
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
        interrupt()
        beginRecording(handsFree = true)
    }

    fun pressDown() {
        when (_state.value) {
            VoiceState.IDLE, VoiceState.ERROR -> beginRecording(handsFree = false)
            VoiceState.SPEAKING, VoiceState.PROCESSING -> {
                interrupt()
                beginRecording(handsFree = false)
            }
            VoiceState.LISTENING, VoiceState.TRANSCRIBING -> Unit
        }
    }

    /** Cuts Maggie off wherever she is — preparing, synthesizing or speaking. False when she was not busy. */
    fun interrupt(): Boolean {
        val heard = when (_state.value) {
            VoiceState.PROCESSING -> null
            VoiceState.SPEAKING -> heardSoFar()
            else -> return false
        }
        stopSpeaking()
        _state.value = VoiceState.IDLE
        onInterrupt?.invoke(heard)
        return true
    }

    private fun heardSoFar(): String {
        val player = synchronized(speakLock) { speechPlayer } ?: return ""
        val duration = player.durationMs
        if (duration <= 0) return ""
        return heardPart(spokenText, player.positionMs.toFloat() / duration)
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

        return try {
            val sink = engine.start(
                object : DeviceSpeechRecognizer.Listener {
                    override fun onPartial(text: String) {
                        if (_state.value == VoiceState.LISTENING) _partialText.value = text
                    }

                    override fun onResult(result: DeviceSpeechResult) {
                        pending.complete(result)
                    }

                    override fun onUnavailable(reason: String) {
                        pending.complete(null)
                    }
                },
            )
            deviceSession = DeviceSession(engine, sink, pending)
            sink
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
        // The recorder closed the pipe as it stopped, which is what tells the engine
        // the sentence is over; asking it to stop after that is what makes it answer.
        session?.engine?.stopListening()

        val file = audioFile ?: run {
            release(session)
            _state.value = VoiceState.ERROR
            return
        }

        _state.value = VoiceState.TRANSCRIBING

        scope.launch {
            val heard = awaitDeviceResult(session)
            if (heard != null && TranscriptionQuality.isGoodEnough(heard.text, heard.confidence, spokenMillis)) {
                file.delete()
                audioFile = null
                _partialText.value = ""
                deliver(HesitationFilter.strip(heard.text))
                return@launch
            }

            // Not good enough, or nothing at all: Whisper reads the clip the same hold
            // produced. The owner sees which of the two answered without being told —
            // the phone's engine writes as he speaks, Whisper only once he lets go.
            _partialText.value = ""

            try {
                val text = apiService.transcribe(file, TranscriptCleanup.NONE)
                if (text.isNotBlank()) {
                    deliver(text)
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

    private fun deliver(text: String) {
        onFinalResult?.invoke(text)
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
        val generation = invalidateSpeech()
        spokenText = text
        _state.value = VoiceState.SPEAKING
        speakJob = scope.launch(ioDispatcher) {
            var tempFile: File? = null
            try {
                val audioBytes = apiService.synthesizeSpeech(text, ttsVoice)
                tempFile = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
                tempFile.writeBytes(audioBytes)

                // The user may have cut Maggie off while the voice was being made: that audio
                // is never played, however late it arrives.
                synchronized(speakLock) {
                    if (generation != speakGeneration) {
                        tempFile.delete()
                        return@launch
                    }
                    val player = playerFactory()
                    speechPlayer = player
                    speechFile = tempFile
                    player.play(tempFile) { onPlaybackFinished(generation) }
                }
            } catch (e: CancellationException) {
                tempFile?.delete()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "TTS synthesis failed", e)
                tempFile?.delete()
                synchronized(speakLock) {
                    if (generation == speakGeneration) {
                        releasePlayer()
                        _state.value = VoiceState.IDLE
                    }
                }
            }
        }
    }

    private fun onPlaybackFinished(generation: Int) {
        synchronized(speakLock) {
            if (generation != speakGeneration) return
            releasePlayer()
            _state.value = VoiceState.IDLE
        }
    }

    private fun invalidateSpeech(): Int = synchronized(speakLock) {
        speakGeneration++
        speakJob?.cancel()
        speakJob = null
        speechPlayer?.stop()
        releasePlayer()
        speakGeneration
    }

    fun stopSpeaking() {
        invalidateSpeech()
        if (_state.value == VoiceState.SPEAKING) {
            _state.value = VoiceState.IDLE
        }
    }

    private fun releasePlayer() {
        speechPlayer?.release()
        speechPlayer = null
        speechFile?.delete()
        speechFile = null
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

/** The start of [text] a voice has covered at [fraction] of its length, cut back to the last whole word. */
internal fun heardPart(text: String, fraction: Float): String {
    val covered = (text.length * fraction.coerceIn(0f, 1f)).toInt()
    if (covered >= text.length) return text
    val head = text.substring(0, covered)
    if (text[covered].isWhitespace()) return head.trimEnd()
    return head.substringBeforeLast(' ', "").trimEnd()
}
