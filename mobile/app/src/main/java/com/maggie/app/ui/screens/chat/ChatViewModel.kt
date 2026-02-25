package com.maggie.app.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.repository.ChatPreferencesRepository
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.util.ChatDateFormatter
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
)

@OptIn(FlowPreview::class)
class ChatViewModel(
    private val repository: ChatRepository,
    private val mercureService: MercureService,
    private val chatPrefsRepository: ChatPreferencesRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    private val json = Json { ignoreUnknownKeys = true }
    private val searchQueryFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private var highlightJob: Job? = null

    companion object {
        private const val PAGE_SIZE = 20
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
                val messages = repository.loadRecentMessages(PAGE_SIZE)
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
                val older = repository.loadOlderMessages(oldestId, PAGE_SIZE)
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

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val newMessages = repository.sendMessage(text)
                if (newMessages.isNotEmpty()) {
                    val merged = _uiState.value.messages + newMessages
                    _uiState.value = _uiState.value.copy(messages = merged, isLoading = false)
                    rebuildDisplayItems()
                    scrollToBottom(animate = true)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
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
                            val results = repository.searchMessages(query)
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
                val context = repository.loadMessageContext(messageId)
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

        if (state.isLoading) {
            items.add(ChatListItem.LoadingIndicator)
        }

        _uiState.value = _uiState.value.copy(displayItems = items)
    }

    private fun subscribeToChatUpdates() {
        viewModelScope.launch {
            mercureService.subscribe("/chat/{userId}")
                .catch { /* SSE connection errors — silently retry on next app resume */ }
                .collect { event ->
                    try {
                        val message = json.decodeFromString<ChatMessage>(event.data)
                        repository.handleMercureMessage(message)
                        // Append to in-memory list if not already present
                        val current = _uiState.value.messages
                        if (current.none { it.id == message.id }) {
                            _uiState.value = _uiState.value.copy(
                                messages = current + message,
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
