package com.maggie.app.ui.components

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.ui.screens.chat.ScrollBehavior

/** How far above the very end still counts as « at the bottom », in pixels. */
private const val BOTTOM_TOLERANCE_PX = 48

private const val END_STEP_PX = 100_000f

/**
 * Where the conversation is read from, shared by the voice sheet, the text sheet, the
 * panel and the full screen (MAG-348).
 *
 * The list **follows** the latest message — a new one, an answer growing line by line,
 * a keyboard or a card taking height — until the user pulls it up on purpose; reaching
 * the bottom again, or the button, resumes it. Only a drag changes that: whatever the
 * ViewModel or the layout does to the list never stops the following.
 */
@Stable
class ChatListState internal constructor(val lazy: LazyListState) {
    /** `false` once the user left the bottom: nothing then scrolls the list by itself. */
    var following by mutableStateOf(true)
        private set

    /** Something arrived below the fold while the user was reading further up. */
    var unseenBelow by mutableStateOf(false)
        internal set

    internal var dragging = false

    internal fun isAtBottom(): Boolean {
        val info = lazy.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return true
        return last.index == info.totalItemsCount - 1 &&
            last.offset + last.size <= info.viewportEndOffset + BOTTOM_TOLERANCE_PX
    }

    /** To the very last line, even when the last bubble is taller than the list. */
    suspend fun scrollToLatest(animate: Boolean) {
        val last = lazy.layoutInfo.totalItemsCount - 1
        if (last < 0) return
        following = true
        unseenBelow = false
        if (animate) lazy.animateScrollToItem(last) else lazy.scrollToItem(last)
        while (lazy.canScrollForward && lazy.scrollBy(END_STEP_PX) > 0f) Unit
    }

    /** To a message the user asked for (a search result): the list stops following unless it landed on the end. */
    suspend fun scrollToIndex(index: Int) {
        following = false
        lazy.scrollToItem(index)
        following = isAtBottom()
    }

    internal fun onUserScrollEnded() {
        dragging = false
        following = isAtBottom()
        if (following) unseenBelow = false
    }
}

/**
 * The list state and the effects that drive it: the scroll commands the ViewModel
 * sends, the user's own scrolling, and the « the user is at the bottom » signal that
 * clears the unread count.
 *
 * The list is created **on the latest message**: a surface opened after the ViewModel
 * sent its scroll command never heard it, and used to show the oldest messages.
 */
@Composable
fun rememberChatListState(viewModel: ChatViewModel): ChatListState {
    val uiState by viewModel.uiState.collectAsState()
    val lazy = rememberLazyListState(
        initialFirstVisibleItemIndex = remember { viewModel.uiState.value.displayItems.lastIndex.coerceAtLeast(0) },
    )
    val state = remember(lazy) { ChatListState(lazy) }

    LaunchedEffect(uiState.scrollToIndex, uiState.scrollBehavior) {
        val index = uiState.scrollToIndex ?: return@LaunchedEffect
        // Consumed even when a newer scroll preempts this one: a command left in the
        // state would be replayed by the next surface that opens.
        try {
            when (uiState.scrollBehavior) {
                ScrollBehavior.ANIMATE_TO_BOTTOM -> state.scrollToLatest(animate = true)
                ScrollBehavior.INSTANT_TO_INDEX ->
                    if (index >= lazy.layoutInfo.totalItemsCount - 1) state.scrollToLatest(animate = false) else state.scrollToIndex(index)
                ScrollBehavior.NONE -> {}
            }
        } finally {
            viewModel.consumeScroll()
        }
    }

    LaunchedEffect(state) {
        lazy.interactionSource.interactions.collect { if (it is DragInteraction.Start) state.dragging = true }
    }
    LaunchedEffect(state) {
        snapshotFlow { lazy.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && state.dragging) state.onUserScrollEnded()
        }
    }

    LaunchedEffect(lazy) {
        snapshotFlow {
            val lastVisible = lazy.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = lazy.layoutInfo.totalItemsCount
            lastVisible >= total - 2
        }.collect { isAtBottom ->
            if (isAtBottom) {
                viewModel.onScrolledToBottom()
            }
        }
    }

    return state
}
