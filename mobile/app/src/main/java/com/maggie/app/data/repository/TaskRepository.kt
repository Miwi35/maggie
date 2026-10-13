package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TaskCreateRequest
import com.maggie.app.data.local.dao.TaskDao
import com.maggie.app.data.local.entity.SyncStatus
import com.maggie.app.data.local.entity.TaskEntity
import com.maggie.app.data.model.Task
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
     * Moves two adjacent siblings past each other by swapping their positions: Google's
     * positions are opaque strings, so the app never makes one up. Returns both, updated.
     */
    suspend fun swapPositions(first: Task, second: Task): Result<List<Task>> = runCatching {
        listOf(
            apiService.updateTask(first.id, buildJsonObject { put("position", second.position) }),
            apiService.updateTask(second.id, buildJsonObject { put("position", first.position) }),
        ).also { tasks -> taskDao.upsertAll(tasks.map { TaskEntity.fromModel(it) }) }
    }

    suspend fun getUndoneUndatedTasks(): List<Task> {
        return apiService.getUndoneUndatedTasks()
    }

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
