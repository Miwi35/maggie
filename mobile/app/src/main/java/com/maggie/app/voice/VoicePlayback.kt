package com.maggie.app.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the chat shows of the voice message being played (MAG-686). Only one plays at a time. */
data class VoicePlaybackState(
    val messageId: String? = null,
    val status: Status = Status.IDLE,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    /** ×1, ×1,5 or ×2: kept from one message to the next, applied to the voice messages only. */
    val speed: Float = VoicePlayback.SPEEDS.first(),
) {
    enum class Status { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    fun statusOf(id: String): Status = if (messageId == id) status else Status.IDLE
}

/**
 * Plays the voice messages of the thread, one at a time. The state lives here, not in the thread:
 * a failure only turns the bubble concerned into an error, the messages around it stay as they are.
 *
 * [resolveUrl] turns a message id into the signed URL to read (authenticated call, 403/404 throw).
 */
class VoicePlayback(
    private val scope: CoroutineScope,
    private val newPlayer: () -> VoiceMessagePlayer,
    private val resolveUrl: suspend (messageId: String) -> String,
) {
    private val _state = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = _state

    private var player: VoiceMessagePlayer? = null
    private var generation = 0
    private var loading: Job? = null
    private var ticker: Job? = null

    /** The play/pause button of a bubble: starts [messageId], or pauses / resumes it. */
    fun toggle(messageId: String, knownDurationMs: Int = 0) {
        val current = _state.value
        if (current.messageId == messageId) {
            when (current.status) {
                VoicePlaybackState.Status.PLAYING -> return pause()
                VoicePlaybackState.Status.PAUSED -> return resume()
                VoicePlaybackState.Status.LOADING -> return
                else -> Unit
            }
        }
        load(messageId, knownDurationMs)
    }

    fun seekTo(messageId: String, positionMs: Int) {
        if (_state.value.messageId != messageId) return
        val target = positionMs.coerceIn(0, _state.value.durationMs.takeIf { it > 0 } ?: Int.MAX_VALUE)
        player?.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    /** ×1 → ×1,5 → ×2 → ×1. */
    fun cycleSpeed() {
        val next = SPEEDS[(SPEEDS.indexOf(_state.value.speed) + 1) % SPEEDS.size]
        player?.setSpeed(next)
        _state.update { it.copy(speed = next) }
    }

    fun stop() {
        generation++
        loading?.cancel()
        ticker?.cancel()
        player?.release()
        player = null
        _state.update { it.copy(messageId = null, status = VoicePlaybackState.Status.IDLE, positionMs = 0, durationMs = 0) }
    }

    private fun pause() {
        player?.pause()
        ticker?.cancel()
        _state.update { it.copy(status = VoicePlaybackState.Status.PAUSED, positionMs = player?.positionMs ?: it.positionMs) }
    }

    private fun resume() {
        player?.start()
        _state.update { it.copy(status = VoicePlaybackState.Status.PLAYING) }
        startTicker()
    }

    private fun load(messageId: String, knownDurationMs: Int) {
        stop()
        val mine = generation
        _state.update {
            it.copy(messageId = messageId, status = VoicePlaybackState.Status.LOADING, positionMs = 0, durationMs = knownDurationMs)
        }
        loading = scope.launch {
            val url = try {
                resolveUrl(messageId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (mine == generation) fail()
                return@launch
            }
            if (mine != generation) return@launch
            val fresh = newPlayer()
            player = fresh
            fresh.prepare(
                url,
                object : VoiceMessagePlayer.Listener {
                    override fun onPrepared(durationMs: Int) {
                        if (mine != generation) return
                        fresh.setSpeed(_state.value.speed)
                        fresh.start()
                        _state.update {
                            it.copy(
                                status = VoicePlaybackState.Status.PLAYING,
                                durationMs = durationMs.takeIf { d -> d > 0 } ?: it.durationMs,
                            )
                        }
                        startTicker()
                    }

                    override fun onCompleted() {
                        if (mine != generation) return
                        ticker?.cancel()
                        // Back to the start, ready to be played again.
                        _state.update { it.copy(status = VoicePlaybackState.Status.PAUSED, positionMs = 0) }
                        fresh.seekTo(0)
                    }

                    override fun onError() {
                        if (mine == generation) fail()
                    }

                    override fun onFocusLost() {
                        if (mine != generation) return
                        ticker?.cancel()
                        _state.update { it.copy(status = VoicePlaybackState.Status.PAUSED, positionMs = fresh.positionMs) }
                    }
                },
            )
        }
    }

    private fun fail() {
        ticker?.cancel()
        player?.release()
        player = null
        _state.update { it.copy(status = VoicePlaybackState.Status.ERROR, positionMs = 0) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                player?.let { p -> _state.update { it.copy(positionMs = p.positionMs) } }
                delay(TICK_MS)
            }
        }
    }

    companion object {
        val SPEEDS = listOf(1f, 1.5f, 2f)
        private const val TICK_MS = 250L
    }
}
