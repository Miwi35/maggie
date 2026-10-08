package com.maggie.app.ui.interruption

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.fcm.PushActionHandler
import com.maggie.app.data.fcm.PushActionKind
import com.maggie.app.data.fcm.PushOutcome
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.data.interruption.InterruptionCenter
import com.maggie.app.data.interruption.key
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InterruptionUiState(
    val payload: PushPayload? = null,
    /** An answer is on its way: the buttons wait for it. */
    val busy: Boolean = false,
    /** Why the last answer did not go through. */
    val note: String? = null,
)

/** What the screen hosting the interruption must do once an answer is given. */
sealed interface InterruptionEvent {
    /** « Voir l'événement » and the like: go where the message points. */
    data class OpenLink(val payload: PushPayload) : InterruptionEvent

    /** Answering a message in her own words happens in the chat. */
    data object OpenChat : InterruptionEvent
}

/**
 * The interruption on screen and what its buttons do (MAG-314). The same answers as the
 * buttons of the push notification, carried out by the same handler, so answering here
 * closes the request everywhere.
 *
 * « Plus tard » is local for now — the push's own alarm brings it back in ten minutes — until
 * the server knows how to be told (partie 9 of the spec).
 */
class InterruptionViewModel(
    private val center: InterruptionCenter,
    private val handler: PushActionHandler,
    /** Takes down the system notification a push left in the tray before the app was opened. */
    private val dismiss: (PushPayload) -> Unit = {},
    private val postpone: (PushPayload) -> Unit,
) : ViewModel() {

    private val busy = MutableStateFlow<String?>(null)
    private val failure = MutableStateFlow<Pair<String, String>?>(null)
    private val _events = Channel<InterruptionEvent>(Channel.UNLIMITED)

    val events: Flow<InterruptionEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<InterruptionUiState> = combine(center.current, busy, failure) { payload, busyKey, failed ->
        InterruptionUiState(
            payload = payload,
            busy = payload != null && busyKey == payload.key,
            note = failed?.takeIf { payload != null && it.first == payload.key }?.second,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, InterruptionUiState())

    /** True once per interruption, across screens: coming back to the app with it still on screen must not ring again. */
    fun announce(key: String): Boolean = center.announce(key)

    fun answer(kind: PushActionKind) {
        val payload = center.current.value ?: return
        val key = payload.key
        if (busy.value == key) return

        when (kind) {
            PushActionKind.LATER -> {
                postpone(payload)
                dismiss(payload)
                center.postpone(key)
            }
            PushActionKind.GO -> {
                center.close(key)
                dismiss(payload)
                _events.trySend(InterruptionEvent.OpenLink(payload))
            }
            PushActionKind.REPLY -> {
                center.close(key)
                dismiss(payload)
                _events.trySend(InterruptionEvent.OpenChat)
            }
            else -> viewModelScope.launch {
                busy.value = key
                failure.value = null
                try {
                    when (val outcome = handler.handle(kind, payload)) {
                        is PushOutcome.Retry -> failure.value = key to outcome.message
                        PushOutcome.Closed, PushOutcome.Postponed, is PushOutcome.Settled -> {
                            center.close(key)
                            dismiss(payload)
                        }
                    }
                } finally {
                    busy.value = null
                }
            }
        }
    }

    /** Thirty seconds without an answer: it goes away and stays unread. */
    fun timedOut(key: String) = center.close(key)
}
