package com.maggie.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maggie.app.data.model.ChatMessage

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun toModel(): ChatMessage = ChatMessage(
        id = id,
        role = role,
        content = content,
        timestamp = timestamp,
    )

    companion object {
        fun fromModel(message: ChatMessage): ChatMessageEntity =
            ChatMessageEntity(
                id = if (message.id == 0L) 0 else message.id,
                role = message.role,
                content = message.content,
                timestamp = message.timestamp,
            )
    }
}
