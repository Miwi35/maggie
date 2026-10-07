package com.maggie.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maggie.app.data.model.ChatMessage

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    val role: String,
    val content: String,
    val createdAt: String = "",
    val contextId: String? = null,
) {
    fun toModel(): ChatMessage = ChatMessage(
        id = id,
        role = role,
        content = content,
        createdAt = createdAt,
        contextId = contextId,
    )

    companion object {
        fun fromModel(message: ChatMessage): ChatMessageEntity =
            ChatMessageEntity(
                id = message.id,
                role = message.role,
                content = message.content,
                createdAt = message.createdAt,
                contextId = message.contextId,
            )
    }
}
