package com.maggie.app.ui.screens.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.ApprovalDecisionException
import com.maggie.app.data.api.ChatImage
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.AgUiEvent
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.MessageFeedback
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.data.repository.ApprovalRepository
import com.maggie.app.data.repository.ChatPreferencesRepository
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.ui.components.approvalQuestion
import com.maggie.app.ui.screens.contexts.ThreadEvent
import com.maggie.app.util.ChatDateFormatter
import com.maggie.app.voice.ScreenContext
import com.maggie.app.voice.SpokenApprovalAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ScrollBehavior { NONE, ANIMATE_TO_BOTTOM, INSTANT_TO_INDEX }

enum class ApprovalDecision { APPROVE, DENY }

/**
 * An approval as the chat shows it. [decision] is the answer sent and not yet
 * confirmed — the optimistic state, in which the card is locked; [error] says the
 * last attempt did not reach the agent and the card is answerable again.
 */
data class ApprovalItem(
    val approval: PendingApproval,
    val decision: ApprovalDecision? = null,
    val error: Boolean = false,
)

data class ChatUiState(
    val displayItems: List<ChatListItem> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingHistory: Boolean = false,
    val hasMoreHistory: Boolean = true,
    val isSearchMode: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<ChatMessage> = emptyList(),
    val isSearchLoading: Boolean = false,
    val highlightedMessageId: String? = null,
    val tappedMessageId: String? = null,
    val unreadFromId: String? = null,
    val scrollToIndex: Int? = null,
    val scrollBehavior: ScrollBehavior = ScrollBehavior.NONE,
    val streamingText: String = "",
    val streamingMessageId: String? = null,
    /** The answer to a request made in this session, waiting to be read aloud (MAG-227). */
    val replyToSpeak: ChatMessage? = null,
    /** Actions Maggie holds until the user answers, oldest first (MAG-4). */
    val pendingApprovals: List<ApprovalItem> = emptyList(),
    /** What Maggie is doing, for the Mind tab (MAG-403). */
    val agentState: AgentState = AgentState.IDLE,
    /** The tool calls of the open session, oldest first: the turn in progress and the last ones before it. */
    val toolCalls: List<ToolCallItem> = emptyList(),
    /**
     * Why the last question got no answer: a mention under the question with a « Réessayer »
     * (MAG-363) — information about the message, never a bubble of Maggie's, and never stored.
     */
    val failure: String? = null,
    /**
     * The screenshots sent from this device, as JPEG bytes, by message id (MAG-214).
     * Memory only: the image is stored nowhere, so after a reload a bubble only
     * has [ChatMessage.hasImage] to go on.
     */
    val thumbnails: Map<String, ByteArray> = emptyMap(),
    /** A message gone from the chat that « Annuler » still brings back; nothing was sent yet (MAG-342). */
    val undoableMessageDeletion: ChatMessage? = null,
    /** The server did not delete it: the message is back, say so once. */
    val messageDeleteFailed: Boolean = false,
    /** Maggie is preparing or writing her answer: the send button is an « Arrêter » (MAG-320). */
    val answering: Boolean = false,
    /** The last message of the user, the only one « Modifier » is offered on (MAG-320). */
    val editableMessageId: String? = null,
    /** Text for the input to take, cursor at its end, then [ChatViewModel.consumeInputToRestore]. */
    val inputToRestore: String? = null,
    /** The server did not take a thumb: the answer shows its previous rating again, say so once (MAG-23). */
    val messageFeedbackFailed: Boolean = false,
    /**
     * The answer whose thumb down was just recorded and which is now offered an optional word on
     * why (MAG-23); `null` once it is sent or skipped. The comment dialog renders from it.
     */
    val commentingMessageId: String? = null,
) {
    /** The badge of the Mind tab: the held actions that can still be answered. */
    val awaitingApprovalCount: Int get() = pendingApprovals.count { it.isAwaitingAnswer() }
}

@OptIn(FlowPreview::class)
class ChatViewModel(
    private val repository: ChatRepository,
    private val mercureService: MercureService,
    private val chatPrefsRepository: ChatPreferencesRepository,
    private val authRepository: AuthRepository,
    private val approvalRepository: ApprovalRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    private val askedApprovals = mutableSetOf<String>()
    val uiState: StateFlow<ChatUiState> = _uiState

    private val _contextUpdates = MutableSharedFlow<AgUiEvent.ContextUpdate>(extraBufferCapacity = 16)
    val contextUpdates: SharedFlow<AgUiEvent.ContextUpdate> = _contextUpdates

    private val json = Json { ignoreUnknownKeys = true }
    private val searchQueryFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private var highlightJob: Job? = null

    /**
     * Raised by [sendMessage], lowered when its answer is in (or the request
     * failed, or [dropPendingReply]). It is the only thing that lets an answer
     * be read aloud: `isLoading` is not, because it is also up while the
     * history loads, and history is never read.
     */
    private var awaitingReply = false

    /**
     * What [retry] sends again: the same text, the same key, so the agent answers a message once.
     * The screenshot goes again with it — the same turn, never answered (MAG-214) — and is
     * dropped with the attempt once the question is answered.
     */
    private data class Attempt(
        val text: String,
        val screenContext: String?,
        val key: String,
        val anchor: String?,
        val pendingId: String,
        val image: ChatImage?,
        val replaces: String?,
    )

    private var unanswered: Attempt? = null

    /**
     * The message the next send corrects (MAG-320). [withdrawn]: the answer to it was stopped, so it
     * and what follows are hidden until the correction is sent, or given up and shown again.
     */
    private data class Correction(val messageId: String, val withdrawn: Boolean)

    private var correction: Correction? = null

    // The request in flight, so that cutting Maggie off can stop it (MAG-223).
    private var sendJob: Job? = null
    private var interruptJob: Job? = null

    // The answer last handed to the voice, so cutting it off can say which one.
    private var spokenReply: ChatMessage? = null

    /** A confirmed message deletion waiting out its « Annuler » window. At most one at a time. */
    private class PendingDeletion(val message: ChatMessage, val index: Int) {
        var timer: Job? = null
        var committing = false
    }

    private var pendingDeletion: PendingDeletion? = null

    /**
     * Threads the owner deleted and may still take back: their messages are kept but not
     * shown (MAG-342). The conversation is one list, the threads are only how it is filed.
     */
    private val hiddenContextIds = mutableSetOf<String>()

    /**
     * The thread the router chose for the run in flight, and the messages of that run
     * (MAG-342). The agent files them under it, but the stream carries no thread on a
     * message: without this, the current exchange has no `contextId` until a reload and
     * deleting its thread would neither hide it nor bring it back with « Annuler ».
     */
    private var runContextId: String? = null
    private val runMessageIds = mutableSetOf<String>()

    companion object {
        /** How long « Message supprimé · Annuler » stays, and so how long the server hears nothing. */
        const val UNDO_WINDOW_MS = 6_000L
        private const val PAGE_SIZE = 20
        private const val ACTIVITY_HISTORY = 10
        private const val TAG = "ChatViewModel"
        private const val PENDING_PREFIX = "pending_"
        private const val RECOVERY_WINDOW_MS = 60_000L
        private const val RECOVERY_POLL_MS = 3_000L
        private const val NO_ANSWER = "Maggie n'a pas pu répondre."
    }

    init {
        loadInitialMessages()
        subscribeToChatUpdates()
        observeSearchQuery()
        subscribeToApprovals()
        loadPendingApprovals()
    }

    private fun loadInitialMessages() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val messages = repository.loadRecentMessages(PAGE_SIZE).asSaid()
                val lastReadId = chatPrefsRepository.getLastReadMessageId()
                val unreadFromId = resolveUnreadFromId(messages, lastReadId)
                _uiState.value = _uiState.value.copy(
                    messages = messages,
                    isLoading = false,
                    hasMoreHistory = messages.size >= PAGE_SIZE,
                    unreadFromId = unreadFromId,
                )
                rebuildDisplayItems()
                // Scroll to bottom on initial load
                scrollToBottom(animate = false)
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    /**
     * What the user said, without the screen-context block the assistant may
     * have attached to it (MAG-30). The agent no longer stores one — the
     * context travels in its own field — but the exchanges recorded before
     * that fix still carry it, and so would a message sent from a copy of the
     * app that predates it. Applied wherever a message enters the UI.
     */
    private fun ChatMessage.asSaid(): ChatMessage =
        if (role == "user") copy(content = ScreenContext.withoutPromptBlock(content)) else this

    private fun List<ChatMessage>.asSaid(): List<ChatMessage> = map { it.asSaid() }

    /** The pending bubble's thumbnail follows it when it takes its stored id. */
    private fun Map<String, ByteArray>.rekeyed(from: String, to: String?): Map<String, ByteArray> {
        val bytes = this[from] ?: return this
        return if (to == null) this - from else this - from + (to to bytes)
    }

    private fun resolveUnreadFromId(messages: List<ChatMessage>, lastReadId: String?): String? {
        if (lastReadId == null) return null
        val lastReadIndex = messages.indexOfFirst { it.id == lastReadId }
        if (lastReadIndex == -1 || lastReadIndex >= messages.size - 1) return null
        // The first unread message is the one after lastRead
        return messages[lastReadIndex + 1].id
    }

    fun loadOlderMessages() {
        val state = _uiState.value
        if (state.isLoadingHistory || !state.hasMoreHistory || state.messages.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingHistory = true)
            try {
                val oldestId = state.messages.first().id
                val older = repository.loadOlderMessages(oldestId, PAGE_SIZE).asSaid()
                if (older.isNotEmpty()) {
                    val merged = older + _uiState.value.messages
                    _uiState.value = _uiState.value.copy(
                        messages = merged,
                        isLoadingHistory = false,
                        hasMoreHistory = older.size >= PAGE_SIZE,
                    )
                    rebuildDisplayItems()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoadingHistory = false,
                        hasMoreHistory = false,
                    )
                }
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingHistory = false)
            }
        }
    }

    /**
     * [screenContext] is the block describing the screen the assistant was
     * summoned from (MAG-30). It travels in its own field, beside the message:
     * glued to it, it was what the agent stored, and the conversation then
     * showed the page instead of the question — on reload, through Mercure and
     * in the web chat alike. The model still gets it; nobody reads it.
     *
     * [image] is the screenshot that goes with it, as JPEG bytes (MAG-214): sent for
     * this turn, and kept here only to draw the bubble's thumbnail.
     */
    fun sendMessage(text: String, screenContext: String? = null, image: ByteArray? = null) {
        if (text.isBlank()) return
        awaitingReply = true
        val key = java.util.UUID.randomUUID().toString()
        spokenReply = null

        sendJob = viewModelScope.launch {
            // What the agent is told of the stopped answer must have arrived before it is removed.
            interruptJob?.join()
            resolvePendingCorrection()
            // A message that still has no stored id cannot be named to the agent: nothing is replaced.
            val replaces = correction?.messageId?.takeUnless { it.startsWith(PENDING_PREFIX) }
            if (replaces != null) dropFrom(replaces)
            correction = null
            // Add optimistic user message
            val userMessage = ChatMessage(
                id = "$PENDING_PREFIX${System.currentTimeMillis()}",
                role = "user",
                content = text,
                createdAt = java.time.Instant.now().toString(),
                hasImage = image != null,
            )
            val anchor = _uiState.value.messages.lastOrNull { !it.id.startsWith(PENDING_PREFIX) }?.createdAt
            runContextId = null
            runMessageIds.clear()
            runMessageIds += userMessage.id
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + userMessage,
                failure = null,
                thumbnails = if (image != null) {
                    _uiState.value.thumbnails + (userMessage.id to image)
                } else {
                    _uiState.value.thumbnails
                },
                isLoading = true,
                answering = true,
                streamingText = "",
                streamingMessageId = null,
                replyToSpeak = null,
            )
            rebuildDisplayItems()
            scrollToBottom(animate = true)

            // The agent builds this turn's history from what it stored of the last one: it must
            // know the last answer was cut before it reads the new question.
            interruptJob?.join()

            val attached = image?.let { ChatImage.jpeg(it) }
            deliver(Attempt(text, screenContext?.takeIf { it.isNotBlank() }, key, anchor, userMessage.id, attached, replaces))
        }
    }

    /**
     * The stored id of the question being corrected comes back through Mercure, which a stopped
     * request may not have waited for: ask the server for it, or the old exchange would stay.
     */
    private suspend fun resolvePendingCorrection() {
        val pendingId = correction?.messageId?.takeIf { it.startsWith(PENDING_PREFIX) } ?: return
        val messages = _uiState.value.messages
        val anchor = messages.take(messages.indexOfFirst { it.id == pendingId }.coerceAtLeast(0))
            .lastOrNull { !it.id.startsWith(PENDING_PREFIX) }?.createdAt
        try {
            mergeStored(repository.fetchMessagesAfter(anchor).asSaid())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch the stored question: ${e::class.simpleName}: ${e.message}")
        }
    }

    /**
     * « Réessayer » under a question left without an answer: the same text under the same key,
     * and no new bubble — the question is already on screen.
     */
    fun retry() {
        val attempt = unanswered ?: return
        if (_uiState.value.failure == null) return
        awaitingReply = true
        spokenReply = null

        sendJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                failure = null,
                isLoading = true,
                answering = true,
                streamingText = "",
                streamingMessageId = null,
                replyToSpeak = null,
            )
            rebuildDisplayItems()
            scrollToBottom(animate = true)
            deliver(attempt)
        }
    }

    private suspend fun deliver(attempt: Attempt) {
        unanswered = attempt
        val (text, screen, key, anchor, pendingId, image, replaces) = attempt
        try {
            var streamError: String? = null
            repository.sendMessageStream(text, screen, key, image, replaces)
                .collect { event ->
                    if (event is AgUiEvent.Error) streamError = event.message
                    handleStreamEvent(event)
                }
            if (awaitingReply) {
                val answered = _uiState.value.messages.lastOrNull { !it.isDecision }?.role == "assistant"
                if (answered && streamError == null) finishRequest() else recoverReply(anchor, RECOVERY_WINDOW_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Stream failed, falling back to non-streaming: ${e::class.simpleName}: ${e.message}")
            // Fallback to non-streaming
            try {
                val newMessages = repository.sendMessage(text, screen, image).asSaid()
                if (newMessages.isNotEmpty()) {
                    // Replace optimistic user message with server response
                    val current = _uiState.value.messages.dropLast(1)
                    _uiState.value = _uiState.value.copy(
                        messages = current + newMessages,
                        thumbnails = _uiState.value.thumbnails.rekeyed(
                            pendingId,
                            newMessages.firstOrNull { it.role == "user" }?.id,
                        ),
                        streamingText = "",
                        streamingMessageId = null,
                    )
                    finishRequest()
                    rebuildDisplayItems()
                } else {
                    fail()
                }
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                Log.w(TAG, "Non-streaming send failed: ${e2::class.simpleName}: ${e2.message}")
                // The call may have been cut after the agent stored its answer: look once before giving up.
                recoverReply(anchor, 0)
            }
        }
    }

    /**
     * The call that carried the question is gone, but the agent stores its answer whether or not
     * the phone was still listening: ask the server for it until [window] has passed, then say so.
     */
    private suspend fun recoverReply(anchor: String?, window: Long) {
        _uiState.value = _uiState.value.copy(streamingText = "", streamingMessageId = null)
        var waited = 0L
        while (true) {
            try {
                mergeStored(repository.fetchMessagesAfter(anchor).asSaid())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch the answer: ${e::class.simpleName}: ${e.message}")
            }
            if (_uiState.value.messages.lastOrNull { !it.isDecision }?.role == "assistant") {
                finishRequest()
                rebuildDisplayItems()
                return
            }
            if (waited >= window) break
            delay(RECOVERY_POLL_MS)
            waited += RECOVERY_POLL_MS
        }
        fail()
    }

    private fun mergeStored(stored: List<ChatMessage>) {
        var messages = _uiState.value.messages
        var thumbnails = _uiState.value.thumbnails
        for (message in stored) {
            if (messages.any { it.id == message.id }) continue
            val pendingIndex = if (message.role == "user") {
                messages.indexOfFirst { it.id.startsWith(PENDING_PREFIX) && it.content == message.content }
            } else {
                -1
            }
            messages = if (pendingIndex >= 0) {
                correction?.takeIf { it.messageId == messages[pendingIndex].id }
                    ?.let { correction = it.copy(messageId = message.id) }
                thumbnails = thumbnails.rekeyed(messages[pendingIndex].id, message.id)
                messages.mapIndexed { i, m -> if (i == pendingIndex) message else m }
            } else {
                messages + message
            }
        }
        _uiState.value = _uiState.value.copy(messages = messages, thumbnails = thumbnails)
        rebuildDisplayItems()
    }

    /** A call still running when its turn is cut short will never report: it must not spin for ever. */
    private fun List<ToolCallItem>.stopped(): List<ToolCallItem> =
        map { if (it.status == ToolCallStatus.RUNNING) it.copy(status = ToolCallStatus.ERROR) else it }

    private fun fail() {
        awaitingReply = false
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            answering = false,
            streamingText = "",
            streamingMessageId = null,
            failure = NO_ANSWER,
            agentState = AgentState.IDLE,
            toolCalls = _uiState.value.toolCalls.stopped(),
        )
        rebuildDisplayItems()
    }

    /**
     * The user cut Maggie off (MAG-223): stop the request, keep of her answer what was heard or
     * shown, and tell the agent so the next turn knows. [heard] is what the voice got through
     * (null while the answer was still being prepared); [streamingIsShown] is false where the
     * answer being written is not on screen, so nothing of it was seen either.
     */
    fun interrupt(heard: String? = null, streamingIsShown: Boolean = true) {
        val state = _uiState.value
        val reply = state.replyToSpeak
        val spoken = spokenReply
        val (messageId, kept) = when {
            awaitingReply -> state.streamingMessageId to (heard ?: if (streamingIsShown) state.streamingText else "")
            reply != null -> reply.id to (heard ?: "")
            spoken != null -> spoken.id to (heard ?: spoken.content)
            else -> return
        }

        sendJob?.cancel()
        sendJob = null
        awaitingReply = false
        spokenReply = null
        val messages = if (messageId != null && kept.isNotBlank()) {
            state.messages.withMessage(
                ChatMessage(id = messageId, role = "assistant", content = kept, createdAt = java.time.Instant.now().toString()),
            )
        } else {
            state.messages
        }
        _uiState.value = state.copy(
            messages = messages,
            isLoading = false,
            answering = false,
            streamingText = "",
            streamingMessageId = null,
            replyToSpeak = null,
            agentState = AgentState.IDLE,
            toolCalls = state.toolCalls.stopped(),
        )
        rebuildDisplayItems()

        interruptJob = viewModelScope.launch {
            try {
                val stored = repository.interruptChat(messageId, kept)
                _uiState.value = _uiState.value.copy(messages = _uiState.value.messages.withMessage(stored))
                rebuildDisplayItems()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not record the interruption: ${e.message}")
            }
        }
    }

    private fun List<ChatMessage>.withMessage(message: ChatMessage): List<ChatMessage> =
        if (any { it.id == message.id }) map { if (it.id == message.id) message.copy(createdAt = it.createdAt) else it } else this + message

    /**
     * « Arrêter » of the written chat (MAG-320): the answer is cut like a voice reply is, the question
     * and the interrupted answer leave the thread, and the question comes back in the input to be
     * corrected. The agent is told even if the phone could not be heard saying so: the words are back
     * in the input either way.
     */
    fun stop() {
        if (!awaitingReply) return
        val question = _uiState.value.messages.lastOrNull { it.role == "user" }
        if (question != null) {
            correction = Correction(question.id, withdrawn = true)
            _uiState.value = _uiState.value.copy(inputToRestore = question.content)
        }
        unanswered = null
        interrupt()
    }

    /** « Modifier » on the last message of the user: its words go back in the input, and the next send replaces it. */
    fun editMessage(id: String) {
        val last = _uiState.value.messages.lastOrNull { it.role == "user" }
        if (last == null || last.id != id) return
        if (awaitingReply) {
            stop()
            return
        }
        correction = Correction(last.id, withdrawn = false)
        _uiState.value = _uiState.value.copy(inputToRestore = last.content)
    }

    fun consumeInputToRestore() {
        _uiState.value = _uiState.value.copy(inputToRestore = null)
    }

    /** An emptied input gives the correction up: what was stopped stays in the thread, as it is on the server. */
    fun onInputChanged(text: String) {
        if (text.isNotEmpty() || correction == null) return
        correction = null
        rebuildDisplayItems()
    }

    /** The agent takes [userMessageId] and its answer off the conversation: so does the thread, at once. */
    private fun dropFrom(userMessageId: String) {
        val messages = _uiState.value.messages
        val index = messages.indexOfFirst { it.id == userMessageId }
        if (index < 0) return
        removeMessages(messages.drop(index).map { it.id }.toSet(), null)
    }

    private suspend fun handleStreamEvent(event: AgUiEvent) {
        when (event) {
            is AgUiEvent.TextMessageStart -> {
                _uiState.value = _uiState.value.copy(
                    streamingMessageId = event.messageId,
                    streamingText = "",
                    agentState = AgentState.THINKING,
                )
                rebuildDisplayItems()
            }
            is AgUiEvent.TextMessageContent -> {
                _uiState.value = _uiState.value.copy(
                    streamingText = _uiState.value.streamingText + event.delta,
                )
                rebuildDisplayItems()
            }
            is AgUiEvent.TextMessageEnd -> {
                val finalText = _uiState.value.streamingText
                val messageId = _uiState.value.streamingMessageId ?: event.messageId
                val assistantMessage = ChatMessage(
                    id = messageId,
                    role = "assistant",
                    content = finalText,
                    createdAt = java.time.Instant.now().toString(),
                    contextId = runContextId,
                )
                runMessageIds += messageId
                // Persist to Room
                repository.persistMessage(assistantMessage)
                // The agent stores the answer under the id it streamed it with, so its
                // Mercure echo — which can land before this point — is the same message.
                val alreadyThere = _uiState.value.messages.any { it.id == messageId }
                _uiState.value = _uiState.value.copy(
                    messages = if (alreadyThere) _uiState.value.messages else _uiState.value.messages + assistantMessage,
                    streamingText = "",
                    streamingMessageId = null,
                )
                rebuildDisplayItems()
            }
            is AgUiEvent.RunFinished -> {
                finishRequest()
                rebuildDisplayItems()
            }
            is AgUiEvent.ContextUpdate -> {
                _contextUpdates.tryEmit(event)
                fileRunUnder(event.id)
            }
            is AgUiEvent.Error -> {
                // Not the end of the request: the answer may be stored, see [recoverReply].
                Log.w(TAG, "Stream error: ${event.message}")
            }
            is AgUiEvent.RunStarted -> {
                // Only the finished calls of earlier turns stay as history, the last ones.
                _uiState.value = _uiState.value.copy(
                    agentState = AgentState.THINKING,
                    toolCalls = _uiState.value.toolCalls
                        .filter { it.status != ToolCallStatus.RUNNING }
                        .takeLast(ACTIVITY_HISTORY),
                )
            }
            is AgUiEvent.ToolCallStart -> {
                _uiState.value = _uiState.value.copy(
                    agentState = AgentState.ACTING,
                    toolCalls = _uiState.value.toolCalls +
                        ToolCallItem(event.toolCallId, event.toolName, ToolCallStatus.RUNNING),
                )
            }
            is AgUiEvent.ToolCallEnd -> {
                // The outcome comes with the ToolResult; until then a call that ended is a success.
                _uiState.value = _uiState.value.copy(
                    agentState = AgentState.THINKING,
                    toolCalls = _uiState.value.toolCalls.map {
                        if (it.id == event.toolCallId && it.status == ToolCallStatus.RUNNING) {
                            it.copy(status = ToolCallStatus.SUCCESS)
                        } else {
                            it
                        }
                    },
                )
            }
            is AgUiEvent.ToolResult -> {
                val status = ToolCallStatus.fromWire(event.status) ?: return
                _uiState.value = _uiState.value.copy(
                    toolCalls = _uiState.value.toolCalls.map {
                        if (it.id == event.toolCallId) it.copy(status = status) else it
                    },
                )
            }
        }
    }

    /**
     * The router filed this run under [contextId]: the question and the answer already
     * on screen, and those still to come, belong to it. The local copy is told too, so a
     * restart does not forget it.
     */
    private suspend fun fileRunUnder(contextId: String) {
        runContextId = contextId
        val filed = _uiState.value.messages.filter { it.id in runMessageIds && it.contextId == null }
        if (filed.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.map {
                if (it.id in runMessageIds && it.contextId == null) it.copy(contextId = contextId) else it
            },
        )
        rebuildDisplayItems()
        filed.filterNot { it.id.startsWith(PENDING_PREFIX) }
            .forEach { repository.persistMessage(it.copy(contextId = contextId)) }
    }

    /** The request is over: its answer, if any, is now the thing to read aloud. */
    private fun finishRequest() {
        val reply = if (awaitingReply) _uiState.value.messages.lastOrNull { !it.isDecision }?.takeIf { it.role == "assistant" } else null
        awaitingReply = false
        unanswered = null
        _uiState.value = _uiState.value.copy(isLoading = false, answering = false, replyToSpeak = reply, agentState = AgentState.IDLE)
    }

    /** Called by whoever read [ChatUiState.replyToSpeak]: it is offered once. */
    fun onReplySpoken() {
        spokenReply = _uiState.value.replyToSpeak
        _uiState.value = _uiState.value.copy(replyToSpeak = null)
    }

    /** The surface that would have spoken is gone: an answer arriving now stays silent. */
    fun dropPendingReply() {
        awaitingReply = false
        spokenReply = null
        _uiState.value = _uiState.value.copy(replyToSpeak = null, answering = false)
    }

    fun openSearch() {
        _uiState.value = _uiState.value.copy(
            isSearchMode = true,
            searchQuery = "",
            searchResults = emptyList(),
        )
    }

    fun closeSearch() {
        _uiState.value = _uiState.value.copy(
            isSearchMode = false,
            searchQuery = "",
            searchResults = emptyList(),
            isSearchLoading = false,
        )
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchQueryFlow.tryEmit(query)
    }

    private fun observeSearchQuery() {
        viewModelScope.launch {
            searchQueryFlow
                .debounce(300)
                .collect { query ->
                    if (query.length >= 2) {
                        _uiState.value = _uiState.value.copy(isSearchLoading = true)
                        try {
                            val results = repository.searchMessages(query).asSaid()
                            _uiState.value = _uiState.value.copy(
                                searchResults = results,
                                isSearchLoading = false,
                            )
                        } catch (_: Exception) {
                            _uiState.value = _uiState.value.copy(isSearchLoading = false)
                        }
                    } else {
                        _uiState.value = _uiState.value.copy(
                            searchResults = emptyList(),
                            isSearchLoading = false,
                        )
                    }
                }
        }
    }

    fun navigateToMessage(messageId: String) {
        viewModelScope.launch {
            closeSearch()
            // Check if the message is already in our list
            val existingIndex = _uiState.value.messages.indexOfFirst { it.id == messageId }
            if (existingIndex >= 0) {
                highlightMessage(messageId)
                return@launch
            }
            // Load context around the message
            try {
                val context = repository.loadMessageContext(messageId).asSaid()
                if (context.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        messages = context,
                        hasMoreHistory = true,
                    )
                    rebuildDisplayItems()
                    highlightMessage(messageId)
                }
            } catch (_: Exception) {
                // Silently fail
            }
        }
    }

    private fun highlightMessage(messageId: String) {
        _uiState.value = _uiState.value.copy(highlightedMessageId = messageId)
        rebuildDisplayItems()
        // Scroll to the highlighted message
        val index = _uiState.value.displayItems.indexOfFirst {
            it is ChatListItem.MessageItem && it.message.id == messageId
        }
        if (index >= 0) {
            _uiState.value = _uiState.value.copy(
                scrollToIndex = index,
                scrollBehavior = ScrollBehavior.INSTANT_TO_INDEX,
            )
        }
        // Clear highlight after 2 seconds
        highlightJob?.cancel()
        highlightJob = viewModelScope.launch {
            delay(2000)
            _uiState.value = _uiState.value.copy(highlightedMessageId = null)
            rebuildDisplayItems()
        }
    }

    fun onMessageTapped(messageId: String) {
        val current = _uiState.value.tappedMessageId
        _uiState.value = _uiState.value.copy(
            tappedMessageId = if (current == messageId) null else messageId,
        )
        rebuildDisplayItems()
    }

    fun onScrolledToBottom() {
        val state = _uiState.value
        if (state.unreadFromId != null) {
            markAllRead()
        }
    }

    fun markAllRead() {
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return
        val lastId = messages.last().id
        _uiState.value = _uiState.value.copy(unreadFromId = null)
        rebuildDisplayItems()
        viewModelScope.launch {
            chatPrefsRepository.saveLastReadMessageId(lastId)
        }
    }

    fun consumeScroll() {
        _uiState.value = _uiState.value.copy(
            scrollToIndex = null,
            scrollBehavior = ScrollBehavior.NONE,
        )
    }

    private fun scrollToBottom(animate: Boolean) {
        val lastIndex = _uiState.value.displayItems.size - 1
        if (lastIndex >= 0) {
            _uiState.value = _uiState.value.copy(
                scrollToIndex = lastIndex,
                scrollBehavior = if (animate) ScrollBehavior.ANIMATE_TO_BOTTOM else ScrollBehavior.INSTANT_TO_INDEX,
            )
        }
    }

    fun rebuildDisplayItems() {
        val state = _uiState.value
        val items = mutableListOf<ChatListItem>()
        // The answer stopped and its question wait to be replaced: the echo of the interrupted
        // answer, which arrives after them, must not bring them back.
        val withdrawnFrom = correction?.takeIf { it.withdrawn }
            ?.let { c -> state.messages.indexOfFirst { it.id == c.messageId } }
            ?.takeIf { it >= 0 }
        val kept = if (withdrawnFrom != null) state.messages.take(withdrawnFrom) else state.messages
        val messages = if (hiddenContextIds.isEmpty()) {
            kept
        } else {
            kept.filterNot { it.contextId != null && it.contextId in hiddenContextIds }
        }
        val tappedId = state.tappedMessageId
        val highlightedId = state.highlightedMessageId
        val unreadFromId = state.unreadFromId

        for (i in messages.indices) {
            val msg = messages[i]
            val prevCreatedAt = if (i > 0) messages[i - 1].createdAt else null

            // Date/time separator
            val separatorLabel = ChatDateFormatter.getTimeSeparatorLabel(prevCreatedAt, msg.createdAt)
            if (separatorLabel != null) {
                items.add(ChatListItem.DateSeparator(separatorLabel))
            }

            // Unread divider
            if (unreadFromId != null && msg.id == unreadFromId) {
                items.add(ChatListItem.UnreadDivider)
            }

            // Tapped timestamp (above the message)
            if (tappedId == msg.id) {
                val instant = ChatDateFormatter.parseIso(msg.createdAt)
                if (instant != null) {
                    items.add(ChatListItem.DateSeparator(ChatDateFormatter.formatDayAndTime(instant)))
                }
            }

            items.add(
                ChatListItem.MessageItem(
                    message = msg,
                    isHighlighted = highlightedId == msg.id,
                    thumbnail = state.thumbnails[msg.id],
                ),
            )
        }

        // Streaming message (shows partial text while assistant is responding)
        if (state.streamingText.isNotEmpty()) {
            items.add(ChatListItem.StreamingMessage(state.streamingText))
        } else if (state.isLoading) {
            items.add(ChatListItem.LoadingIndicator)
        }
        state.failure?.let { items.add(ChatListItem.Failure(it)) }

        _uiState.value = _uiState.value.copy(
            displayItems = items,
            editableMessageId = messages.lastOrNull { it.role == "user" }?.id,
        )
    }

    /** Locks the card at once, then confirms with the agent; a failure unlocks it with an error. */
    fun approve(id: String) = decide(id, ApprovalDecision.APPROVE)

    fun deny(id: String) = decide(id, ApprovalDecision.DENY)

    /**
     * What was said to the overlay while an action waits: a clear yes or no answers the
     * oldest card still open (MAG-310), anything else is left to be a normal message.
     * Returns whether the sentence was taken as an answer — the caller sends it to Maggie
     * otherwise.
     */
    fun answerApprovalByVoice(text: String): Boolean {
        val target = _uiState.value.pendingApprovals
            .firstOrNull { it.approval.isPending && it.decision == null } ?: return false
        // A « oui » answers a question that was heard: a card nobody read out yet is not it.
        if (target.approval.id !in askedApprovals) return false
        val decision = SpokenApprovalAnswer.parse(text) ?: return false
        decide(target.approval.id, decision, channel = "voice")
        return true
    }

    /** The question Maggie asks aloud for a held action, with what its arguments cannot say. */
    suspend fun questionFor(approval: PendingApproval): String =
        approvalQuestion(approval, approvalRepository.describe(approval))

    fun isApprovalAsked(id: String): Boolean = id in askedApprovals

    fun markApprovalAsked(id: String) {
        askedApprovals += id
        // The question about to be spoken replaces the last reply as what a tap on the mic cuts.
        spokenReply = null
    }

    /** Drops a card that stays on screen once settled — a failed action — when the user closes it. */
    fun dismissApproval(id: String) {
        _uiState.value = _uiState.value.copy(
            pendingApprovals = _uiState.value.pendingApprovals.filterNot { it.approval.id == id },
        )
    }

    private fun decide(id: String, decision: ApprovalDecision, channel: String = "chat") {
        val item = _uiState.value.pendingApprovals.firstOrNull { it.approval.id == id } ?: return
        if (item.decision != null || !item.approval.isPending) return

        updateApproval(id) { it.copy(decision = decision, error = false) }
        viewModelScope.launch {
            val outcome = when (decision) {
                ApprovalDecision.APPROVE -> approvalRepository.approve(id, channel)
                ApprovalDecision.DENY -> approvalRepository.deny(id, channel)
            }
            outcome
                .onSuccess { confirmed -> applyApproval(confirmed) }
                .onFailure { e ->
                    Log.w(TAG, "Approval $id not ${decision.name.lowercase()}d: ${e.message}")
                    if (e is ApprovalDecisionException && e.isFinal) {
                        // Decided elsewhere, expired or not ours: there is nothing left to answer.
                        dismissApproval(id)
                    } else {
                        updateApproval(id) { it.copy(decision = null, error = true) }
                    }
                }
        }
    }

    private fun updateApproval(id: String, change: (ApprovalItem) -> ApprovalItem) {
        _uiState.value = _uiState.value.copy(
            pendingApprovals = _uiState.value.pendingApprovals.map {
                if (it.approval.id == id) change(it) else it
            },
        )
    }

    /**
     * Folds in what the agent says about an approval, from a decision's answer or from
     * Mercure. Only a pending action and a failed one are worth a card: an approved or
     * denied one is over — Maggie's own message in the chat says what it did — and an
     * expired one can no longer be answered.
     */
    private fun applyApproval(approval: PendingApproval) {
        val current = _uiState.value.pendingApprovals
        val known = current.any { it.approval.id == approval.id }
        val shown = approval.isPending || approval.status == PendingApproval.STATUS_FAILED
        val updated = when {
            !shown -> current.filterNot { it.approval.id == approval.id }
            known -> current.map {
                // A pending echo of an answer still in flight must not unlock the card.
                if (it.approval.id != approval.id) it
                else if (approval.isPending) it.copy(approval = approval)
                else it.copy(approval = approval, decision = null, error = false)
            }
            else -> current + ApprovalItem(approval)
        }
        _uiState.value = _uiState.value.copy(pendingApprovals = updated)
    }

    private fun loadPendingApprovals() {
        viewModelScope.launch {
            approvalRepository.getPending().onSuccess { loaded ->
                // An id the stream already told about is fresher than this snapshot.
                val known = _uiState.value.pendingApprovals.map { it.approval.id }.toSet()
                val fresh = loaded.filter { it.id !in known && it.isPending }
                if (fresh.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        pendingApprovals = (_uiState.value.pendingApprovals + fresh.map { ApprovalItem(it) })
                            .sortedBy { it.approval.createdAt },
                    )
                }
            }
        }
    }

    private fun subscribeToApprovals() {
        viewModelScope.launch {
            approvalRepository.observe()
                .catch { /* SSE connection errors — silently retry on next app resume */ }
                .collect { approval -> applyApproval(approval) }
        }
    }

    /**
     * Delete a message: it leaves the chat at once, but the server hears nothing until
     * [UNDO_WINDOW_MS] has passed, so « Annuler » costs nothing (MAG-342).
     */
    fun deleteMessage(id: String) {
        // A bubble still waiting for its stored id is not on the server: nothing to delete.
        if (id.startsWith(PENDING_PREFIX)) return
        val messages = _uiState.value.messages
        val index = messages.indexOfFirst { it.id == id }
        if (index < 0) return
        val message = messages[index]

        pendingDeletion?.let { previous ->
            if (!previous.committing) {
                previous.timer?.cancel()
                viewModelScope.launch { commitDeletion(previous) }
            }
        }

        val deletion = PendingDeletion(message, index)
        pendingDeletion = deletion
        _uiState.value = _uiState.value.copy(
            messages = messages.filterNot { it.id == id },
            searchResults = _uiState.value.searchResults.filterNot { it.id == id },
            undoableMessageDeletion = message,
            messageDeleteFailed = false,
        )
        rebuildDisplayItems()
        deletion.timer = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            commitDeletion(deletion)
        }
    }

    /** « Annuler »: the message is back where it was, and the server never knew. */
    fun undoMessageDeletion() {
        val deletion = pendingDeletion?.takeIf { !it.committing } ?: return
        deletion.timer?.cancel()
        pendingDeletion = null
        _uiState.value = _uiState.value.copy(undoableMessageDeletion = null)
        restore(deletion)
    }

    fun consumeMessageDeleteFailed() {
        _uiState.value = _uiState.value.copy(messageDeleteFailed = false)
    }

    private suspend fun commitDeletion(deletion: PendingDeletion) {
        deletion.committing = true
        if (pendingDeletion === deletion) {
            pendingDeletion = null
            _uiState.value = _uiState.value.copy(undoableMessageDeletion = null)
        }
        repository.deleteMessage(deletion.message.id).onFailure { e ->
            Log.w(TAG, "Message ${deletion.message.id} not deleted: ${e.message}")
            restore(deletion)
            _uiState.value = _uiState.value.copy(messageDeleteFailed = true)
        }
    }

    private fun restore(deletion: PendingDeletion) {
        val current = _uiState.value.messages
        if (current.none { it.id == deletion.message.id }) {
            val restored = current.toMutableList()
            restored.add(deletion.index.coerceAtMost(restored.size), deletion.message)
            _uiState.value = _uiState.value.copy(messages = restored)
            rebuildDisplayItems()
        }
    }

    /**
     * Put the owner's thumb on one of Maggie's answers, or take it off with a null [rating]
     * (MAG-23). As on the web, a thumb down is recorded at once, then offers an optional word on
     * why ([commentingMessageId][ChatUiState.commentingMessageId]): a quick tap is never lost to
     * a form nobody fills.
     */
    fun rateMessage(id: String, rating: String?) {
        val message = rateableMessage(id) ?: return
        if (message.feedback == rating) return

        recordFeedback(message, rating, null)
        val commenting = _uiState.value.commentingMessageId
        _uiState.value = _uiState.value.copy(
            commentingMessageId = when {
                rating == MessageFeedback.DOWN -> id
                commenting == id -> null
                else -> commenting
            },
        )
    }

    /**
     * « Envoyer » under a thumb down: the same thumb, now with what the owner wrote. A blank
     * comment sends nothing — the thumb down is already recorded.
     */
    fun sendFeedbackComment(comment: String) {
        val id = _uiState.value.commentingMessageId ?: return
        _uiState.value = _uiState.value.copy(commentingMessageId = null)
        val word = comment.trim().take(MessageFeedback.MAX_COMMENT_LENGTH).ifEmpty { return }
        val message = rateableMessage(id) ?: return
        // The thumb down was refused or taken back meanwhile: a comment on nothing is not sent.
        if (message.feedback != MessageFeedback.DOWN) return

        recordFeedback(message, MessageFeedback.DOWN, word)
    }

    /** « Passer »: no word on why, the thumb down stays. */
    fun skipFeedbackComment() {
        _uiState.value = _uiState.value.copy(commentingMessageId = null)
    }

    /** A stored answer of Maggie's: the one being written still has its `pending_` id. */
    private fun rateableMessage(id: String): ChatMessage? =
        _uiState.value.messages.firstOrNull { it.id == id }
            ?.takeIf { it.role == "assistant" && !id.startsWith(PENDING_PREFIX) }

    /**
     * The bubble shows it at once; a refusal puts back what it showed before, unless another tap
     * changed it since — that tap's own answer settles it.
     */
    private fun recordFeedback(message: ChatMessage, rating: String?, comment: String?) {
        val id = message.id
        setFeedback(id, rating, comment)
        viewModelScope.launch {
            repository.rateMessage(id, rating, comment).onFailure { e ->
                Log.w(TAG, "Feedback on $id not recorded: ${e.message}")
                val current = _uiState.value.messages.firstOrNull { it.id == id }
                if (current != null && current.feedback == rating && current.feedbackComment == comment) {
                    setFeedback(id, message.feedback, message.feedbackComment)
                    _uiState.value = _uiState.value.copy(
                        messageFeedbackFailed = true,
                        // No thumb down left to say why about.
                        commentingMessageId = _uiState.value.commentingMessageId
                            .takeUnless { it == id && message.feedback != MessageFeedback.DOWN },
                    )
                }
            }
        }
    }

    fun consumeMessageFeedbackFailed() {
        _uiState.value = _uiState.value.copy(messageFeedbackFailed = false)
    }

    private fun setFeedback(id: String, rating: String?, comment: String?) {
        val rated = { m: ChatMessage -> if (m.id == id) m.copy(feedback = rating, feedbackComment = comment) else m }
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.map(rated),
            searchResults = _uiState.value.searchResults.map(rated),
            messageFeedbackFailed = false,
        )
        rebuildDisplayItems()
    }

    /** The chat follows the thread list: a thread being deleted takes its messages off the screen. */
    fun onThreadEvent(event: ThreadEvent) {
        when (event) {
            is ThreadEvent.Hidden -> {
                hiddenContextIds += event.contextId
                rebuildDisplayItems()
            }
            is ThreadEvent.Restored -> {
                hiddenContextIds -= event.contextId
                rebuildDisplayItems()
            }
            is ThreadEvent.Deleted -> removeMessages(emptySet(), event.contextId)
        }
    }

    /**
     * Drops what the server says it deleted: [ids], and the messages of [contextId]. Idempotent
     * with the optimistic removal done here first — the echo of our own deletion finds nothing.
     */
    private fun removeMessages(ids: Set<String>, contextId: String?) {
        val gone = { m: ChatMessage -> m.id in ids || (contextId != null && m.contextId == contextId) }
        val state = _uiState.value
        if (contextId != null) hiddenContextIds += contextId
        // What the owner could still take back is gone for good: no « Annuler » for nothing.
        pendingDeletion?.takeIf { it.message.id in ids || (contextId != null && it.message.contextId == contextId) }
            ?.let {
                it.timer?.cancel()
                pendingDeletion = null
            }
        _uiState.value = state.copy(
            messages = state.messages.filterNot(gone),
            searchResults = state.searchResults.filterNot(gone),
            undoableMessageDeletion = if (pendingDeletion == null) null else state.undoableMessageDeletion,
        )
        rebuildDisplayItems()
        viewModelScope.launch {
            try {
                repository.forgetMessages(ids.toList(), contextId)
            } catch (e: Exception) {
                Log.w(TAG, "Local copy of deleted messages not cleaned: ${e.message}")
            }
        }
    }

    /**
     * `{"deleted": true, "contextId": …, "messageIds": […]}` on the chat stream. Returns whether
     * [data] was one, so that it is never read as a message.
     */
    private fun applyDeletionEvent(data: String): Boolean {
        val payload = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return false
        if (payload["deleted"]?.jsonPrimitive?.booleanOrNull != true) return false
        val ids = (payload["messageIds"] as? JsonArray)
            ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?.toSet()
            .orEmpty()
        val contextId = runCatching { payload["contextId"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        removeMessages(ids, contextId)
        return true
    }

    private fun subscribeToChatUpdates() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.agentScoped(userId, MercureTopics.CHAT))
                .catch { /* SSE connection errors — silently retry on next app resume */ }
                .collect { event ->
                    try {
                        // A deletion has no role and no content: it is not a message to show.
                        if (applyDeletionEvent(event.data)) return@collect
                        val message = json.decodeFromString<ChatMessage>(event.data).asSaid()
                        repository.handleMercureMessage(message)
                        // Append to in-memory list if not already present
                        val current = _uiState.value.messages
                        val known = current.firstOrNull { it.id == message.id }
                        if (known != null && known.content != message.content) {
                            // Cut short after being shown whole (MAG-223): what is stored wins.
                            _uiState.value = _uiState.value.copy(messages = current.withMessage(message))
                            rebuildDisplayItems()
                        } else if (known == null) {
                            // The question this device just sent comes back with its stored id:
                            // it is that bubble, not a new one.
                            val pendingIndex = if (message.role == "user") {
                                current.indexOfFirst { it.id.startsWith(PENDING_PREFIX) && it.content == message.content }
                            } else {
                                -1
                            }
                            _uiState.value = _uiState.value.copy(
                                messages = if (pendingIndex >= 0) {
                                    if (current[pendingIndex].id in runMessageIds) runMessageIds += message.id
                                    correction?.takeIf { it.messageId == current[pendingIndex].id }
                                        ?.let { correction = it.copy(messageId = message.id) }
                                    current.mapIndexed { i, m ->
                                        // The echo's thread wins; the run's is what the bubble had.
                                        if (i == pendingIndex) m.copy(id = message.id, contextId = message.contextId ?: m.contextId) else m
                                    }
                                } else {
                                    current + message
                                },
                                thumbnails = if (pendingIndex >= 0) {
                                    _uiState.value.thumbnails.rekeyed(current[pendingIndex].id, message.id)
                                } else {
                                    _uiState.value.thumbnails
                                },
                            )
                            rebuildDisplayItems()
                        } else if (message.contextId != null && known.contextId != message.contextId) {
                            // Streamed without a thread, echoed with one.
                            _uiState.value = _uiState.value.copy(
                                messages = current.map { if (it.id == message.id) it.copy(contextId = message.contextId) else it },
                            )
                            rebuildDisplayItems()
                        }
                        // Clear loading when we receive an assistant/system message — not the line
                        // that keeps the user's own answer to a request (MAG-336): Maggie is still
                        // working on the question asked before it.
                        if (message.role != "user" && !message.isDecision) {
                            unanswered = null
                            _uiState.value = _uiState.value.copy(isLoading = false, failure = null)
                            rebuildDisplayItems()
                        }
                    } catch (_: Exception) {
                        // Ignore parse errors
                    }
                }
        }
    }
}
