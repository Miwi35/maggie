package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A tool call the agent's policy holds back until the user answers it
 * (`agent/app/db/pending_action_model.py`). The arguments are frozen: what runs on
 * approval is what Maggie asked for.
 */
@Serializable
data class PendingApproval(
    val id: String,
    val toolName: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val status: String = STATUS_PENDING,
    val result: String? = null,
    val contextId: String? = null,
    val createdAt: String? = null,
    val decidedAt: String? = null,
    val expiresAt: String? = null,
) {
    val isPending: Boolean get() = status == STATUS_PENDING

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_APPROVED = "approved"
        const val STATUS_DENIED = "denied"
        const val STATUS_EXPIRED = "expired"
        const val STATUS_FAILED = "failed"
    }
}
