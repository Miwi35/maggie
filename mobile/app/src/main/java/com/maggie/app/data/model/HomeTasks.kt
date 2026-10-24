package com.maggie.app.data.model

/**
 * What the « Tâches » card of the Accueil shows (MAG-561): the tasks of the day, the routines of the moment, and
 * the numbers of the ring. Not a wire object: [HomeTasksComposer] composes it from the tasks and routines
 * resources (MAG-588).
 *
 * - [done] / [total]: the ring, « 3 sur 5 »; [total] is five at most, done rows included. The done rows
 *   come after the open ones and stay in the list until midnight;
 * - [more]: the open tasks the card does not list, « + n autres »;
 * - [overdue]: the open tasks that are late, « n en retard »;
 * - [editable]: `false` on tomorrow — nothing on the card can be ticked;
 * - [saga]: the saga of the moment, when there is one (none is composed yet).
 */
data class HomeTasks(
    val module: String = "tasks",
    val day: String = "today",
    val editable: Boolean = true,
    val tasks: List<HomeTask> = emptyList(),
    val done: Int = 0,
    val total: Int = 0,
    val more: Int = 0,
    val overdue: Int = 0,
    val routines: List<HomeRoutine> = emptyList(),
    val saga: HomeSaga? = null,
) {
    /** Nothing to say: the card is not drawn. */
    val isEmpty: Boolean get() = tasks.isEmpty() && routines.isEmpty() && saga == null && more == 0

    /**
     * Ticks (or unticks) [id] as the API would count it, without a read: the row is struck, the ring moves,
     * and a late task stops counting as late. The order stays, the next read puts the done rows last.
     */
    fun withTaskDone(id: String, done: Boolean): HomeTasks {
        val task = tasks.firstOrNull { it.id == id } ?: return this
        if (task.done == done) return this
        val step = if (done) 1 else -1
        return copy(
            tasks = tasks.map { if (it.id == id) it.copy(done = done) else it },
            done = (this.done + step).coerceIn(0, total),
            overdue = if (task.overdueDays != null) (overdue - step).coerceAtLeast(0) else overdue,
        )
    }

    /** Ticks (or unticks) the routine [id] of the bar, as the API would answer. */
    fun withRoutineChecked(id: String, checked: Boolean): HomeTasks =
        copy(routines = routines.map { if (it.id == id) it.copy(checked = checked) else it })
}

/** A row of the card: [parentId] is set on a subtask, which is drawn under its task; [overdueDays] on a late one. */
data class HomeTask(
    val id: String,
    val title: String,
    val done: Boolean = false,
    val overdueDays: Int? = null,
    val parentId: String? = null,
) {
    val isSubtask: Boolean get() = parentId != null
}

/** A chip of the routines' bar: [anchor] is the moment (`wake`, `afterLunch`…), [time] its hour, `HH:MM`. */
data class HomeRoutine(
    val id: String,
    val title: String,
    val anchor: String = Routine.ANCHOR_TIME,
    val time: String = "",
    val checked: Boolean = false,
)

/** The saga of the moment, « title · done/total ». */
data class HomeSaga(
    val title: String,
    val done: Int = 0,
    val total: Int = 0,
)
