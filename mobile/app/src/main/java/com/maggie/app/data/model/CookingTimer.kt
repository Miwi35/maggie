package com.maggie.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CookingTimerStatus {
    @SerialName("running") RUNNING,

    /** `endsAt` is null, `remainingSeconds` is what is left. */
    @SerialName("paused") PAUSED,

    /** It rang: the alert waits for « OK ». */
    @SerialName("done") DONE,

    /** « OK » was pressed, here or on another device. */
    @SerialName("dismissed") DISMISSED,

    @SerialName("cancelled") CANCELLED,
    ;

    /** Still on screen: running, paused, or rung and not yet acknowledged. */
    val isActive: Boolean get() = this == RUNNING || this == PAUSED || this == DONE

    /** Still counting: what « Quitter » would stop. */
    val isCounting: Boolean get() = this == RUNNING || this == PAUSED
}

/**
 * A timer of the Cuisine activity (MAG-582), as every answer and every Mercure update spells it
 * (`CookingTimer::describe()`). `remainingSeconds` is computed by the server when it answers.
 */
@Serializable
data class CookingTimer(
    val id: String,
    val label: String,
    /** `/api/recipes/{id}`, null once the recipe is deleted. */
    val recipe: String? = null,
    val stepPosition: Int,
    val durationSeconds: Int,
    /** ISO-8601; null while paused. */
    val endsAt: String? = null,
    val remainingSeconds: Int,
    val status: CookingTimerStatus,
    val finishedAt: String? = null,
) {
    val recipeId: String? get() = recipe?.substringAfterLast('/')
}

@Serializable
data class CookingTimerStartRequest(
    val label: String,
    /** The recipe's IRI or bare id. */
    val recipe: String,
    val stepPosition: Int,
    val durationSeconds: Int,
)

/** `add` alone takes [seconds] (60 by default on the server, may be negative); null is left out of the body. */
@Serializable
data class CookingTimerChangeRequest(
    val action: String,
    val seconds: Int? = null,
)

/** The actions `PATCH /api/cooking/timers/{id}` takes. */
enum class CookingTimerAction(val wire: String) {
    PAUSE("pause"),
    RESUME("resume"),
    ADD("add"),
    CANCEL("cancel"),
    DISMISS("dismiss"),
}

/** GET's answer: plain JSON, not Hydra. */
@Serializable
data class CookingTimerList(val member: List<CookingTimer> = emptyList(), val totalItems: Int = 0)
