package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * The reward a task ticked earned (MAG-508), `GET /api/rewards/task/{taskId}`: a star and a
 * message drawn from written banks in the user's tone. Created by the tick itself, so it is there
 * once the tick has answered; a tick that earned none reads 404. Plain JSON, not API Platform.
 *
 * - `level`: `standard`, `surprise` or `palier`;
 * - `surprise`: why it is an « étoile filante » (`fullDay`, `monthRecord`, `waited`), null otherwise.
 */
@Serializable
data class Reward(
    val id: String,
    val subjectType: String,
    val subjectId: String,
    val level: String,
    val surprise: String? = null,
    val messageKey: String,
    val message: String,
    val star: Boolean,
    val earnedAt: String,
) {
    /** The rare one: its own animation, a star crossing the screen. */
    val isShootingStar: Boolean get() = surprise != null

    companion object {
        const val SUBJECT_TASK = "task"
    }
}
