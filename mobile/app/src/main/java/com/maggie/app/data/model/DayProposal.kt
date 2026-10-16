package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * The morning's five (MAG-498), `GET /api/day-plans/{YYYY-MM-DD}/proposal`: what a simple rule
 * proposes, written nowhere. [reserveRanked] is the rest, best first; a swap takes its first.
 */
@Serializable
data class DayProposal(
    val date: String,
    val slots: List<ProposalSlot> = emptyList(),
    val reserveRanked: List<ProposalEntry> = emptyList(),
    val totalMinutes: Int = 0,
    /** The agenda's free time of the day, from the morning to 21:00. */
    val freeMinutes: Int = 0,
) {
    val day: LocalDate get() = LocalDate.parse(date)

    /** The ids to send to `PUT /api/day-plans/{date}` for « C'est parti ». */
    val taskIds: List<String> get() = slots.map { it.task.id }

    /**
     * Slot [index] swapped: the first of the reserve takes its place (the position stays) and the
     * task it replaces goes to the end of the reserve, so repeated swaps walk the reserve without
     * losing a task. Nothing changes without a reserve.
     */
    fun swap(index: Int): DayProposal {
        val next = reserveRanked.firstOrNull() ?: return this
        val out = slots.getOrNull(index) ?: return this
        val swapped = slots.mapIndexed { i, slot ->
            if (i == index) slot.copy(reason = next.reason, minutes = next.minutes, task = next.task) else slot
        }
        return copy(
            slots = swapped,
            reserveRanked = reserveRanked.drop(1) + ProposalEntry(out.reason, out.minutes, out.task),
            totalMinutes = swapped.filter { !it.task.isDone }.sumOf { it.minutes },
        )
    }
}

@Serializable
data class ProposalSlot(
    /** 1 to 5, in order. */
    val position: Int,
    val type: String = "task",
    /** Why it is proposed: see [ProposalReason]. */
    val reason: String,
    /** The estimate, 15 when the task has none. */
    val minutes: Int,
    val task: DayTask,
)

@Serializable
data class ProposalEntry(
    val reason: String,
    val minutes: Int,
    val task: DayTask,
)

/** The badges that say why a task is proposed, by the API's `reason`. */
object ProposalReason {
    fun label(reason: String): String = when (reason) {
        "postponed" -> "reportée"
        "dueToday" -> "à faire aujourd'hui"
        "urgent" -> "urgent"
        "dueTomorrow" -> "pour demain"
        "quick" -> "rapide"
        else -> "quand tu veux"
    }
}
