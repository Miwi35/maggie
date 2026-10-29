package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * Where the owner stopped reading (MAG-687), as the agent keeps it for every device:
 * `GET /chat/unread`, `PUT /chat/read` and the `chat-read` Mercure stream all carry this shape.
 */
@Serializable
data class ChatUnread(
    val lastReadMessageId: String? = null,
    val unreadCount: Int = 0,
)

/** Body of `PUT /chat/read`: the server only moves its watermark forward. */
@Serializable
data class ChatReadRequest(val messageId: String)
