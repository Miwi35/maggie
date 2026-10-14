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
    /** The owner's thumb on an answer of Maggie's: [MessageFeedback.UP], [MessageFeedback.DOWN] or none (MAG-23). */
    val feedback: String? = null,
    /** What the owner said went wrong with a thumb down, when he said anything. */
    val feedbackComment: String? = null,
) {
    val isDecision: Boolean get() = role == DECISION_ROLE
}

/**
 * The ratings the agent knows. Plain strings, like [ChatMessage.role]: a value this
 * copy of the app does not know must not make the whole message unreadable.
 */
object MessageFeedback {
    const val UP = "up"
    const val DOWN = "down"

    /** The agent refuses a longer word (MAG-23). */
    const val MAX_COMMENT_LENGTH = 1000
}
