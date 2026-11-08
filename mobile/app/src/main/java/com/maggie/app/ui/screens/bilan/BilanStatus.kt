package com.maggie.app.ui.screens.bilan

import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/** The activity status' name for the Bilan du soir (`GET /api/activity-status/bilan`). */
const val BILAN_ACTIVITY = "bilan"

/** The origin kind of a bilan: the day it was started for. */
private const val DAY_KIND = "day"

/** The five steps of the evening review, in order. [wire] is the API's `step`. */
enum class BilanStep(val wire: String, val label: String, val heading: String) {
    JOURNEE("journee", "Journée", "Votre journée"),
    FINANCE("finance", "Finance", "Vos comptes"),
    SANTE("sante", "Santé", "Votre santé"),
    CONSIGNES("consignes", "Consignes", "Vos consignes"),
    DEMAIN("demain", "Demain", "Demain"),
    ;

    val next: BilanStep? get() = entries.getOrNull(ordinal + 1)
    val previous: BilanStep? get() = entries.getOrNull(ordinal - 1)

    companion object {
        fun of(wire: String?): BilanStep? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A bilan in progress. [state] is the status' JSON as it stands — `answers` and `instructions` belong to the
 * steps' slots and to Maggie (`save_bilan_step`), so a change of step rewrites them untouched.
 */
data class BilanSession(val day: LocalDate, val step: BilanStep, val state: JsonObject = JsonObject(emptyMap())) {
    fun at(next: BilanStep) = copy(step = next)

    fun statusOrigin() = ActivityOrigin(DAY_KIND, day.toString())

    fun statusState() = JsonObject(state + ("step" to JsonPrimitive(step.wire)))
}

/**
 * The bilan a status holds, or null when it is not one this version can read. A bilan whose day has gone is
 * not [BilanSession.day] today: the API closes it in the morning, and « Reprendre » must not wait for that.
 */
fun ActivityStatus.toBilanSession(): BilanSession? {
    if (origin.kind != DAY_KIND) return null
    val day = runCatching { LocalDate.parse(origin.id) }.getOrNull() ?: return null
    val step = BilanStep.of(state["step"]?.jsonPrimitive?.contentOrNull) ?: return null
    return BilanSession(day, step, state)
}

/** Still resumable: started for [today]. */
fun BilanSession.isOpenOn(today: LocalDate): Boolean = !day.isBefore(today)
