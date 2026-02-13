package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TaskCreateRequest
import com.maggie.app.data.local.dao.TaskDao
import com.maggie.app.data.local.entity.SyncStatus
import com.maggie.app.data.local.entity.TaskEntity
import com.maggie.app.data.model.Task
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

    suspend fun getUndoneTasks(dueDateBefore: String? = null): List<Task> {
        return apiService.getUndoneTasks(dueDateBefore)
    }

    suspend fun getUndoneUndatedTasks(): List<Task> {
        return apiService.getUndoneUndatedTasks()
    }

    suspend fun toggleDone(taskId: String, done: Boolean): Result<Task> = runCatching {
        val data = buildJsonObject {
            put("doneDate", if (done) java.time.Instant.now().toString() else null)
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
