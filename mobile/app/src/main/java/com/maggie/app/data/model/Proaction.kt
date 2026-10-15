package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * One run of Maggie, scheduled in her internal « Maggie » agenda (MAG-326). It is an event of that
 * agenda with a prompt and a run status: [executionStatus] is `pending`, `running`, `completed`
 * or `failed`, and stays apart from the inherited event `status`.
 */
@Serializable
data class Proaction(
    val id: String,
    val summary: String = "",
    val startAt: String? = null,
    val endAt: String? = null,
    val agenda: String? = null,
    val timeZone: String? = null,
    val prompt: String = "",
    val executionStatus: String = STATUS_PENDING,
    val response: String? = null,
    val error: String? = null,
    val completedAt: String? = null,
) {
    val isCompleted: Boolean get() = executionStatus == STATUS_COMPLETED
    val isFailed: Boolean get() = executionStatus == STATUS_FAILED

    /** The status as the screens word it. */
    val statusLabel: String
        get() = when (executionStatus) {
            STATUS_PENDING -> "En attente"
            STATUS_RUNNING -> "En cours"
            STATUS_COMPLETED -> "Faite"
            STATUS_FAILED -> "Échouée"
            else -> executionStatus
        }

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
    }
}
