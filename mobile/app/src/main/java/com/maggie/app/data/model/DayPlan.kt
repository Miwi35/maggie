package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * The day (MAG-497), `GET /api/day-plans/{YYYY-MM-DD}`: five slots at most, `now` the first one
 * not done, and the reserve — every open top-level task holding no slot, the highest score first.
 * Created empty on its first read. Plain JSON, not API Platform.
 */
@Serializable
data class DayPlan(
    val id: String,
    val date: String,
    val confirmedAt: String? = null,
    val slots: List<DaySlot> = emptyList(),
    val now: DaySlot? = null,
    val reserve: List<DayTask> = emptyList(),
    val done: Int = 0,
    val total: Int = 0,
) {
    val isFull: Boolean get() = total >= MAX_SLOTS

    val day: LocalDate get() = LocalDate.parse(date)

    /** The ids of the slots' tasks, in order: what a `PUT` sends back, changed. */
    val taskIds: List<String> get() = slots.map { it.task.id }

    /** What is left to do today: the « Aujourd'hui » tab's count. */
    val left: Int get() = total - done

    /** [taskId] ticked or unticked here, before the server answers: `now` and `done` follow. */
    fun withCompleted(taskId: String, completedAt: String?): DayPlan {
        val slots = slots.map { if (it.task.id == taskId) it.copy(task = it.task.copy(completedAt = completedAt)) else it }
        return copy(
            slots = slots,
            now = slots.firstOrNull { !it.task.isDone },
            done = slots.count { it.task.isDone },
        )
    }

    companion object {
        const val MAX_SLOTS = 5
    }
}

@Serializable
data class DaySlot(
    /** 1 to 5, in order. */
    val position: Int,
    /** Only `task` for now; routines come later. */
    val type: String = "task",
    val task: DayTask,
)

/** A task as the day shows it: fewer fields than [Task], enough for a row. */
@Serializable
data class DayTask(
    val id: String,
    val title: String,
    val estimatedMinutes: Int? = null,
    val firstStep: String? = null,
    val completedAt: String? = null,
    val dueDate: String? = null,
    /** The first past day it was due or planned and left undone, `YYYY-MM-DD`; null when it is not put off. */
    val postponedSince: String? = null,
    val urgency: String? = null,
    val criticality: String = "low",
) {
    val isDone: Boolean get() = completedAt != null

    val isCritical: Boolean get() = urgency == "critical"
}

/** `PUT /api/day-plans/{date}`: the whole list of slots, in order. No default: an omitted `type` is refused. */
@Serializable
data class DayPlanUpdateRequest(val slots: List<DaySlotRequest>)

@Serializable
data class DaySlotRequest(val type: String, val taskId: String) {
    companion object {
        fun task(id: String) = DaySlotRequest(type = "task", taskId = id)
    }
}
