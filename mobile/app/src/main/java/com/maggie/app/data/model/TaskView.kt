package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * The four views of the tasks (MAG-408), as `GET /api/tasks/view/{key}` names them. Each task
 * is in exactly one of them, the API decides which.
 */
enum class TaskView(val key: String, val label: String) {
    TODAY("today", "Aujourd'hui"),
    UPCOMING("upcoming", "À venir"),
    SOMEDAY("someday", "Un jour"),
    DONE("done", "Faites"),
}

/** What each view holds, sent with every read of one so the tab labels stay true. */
@Serializable
data class TaskViewCounts(
    val today: Int = 0,
    val upcoming: Int = 0,
    val someday: Int = 0,
    val done: Int = 0,
) {
    operator fun get(view: TaskView): Int = when (view) {
        TaskView.TODAY -> today
        TaskView.UPCOMING -> upcoming
        TaskView.SOMEDAY -> someday
        TaskView.DONE -> done
    }
}

/**
 * One read of a view: `totalItems` is the size of the whole view, `member` the tasks of this
 * page (« Faites » is paged by 30, the others come whole), in the order to show with each
 * subtask after its parent.
 */
@Serializable
data class TaskViewResponse(
    val member: List<Task> = emptyList(),
    val totalItems: Int = 0,
    val counts: TaskViewCounts = TaskViewCounts(),
    val page: Int? = null,
    val itemsPerPage: Int? = null,
)
