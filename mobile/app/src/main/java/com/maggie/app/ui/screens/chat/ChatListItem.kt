package com.maggie.app.ui.screens.chat

import com.maggie.app.data.model.ChatMessage

sealed class ChatListItem {
    data class DateSeparator(val label: String) : ChatListItem()
    data object UnreadDivider : ChatListItem()
    data class MessageItem(
        val message: ChatMessage,
        val isHighlighted: Boolean = false,
    ) : ChatListItem()
    data object LoadingIndicator : ChatListItem()
}
