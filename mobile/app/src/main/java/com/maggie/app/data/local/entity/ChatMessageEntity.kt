package com.maggie.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maggie.app.data.model.AudioAttachment
import com.maggie.app.data.model.ChatMessage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    val role: String,
    val content: String,
    val createdAt: String = "",
    val hasImage: Boolean = false,
    val contextId: String? = null,
    val feedback: String? = null,
    val feedbackComment: String? = null,
    /** The voice attachment as JSON (MAG-686), `null` for a text message. */
    val audio: String? = null,
) {
    fun toModel(): ChatMessage = ChatMessage(
        id = id,
        role = role,
        content = content,
        createdAt = createdAt,
        hasImage = hasImage,
        contextId = contextId,
        feedback = feedback,
        feedbackComment = feedbackComment,
        audio = audio?.let { runCatching { json.decodeFromString<AudioAttachment>(it) }.getOrNull() },
    )

    companion object {
        fun fromModel(message: ChatMessage): ChatMessageEntity =
            ChatMessageEntity(
                id = message.id,
                role = message.role,
                content = message.content,
                createdAt = message.createdAt,
                hasImage = message.hasImage,
                contextId = message.contextId,
                feedback = message.feedback,
                feedbackComment = message.feedbackComment,
                audio = message.audio?.let { json.encodeToString(it) },
            )

        private val json = Json { ignoreUnknownKeys = true }
    }
}
