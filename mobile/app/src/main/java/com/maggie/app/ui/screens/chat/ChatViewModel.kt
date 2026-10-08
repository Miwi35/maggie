package com.maggie.app.ui.screens.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.ApprovalDecisionException
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.AgUiEvent
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.data.repository.ApprovalRepository
import com.maggie.app.data.repository.ChatPreferencesRepository
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.ui.components.approvalQuestion
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
    /** Why the last question got no answer: shown in the thread, never left as a silence. */
    val failure: String? = null,
)

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

    companion object {
        private const val PAGE_SIZE = 20
        private const val TAG = "ChatViewModel"
        private const val PENDING_PREFIX = "pending_"
        private const val RECOVERY_WINDOW_MS = 60_000L
        private const val RECOVERY_POLL_MS = 3_000L
        private const val NO_ANSWER = "Maggie n'a pas pu répondre. Vérifiez votre connexion et réessayez."
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
     */
    fun sendMessage(text: String, screenContext: String? = null) {
        if (text.isBlank()) return
        awaitingReply = true

        viewModelScope.launch {
            // Add optimistic user message
            val userMessage = ChatMessage(
                id = "$PENDING_PREFIX${System.currentTimeMillis()}",
                role = "user",
                content = text,
                createdAt = java.time.Instant.now().toString(),
            )
            val anchor = _uiState.value.messages.lastOrNull { !it.id.startsWith(PENDING_PREFIX) }?.createdAt
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + userMessage,
                failure = null,
                isLoading = true,
                streamingText = "",
                streamingMessageId = null,
                replyToSpeak = null,
            )
            rebuildDisplayItems()
            scrollToBottom(animate = true)

            val screen = screenContext?.takeIf { it.isNotBlank() }

            try {
                var streamError: String? = null
                repository.sendMessageStream(text, screen)
                    .collect { event ->
                        if (event is AgUiEvent.Error) streamError = event.message
                        handleStreamEvent(event)
                    }
                if (awaitingReply) {
                    val answered = _uiState.value.messages.lastOrNull()?.role == "assistant"
                    if (answered && streamError == null) finishRequest() else recoverReply(anchor, RECOVERY_WINDOW_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Stream failed, falling back to non-streaming: ${e::class.simpleName}: ${e.message}")
                // Fallback to non-streaming
                try {
                    val newMessages = repository.sendMessage(text, screen).asSaid()
                    if (newMessages.isNotEmpty()) {
                        // Replace optimistic user message with server response
                        val current = _uiState.value.messages.dropLast(1)
                        _uiState.value = _uiState.value.copy(
                            messages = current + newMessages,
                            streamingText = "",
                            streamingMessageId = null,
                        )
                        finishRequest()
                        rebuildDisplayItems()
                        scrollToBottom(animate = true)
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
            if (_uiState.value.messages.lastOrNull()?.role == "assistant") {
                finishRequest()
                rebuildDisplayItems()
                scrollToBottom(animate = true)
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
        for (message in stored) {
            if (messages.any { it.id == message.id }) continue
            val pendingIndex = if (message.role == "user") {
                messages.indexOfFirst { it.id.startsWith(PENDING_PREFIX) && it.content == message.content }
            } else {
                -1
            }
            messages = if (pendingIndex >= 0) {
                messages.mapIndexed { i, m -> if (i == pendingIndex) message else m }
            } else {
                messages + message
            }
        }
        _uiState.value = _uiState.value.copy(messages = messages)
        rebuildDisplayItems()
    }

    private fun fail() {
        awaitingReply = false
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            streamingText = "",
            streamingMessageId = null,
            failure = NO_ANSWER,
        )
        rebuildDisplayItems()
    }

    private suspend fun handleStreamEvent(event: AgUiEvent) {
        when (event) {
            is AgUiEvent.TextMessageStart -> {
                _uiState.value = _uiState.value.copy(
                    streamingMessageId = event.messageId,
                    streamingText = "",
                )
                rebuildDisplayItems()
                scrollToBottom(animate = true)
            }
            is AgUiEvent.TextMessageContent -> {
                _uiState.value = _uiState.value.copy(
                    streamingText = _uiState.value.streamingText + event.delta,
                )
                rebuildDisplayItems()
                scrollToBottom(animate = false)
            }
            is AgUiEvent.TextMessageEnd -> {
                val finalText = _uiState.value.streamingText
                val messageId = _uiState.value.streamingMessageId ?: event.messageId
                val assistantMessage = ChatMessage(
                    id = messageId,
                    role = "assistant",
                    content = finalText,
                    createdAt = java.time.Instant.now().toString(),
                )
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
                scrollToBottom(animate = true)
            }
            is AgUiEvent.RunFinished -> {
                finishRequest()
                rebuildDisplayItems()
            }
            is AgUiEvent.ContextUpdate -> {
                _contextUpdates.tryEmit(event)
            }
            is AgUiEvent.Error -> {
                // Not the end of the request: the answer may be stored, see [recoverReply].
                Log.w(TAG, "Stream error: ${event.message}")
            }
            is AgUiEvent.RunStarted,
            is AgUiEvent.ToolCallStart,
            is AgUiEvent.ToolCallEnd,
            is AgUiEvent.ToolResult -> {
                // Acknowledged but no UI action in v1
            }
        }
    }

    /** The request is over: its answer, if any, is now the thing to read aloud. */
    private fun finishRequest() {
        val reply = if (awaitingReply) _uiState.value.messages.lastOrNull()?.takeIf { it.role == "assistant" } else null
        awaitingReply = false
        _uiState.value = _uiState.value.copy(isLoading = false, replyToSpeak = reply)
    }

    /** Called by whoever read [ChatUiState.replyToSpeak]: it is offered once. */
    fun onReplySpoken() {
        _uiState.value = _uiState.value.copy(replyToSpeak = null)
    }

    /** The surface that would have spoken is gone: an answer arriving now stays silent. */
    fun dropPendingReply() {
        awaitingReply = false
        _uiState.value = _uiState.value.copy(replyToSpeak = null)
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
        val messages = state.messages
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

        _uiState.value = _uiState.value.copy(displayItems = items)
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
        decide(target.approval.id, decision)
        return true
    }

    /** The question Maggie asks aloud for a held action, with what its arguments cannot say. */
    suspend fun questionFor(approval: PendingApproval): String =
        approvalQuestion(approval, approvalRepository.describe(approval))

    fun isApprovalAsked(id: String): Boolean = id in askedApprovals

    fun markApprovalAsked(id: String) {
        askedApprovals += id
    }

    /** Drops a card that stays on screen once settled — a failed action — when the user closes it. */
    fun dismissApproval(id: String) {
        _uiState.value = _uiState.value.copy(
            pendingApprovals = _uiState.value.pendingApprovals.filterNot { it.approval.id == id },
        )
    }

    private fun decide(id: String, decision: ApprovalDecision) {
        val item = _uiState.value.pendingApprovals.firstOrNull { it.approval.id == id } ?: return
        if (item.decision != null || !item.approval.isPending) return

        updateApproval(id) { it.copy(decision = decision, error = false) }
        viewModelScope.launch {
            val outcome = when (decision) {
                ApprovalDecision.APPROVE -> approvalRepository.approve(id)
                ApprovalDecision.DENY -> approvalRepository.deny(id)
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

    private fun subscribeToChatUpdates() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.agentScoped(userId, MercureTopics.CHAT))
                .catch { /* SSE connection errors — silently retry on next app resume */ }
                .collect { event ->
                    try {
                        val message = json.decodeFromString<ChatMessage>(event.data).asSaid()
                        repository.handleMercureMessage(message)
                        // Append to in-memory list if not already present
                        val current = _uiState.value.messages
                        if (current.none { it.id == message.id }) {
                            // The question this device just sent comes back with its stored id:
                            // it is that bubble, not a new one.
                            val pendingIndex = if (message.role == "user") {
                                current.indexOfFirst { it.id.startsWith(PENDING_PREFIX) && it.content == message.content }
                            } else {
                                -1
                            }
                            _uiState.value = _uiState.value.copy(
                                messages = if (pendingIndex >= 0) {
                                    current.mapIndexed { i, m -> if (i == pendingIndex) m.copy(id = message.id) else m }
                                } else {
                                    current + message
                                },
                            )
                            rebuildDisplayItems()
                        }
                        // Clear loading when we receive an assistant/system message
                        if (message.role != "user") {
                            _uiState.value = _uiState.value.copy(isLoading = false, failure = null)
                            rebuildDisplayItems()
                            scrollToBottom(animate = true)
                        }
                    } catch (_: Exception) {
                        // Ignore parse errors
                    }
                }
        }
    }
}
