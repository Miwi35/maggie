package com.maggie.app.ui.screens.chat

import com.maggie.app.data.model.ChatMessage

sealed class ChatListItem {
    data class DateSeparator(val label: String) : ChatListItem()
    data object UnreadDivider : ChatListItem()
    data class MessageItem(
        val message: ChatMessage,
        val isHighlighted: Boolean = false,
        /** The screenshot sent with it from this device, as JPEG bytes (MAG-214). */
        val thumbnail: ByteArray? = null,
    ) : ChatListItem()
    data class StreamingMessage(val text: String) : ChatListItem()
    data object LoadingIndicator : ChatListItem()
    data class Failure(val text: String) : ChatListItem()
}
