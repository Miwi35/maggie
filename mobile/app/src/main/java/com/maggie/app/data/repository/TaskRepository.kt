package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TaskCreateRequest
import com.maggie.app.data.local.dao.TaskDao
import com.maggie.app.data.local.entity.SyncStatus
import com.maggie.app.data.local.entity.TaskEntity
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DayPlanUpdateRequest
import com.maggie.app.data.model.DayProposal
import com.maggie.app.data.model.DayReview
import com.maggie.app.data.model.DayReviewUpdateRequest
import com.maggie.app.data.model.DaySlotRequest
import com.maggie.app.data.model.Reward
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskSettings
import com.maggie.app.data.model.TaskView
import com.maggie.app.data.model.TaskViewResponse
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

class TaskRepository(
    private val apiService: MaggieApiService,
    private val taskDao: TaskDao,
) {
    fun observeTasks(): Flow<List<Task>> =
        taskDao.observeAll().map { entities ->
            entities.map { it.toModel() }
        }

    fun observePendingTasks(): Flow<List<Task>> =
        taskDao.observePending().map { entities ->
            entities.map { it.toModel() }
        }

    suspend fun refreshTasks(): Result<List<Task>> = runCatching {
        val serverTasks = apiService.getTasks()
        val serverIds = serverTasks.map { it.id }.toSet()

        taskDao.upsertAll(serverTasks.map { TaskEntity.fromModel(it) })

        val localSyncedIds = taskDao.getIdsByStatus(SyncStatus.SYNCED)
        val staleIds = localSyncedIds.filter { it !in serverIds }
        if (staleIds.isNotEmpty()) {
            taskDao.deleteSyncedByIds(staleIds)
        }

        serverTasks
    }

    /** The cached task, else the server's. Null when neither has it. */
    suspend fun findTask(id: String): Task? =
        taskDao.getById(id)?.toModel()
            ?: runCatching { apiService.getTask(id) }.rethrowCancellation().getOrNull()?.also {
                taskDao.upsertAll(listOf(TaskEntity.fromModel(it)))
            }

    suspend fun getUndoneTasks(dueDateBefore: String? = null): List<Task> {
        return apiService.getUndoneTasks(dueDateBefore)
    }

    /** The subtasks of [parentId], in their order, cached as they come (MAG-404). */
    suspend fun getSubtasks(parentId: String): Result<List<Task>> = runCatching {
        val subtasks = apiService.getSubtasks(Task.iriOf(parentId))
        taskDao.upsertAll(subtasks.map { TaskEntity.fromModel(it) })
        subtasks.sortedWith(Task.SIBLING_ORDER)
    }

    /** A new subtask of [parentId]; the API puts it last among its siblings. */
    suspend fun createSubtask(parentId: String, title: String): Result<Task> =
        createTask(TaskCreateRequest(title = title, parent = Task.iriOf(parentId)))

    /**
     * Moves [subtask] past its adjacent sibling [neighbour]: one PATCH on the moved subtask
     * with the neighbour's position, and the API swaps the two in one operation (and moves
     * the task on Google). Google's positions are opaque strings, so the app never makes one
     * up. Returns both: the moved one as the server answered, the neighbour with the moved
     * one's old position, as the server gave it.
     */
    suspend fun moveSubtask(subtask: Task, neighbour: Task): Result<List<Task>> = runCatching {
        val moved = apiService.updateTask(subtask.id, buildJsonObject { put("position", neighbour.position) })
        listOf(moved, neighbour.copy(position = subtask.position))
            .also { tasks -> taskDao.upsertAll(tasks.map { TaskEntity.fromModel(it) }) }
    }

    /** « Aujourd'hui » as the API computes it: due today or late, most late first, subtasks under their task (MAG-405). */
    suspend fun getTodayTasks(): List<Task> {
        return apiService.getTodayTasks()
    }

    /** One read of a view (MAG-408): its tasks, the counts of the four views, and for « done » the page asked. */
    suspend fun getTaskView(view: TaskView, page: Int? = null): Result<TaskViewResponse> = runCatching {
        apiService.getTaskView(view.key, page)
    }.rethrowCancellation()

    /**
     * The server's task, else the cached one: the day lists a shorter task, and its detail must not
     * show a cache older than the row it was opened from.
     */
    suspend fun fetchTask(id: String): Result<Task> = runCatching {
        apiService.getTask(id).also { taskDao.upsertAll(listOf(TaskEntity.fromModel(it))) }
    }.rethrowCancellation().recoverCatching { error ->
        taskDao.getById(id)?.toModel() ?: throw error
    }.rethrowCancellation()

    /** The day of [date] (MAG-497), created empty on its first read. */
    suspend fun getDayPlan(date: LocalDate): Result<DayPlan> = runCatching {
        apiService.getDayPlan(date.toString())
    }.rethrowCancellation()

    /** The morning's five for [date] (MAG-498): nothing is written until [setDaySlots]. */
    suspend fun getDayProposal(date: LocalDate): Result<DayProposal> = runCatching {
        apiService.getDayProposal(date.toString())
    }.rethrowCancellation()

    /** The user's morning time, `HH:MM` (« Réglages › Tâches »). */
    suspend fun getMorningTime(): Result<String> = runCatching {
        apiService.getTaskSettings().morningTime
    }.rethrowCancellation()

    /** The user's evening time, `HH:MM` (« Réglages › Tâches », MAG-500). */
    suspend fun getEveningTime(): Result<String> = runCatching {
        apiService.getTaskSettings().eveningTime
    }.rethrowCancellation()

    /** The user's task settings, for how a task done is rewarded (MAG-508). */
    suspend fun getTaskSettings(): Result<TaskSettings> = runCatching {
        apiService.getTaskSettings()
    }.rethrowCancellation()

    /** The reward ticking [taskId] earned (MAG-508); `null` when it earned none. */
    suspend fun getTaskReward(taskId: String): Result<Reward?> = runCatching {
        apiService.getReward(Reward.SUBJECT_TASK, taskId)
    }.rethrowCancellation()

    /** The evening's review of [date] (MAG-500), created empty on its first read. */
    suspend fun getDayReview(date: LocalDate): Result<DayReview> = runCatching {
        apiService.getDayReview(date.toString())
    }.rethrowCancellation()

    /** « Bonne nuit »: records [helped], the whole list, and closes the review of [date]. */
    suspend fun saveDayReview(date: LocalDate, helped: List<String>): Result<DayReview> = runCatching {
        apiService.updateDayReview(date.toString(), DayReviewUpdateRequest(helped))
    }.rethrowCancellation()

    /**
     * Replaces the day's slots by [taskIds], in order, five at most: what is left out goes back to the reserve.
     * [confirm] `false` writes without settling the day (the evening's « Demain »).
     */
    suspend fun setDaySlots(date: LocalDate, taskIds: List<String>, confirm: Boolean = true): Result<DayPlan> = runCatching {
        apiService.updateDayPlan(
            date.toString(),
            DayPlanUpdateRequest(taskIds.map(DaySlotRequest::task), confirm = confirm),
        )
    }.rethrowCancellation()

    /** Sets or clears [taskId]'s due day, `YYYY-MM-DD` — « Mettre de côté » clears it (MAG-500). */
    suspend fun setDueDate(taskId: String, dueDate: String?): Result<Task> =
        updateTask(taskId, buildJsonObject { put("dueDate", dueDate) })

    suspend fun toggleDone(taskId: String, done: Boolean): Result<Task> = runCatching {
        val data = buildJsonObject {
            put("completedAt", if (done) java.time.Instant.now().toString() else null)
        }
        val task = apiService.updateTask(taskId, data)
        taskDao.upsertAll(listOf(TaskEntity.fromModel(task)))
        task
    }

    suspend fun createTask(request: TaskCreateRequest): Result<Task> = runCatching {
        val task = apiService.createTask(request)
        taskDao.upsertAll(listOf(TaskEntity.fromModel(task)))
        task
    }

    suspend fun updateTask(id: String, data: JsonObject): Result<Task> = runCatching {
        val task = apiService.updateTask(id, data)
        taskDao.upsertAll(listOf(TaskEntity.fromModel(task)))
        task
    }

    suspend fun deleteTask(id: String): Result<Unit> = runCatching {
        apiService.deleteTask(id)
        taskDao.deleteById(id)
    }
}
