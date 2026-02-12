package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.local.dao.TaskDao
import com.maggie.app.data.local.entity.SyncStatus
import com.maggie.app.data.local.entity.TaskEntity
import com.maggie.app.data.model.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
}
