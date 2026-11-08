package com.maggie.app.data.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What a chat message carries besides its text (MAG-717): a question to answer, a task to open. */
@Serializable(with = ChatBlockSerializer::class)
sealed interface ChatBlock

@Serializable
data class QuestionBlock(
    val type: String = "question",
    val id: String,
    val text: String = "",
    val options: List<String> = emptyList(),
    val multiple: Boolean = false,
    /** The choices given, `null` while the question waits. */
    val answered: List<String>? = null,
) : ChatBlock {
    val isAnswered: Boolean get() = answered != null
}

@Serializable
data class TaskBlock(val type: String = "task", val id: String) : ChatBlock

/** A block this version does not know: kept so the list decodes, drawn as nothing. */
@Serializable
data class UnknownBlock(val type: String = "") : ChatBlock

object ChatBlockSerializer : JsonContentPolymorphicSerializer<ChatBlock>(ChatBlock::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ChatBlock> =
        when (element.jsonObject["type"]?.jsonPrimitive?.content) {
            "question" -> QuestionBlock.serializer()
            "task" -> TaskBlock.serializer()
            else -> UnknownBlock.serializer()
        }
}
