package com.maggie.app.ui.screens.contexts

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Context
import com.maggie.app.data.repository.ContextRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ContextUiState(
    val contexts: List<Context> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** The thread the owner asked to delete and has not confirmed yet (MAG-342). */
    val deletionToConfirm: Context? = null,
    /** A thread gone from the list that « Annuler » still brings back; nothing was sent yet. */
    val undoableDeletion: Context? = null,
    /** The server refused or never got the deletion: the thread is back, say so once. */
    val deleteFailed: Boolean = false,
)

/**
 * What the chat has to know about a thread's fate, to follow the list: it hides the
 * thread's messages as soon as the owner confirms, shows them again on « Annuler » or
 * on a failure, and drops them for good once the server has deleted the thread.
 */
sealed interface ThreadEvent {
    val contextId: String

    data class Hidden(override val contextId: String) : ThreadEvent
    data class Restored(override val contextId: String) : ThreadEvent
    data class Deleted(override val contextId: String) : ThreadEvent
}

class ContextViewModel(
    private val contextRepository: ContextRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContextUiState())
    val uiState: StateFlow<ContextUiState> = _uiState

    private val _threadEvents = MutableSharedFlow<ThreadEvent>(extraBufferCapacity = 16)
    val threadEvents: SharedFlow<ThreadEvent> = _threadEvents

    /** A confirmed deletion waiting out its « Annuler » window. At most one at a time. */
    private class PendingDeletion(val context: Context, val index: Int) {
        var timer: Job? = null
        var committing = false
    }

    private var pending: PendingDeletion? = null
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "ContextViewModel"

        /** How long « Fil supprimé · Annuler » stays, and so how long the server hears nothing. */
        const val UNDO_WINDOW_MS = 6_000L
    }

    init {
        refresh()
        subscribeToMercure()
    }

    fun refresh() {
        viewModelScope.launch {
            // Reopened with a list already shown: it stays, the counts are refreshed under it.
            _uiState.value = _uiState.value.copy(isLoading = _uiState.value.contexts.isEmpty(), error = null)
            try {
                val contexts = contextRepository.getContexts().getOrThrow()
                // A thread deleted on screen but not yet sent to the server is still in
                // this answer: it must not come back until « Annuler » says so.
                val hidden = pending?.context?.id
                _uiState.value = _uiState.value.copy(
                    contexts = contexts.filterNot { it.id == hidden },
                    isLoading = false,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Threads not loaded: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    error = e.message,
                    isLoading = false,
                )
            }
        }
    }

    fun handleStreamUpdate(context: Context) {
        // Deleted on screen and waiting for its « Annuler » window: not to come back through here.
        if (pending?.context?.id == context.id) return
        val current = _uiState.value.contexts.toMutableList()
        val index = current.indexOfFirst { it.id == context.id }
        if (index >= 0) {
            // The stream says the label and the status, not how many messages the thread holds.
            current[index] = context.copy(messageCount = current[index].messageCount)
        } else {
            current.add(0, context)
        }
        _uiState.value = _uiState.value.copy(contexts = current)
    }

    /** « Supprimer » on a row: ask first, since the thread takes its messages with it. */
    fun requestDeletion(context: Context) {
        _uiState.value = _uiState.value.copy(deletionToConfirm = context)
    }

    fun cancelDeletionRequest() {
        _uiState.value = _uiState.value.copy(deletionToConfirm = null)
    }

    /**
     * Confirmed: the thread leaves the list at once, but the server hears nothing until
     * [UNDO_WINDOW_MS] has passed — « Annuler » then costs nothing, not even a round trip.
     */
    fun confirmDeletion() {
        val context = _uiState.value.deletionToConfirm ?: return
        // One undo at a time: a second deletion makes the first final.
        pending?.let { previous ->
            if (!previous.committing) {
                previous.timer?.cancel()
                viewModelScope.launch { commit(previous) }
            }
        }

        val index = _uiState.value.contexts.indexOfFirst { it.id == context.id }.coerceAtLeast(0)
        val deletion = PendingDeletion(context, index)
        pending = deletion
        _uiState.value = _uiState.value.copy(
            contexts = _uiState.value.contexts.filterNot { it.id == context.id },
            deletionToConfirm = null,
            undoableDeletion = context,
            deleteFailed = false,
        )
        _threadEvents.tryEmit(ThreadEvent.Hidden(context.id))
        deletion.timer = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            commit(deletion)
        }
    }

    /** « Annuler »: the thread is back where it was, and the server never knew. */
    fun undoDeletion() {
        val deletion = pending?.takeIf { !it.committing } ?: return
        deletion.timer?.cancel()
        pending = null
        _uiState.value = _uiState.value.copy(undoableDeletion = null)
        restore(deletion)
    }

    fun consumeDeleteFailed() {
        _uiState.value = _uiState.value.copy(deleteFailed = false)
    }

    private suspend fun commit(deletion: PendingDeletion) {
        deletion.committing = true
        if (pending === deletion) {
            pending = null
            _uiState.value = _uiState.value.copy(undoableDeletion = null)
        }
        contextRepository.deleteContext(deletion.context.id)
            .onSuccess { _threadEvents.tryEmit(ThreadEvent.Deleted(deletion.context.id)) }
            .onFailure { e ->
                Log.w(TAG, "Thread ${deletion.context.id} not deleted: ${e.message}")
                restore(deletion)
                _uiState.value = _uiState.value.copy(deleteFailed = true)
            }
    }

    private fun restore(deletion: PendingDeletion) {
        val current = _uiState.value.contexts
        if (current.none { it.id == deletion.context.id }) {
            val restored = current.toMutableList()
            restored.add(deletion.index.coerceAtMost(restored.size), deletion.context)
            _uiState.value = _uiState.value.copy(contexts = restored)
        }
        _threadEvents.tryEmit(ThreadEvent.Restored(deletion.context.id))
    }

    /** Deleted elsewhere (another device, the admin), or by our own commit coming back. */
    private fun applyDeleted(id: String) {
        val own = pending?.takeIf { it.context.id == id }
        if (own != null) {
            // Nothing left to undo, nothing left to send.
            own.timer?.cancel()
            pending = null
        }
        val state = _uiState.value
        _uiState.value = state.copy(
            contexts = state.contexts.filterNot { it.id == id },
            deletionToConfirm = state.deletionToConfirm?.takeIf { it.id != id },
            undoableDeletion = state.undoableDeletion?.takeIf { it.id != id },
        )
        _threadEvents.tryEmit(ThreadEvent.Deleted(id))
    }

    /** The id of a `{"id": …, "deleted": true}` payload, `null` for any other event. */
    private fun deletedId(data: String): String? = runCatching {
        val payload = json.parseToJsonElement(data).jsonObject
        if (payload["deleted"]?.jsonPrimitive?.booleanOrNull == true) payload["id"]?.jsonPrimitive?.contentOrNull else null
    }.getOrNull()

    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.agentScoped(userId, MercureTopics.CONTEXTS))
                .catch { /* SSE reconnects automatically */ }
                .collect { event ->
                    val deleted = deletedId(event.data)
                    if (deleted != null) applyDeleted(deleted) else refresh()
                }
        }
    }
}
