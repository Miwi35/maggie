package com.maggie.app.ui.screens.bilan

import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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

    /** `answers.tasks` as `taskId → choice` (`done`, `tomorrow`, `later`). */
    fun taskChoices(): Map<String, String> =
        (state["answers"] as? JsonObject)?.get("tasks")?.let { it as? JsonArray }.orEmpty().mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val id = item["taskId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val choice = item["choice"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            id to choice
        }.toMap()

    /** The session with [taskId]'s [choice] in `answers.tasks` — `null` removes it; the other answers stay untouched. */
    fun withTaskChoice(taskId: String, choice: String?): BilanSession {
        val answers = state["answers"] as? JsonObject ?: JsonObject(emptyMap())
        val tasks = taskChoices().toMutableMap()
        if (choice == null) tasks.remove(taskId) else tasks[taskId] = choice
        val list = JsonArray(tasks.map { (id, c) -> JsonObject(mapOf("taskId" to JsonPrimitive(id), "choice" to JsonPrimitive(c))) })
        return copy(state = JsonObject(state + ("answers" to JsonObject(answers + ("tasks" to list)))))
    }

    /** A whole-number answer of `answers` (`mood`, `fatigue`), or null when it has not been given. */
    fun answerInt(key: String): Int? =
        (state["answers"] as? JsonObject)?.get(key)?.let { it as? JsonPrimitive }?.intOrNull

    /** The session with `answers[key]` set to [value] — `null` removes it; the other answers stay untouched. */
    fun withAnswer(key: String, value: Int?): BilanSession {
        val answers = state["answers"] as? JsonObject ?: JsonObject(emptyMap())
        val updated = if (value == null) answers - key else answers + (key to JsonPrimitive(value))
        return copy(state = JsonObject(state + ("answers" to JsonObject(updated))))
    }

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
