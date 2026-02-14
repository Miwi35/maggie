package com.maggie.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maggie.app.data.local.entity.SyncStatus
import com.maggie.app.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks ORDER BY dueDate ASC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE completedAt IS NULL ORDER BY dueDate ASC")
    fun observePending(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY dueDate ASC")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE completedAt IS NULL AND dueDate IS NOT NULL AND dueDate < :before ORDER BY dueDate ASC")
    suspend fun getUndoneWithDueDateBefore(before: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE completedAt IS NULL AND dueDate IS NULL ORDER BY criticality DESC")
    suspend fun getUndoneWithoutDueDate(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Query("SELECT id FROM tasks WHERE syncStatus = :status")
    suspend fun getIdsByStatus(status: SyncStatus = SyncStatus.SYNCED): List<String>

    @Query("DELETE FROM tasks WHERE id IN (:ids) AND syncStatus = :status")
    suspend fun deleteSyncedByIds(ids: List<String>, status: SyncStatus = SyncStatus.SYNCED)

    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteById(taskId: String)
}
