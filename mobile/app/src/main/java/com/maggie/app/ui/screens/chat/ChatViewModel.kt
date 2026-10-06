package com.maggie.app.ui.screens.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.AgUiEvent
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.repository.ChatPreferencesRepository
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.util.ChatDateFormatter
import com.maggie.app.voice.ScreenContext
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
)

@OptIn(FlowPreview::class)
class ChatViewModel(
    private val repository: ChatRepository,
    private val mercureService: MercureService,
    private val chatPrefsRepository: ChatPreferencesRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
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

    // The request in flight, so that cutting Maggie off can stop it (MAG-223).
    private var sendJob: Job? = null
    private var interruptJob: Job? = null

    // The answer last handed to the voice, so cutting it off can say which one.
    private var spokenReply: ChatMessage? = null

    companion object {
        private const val PAGE_SIZE = 20
        private const val TAG = "ChatViewModel"
        private const val PENDING_PREFIX = "pending_"
    }

    init {
        loadInitialMessages()
        subscribeToChatUpdates()
        observeSearchQuery()
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
     * have attached to it (MAG-30). Applied wherever a message enters the UI,
     * because the agent stores and returns the string it was POSTed: without
     * this, « ajoute ça à mon agenda » comes back as a user bubble full of the
     * shop page it was about.
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
     * summoned from (MAG-30). It travels inside the message, because the chat
     * endpoint takes a single string, but never inside the bubble: the
     * conversation shows what the user said, not what Maggie was told about it.
     */
    fun sendMessage(text: String, screenContext: String? = null) {
        if (text.isBlank()) return
        awaitingReply = true
        spokenReply = null

        sendJob = viewModelScope.launch {
            // Add optimistic user message
            val userMessage = ChatMessage(
                id = "$PENDING_PREFIX${System.currentTimeMillis()}",
                role = "user",
                content = text,
                createdAt = java.time.Instant.now().toString(),
            )
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + userMessage,
                isLoading = true,
                streamingText = "",
                streamingMessageId = null,
                replyToSpeak = null,
            )
            rebuildDisplayItems()
            scrollToBottom(animate = true)

            // The agent builds this turn's history from what it stored of the last one: it must
            // know the last answer was cut before it reads the new question.
            interruptJob?.join()

            val payload = if (screenContext.isNullOrBlank()) text else "$screenContext\n\n$text"

            try {
                repository.sendMessageStream(payload)
                    .collect { event -> handleStreamEvent(event) }
                if (awaitingReply) finishRequest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Stream failed, falling back to non-streaming: ${e.message}")
                // Fallback to non-streaming
                try {
                    val newMessages = repository.sendMessage(payload).asSaid()
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
                        awaitingReply = false
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            streamingText = "",
                            streamingMessageId = null,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    awaitingReply = false
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        streamingText = "",
                        streamingMessageId = null,
                    )
                }
            }
        }
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
            streamingText = "",
            streamingMessageId = null,
            replyToSpeak = null,
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
                Log.w(TAG, "Stream error: ${event.message}")
                awaitingReply = false
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    streamingText = "",
                    streamingMessageId = null,
                )
                rebuildDisplayItems()
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
        spokenReply = _uiState.value.replyToSpeak
        _uiState.value = _uiState.value.copy(replyToSpeak = null)
    }

    /** The surface that would have spoken is gone: an answer arriving now stays silent. */
    fun dropPendingReply() {
        awaitingReply = false
        spokenReply = null
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

        _uiState.value = _uiState.value.copy(displayItems = items)
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
                        val shown = current.firstOrNull { it.id == message.id }
                        if (shown != null && shown.content != message.content) {
                            // Cut short after being shown whole (MAG-223): what is stored wins.
                            _uiState.value = _uiState.value.copy(messages = current.withMessage(message))
                            rebuildDisplayItems()
                        } else if (shown == null) {
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
                            _uiState.value = _uiState.value.copy(isLoading = false)
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
