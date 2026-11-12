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

/**
 * The morning digest a message announces (MAG-808): its [text], the [day] it is for, when it stops being offered on the
 * Accueil ([expiresAt], ISO) and when it was first opened ([openedAt], ISO, `null` until then).
 */
@Serializable
data class DigestBlock(
    val type: String = "digest",
    val day: String = "",
    val text: String = "",
    val expiresAt: String = "",
    val openedAt: String? = null,
) : ChatBlock

/** The evening review a message announces (MAG-826): the [day] it is for and the [route] that opens its activity. */
@Serializable
data class BilanBlock(
    val type: String = "bilan",
    val day: String = "",
    val route: String = "",
) : ChatBlock

/** A block this version does not know: kept so the list decodes, drawn as nothing. */
@Serializable
data class UnknownBlock(val type: String = "") : ChatBlock

object ChatBlockSerializer : JsonContentPolymorphicSerializer<ChatBlock>(ChatBlock::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ChatBlock> =
        when (element.jsonObject["type"]?.jsonPrimitive?.content) {
            "question" -> QuestionBlock.serializer()
            "task" -> TaskBlock.serializer()
            "digest" -> DigestBlock.serializer()
            "bilan" -> BilanBlock.serializer()
            else -> UnknownBlock.serializer()
        }
}
