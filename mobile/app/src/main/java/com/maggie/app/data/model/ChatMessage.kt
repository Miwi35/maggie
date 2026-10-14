package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * The role of the line the agent writes when the user answers a request of Maggie's —
 * « Vous avez répondu : Autoriser — … » (MAG-336). Neither side's message: an information line.
 */
const val DECISION_ROLE = "decision"

@Serializable
data class ChatMessage(
    val id: String = "",
    val role: String,
    val content: String,
    val createdAt: String = "",
    /**
     * The question was sent with a screenshot (MAG-214). The image itself is never
     * stored anywhere: after a reload the bubble can only say there was one.
     */
    val hasImage: Boolean = false,
    /** The thread the message was filed in, `null` for one that belongs to none (MAG-342). */
    val contextId: String? = null,
) {
    val isDecision: Boolean get() = role == DECISION_ROLE
}
