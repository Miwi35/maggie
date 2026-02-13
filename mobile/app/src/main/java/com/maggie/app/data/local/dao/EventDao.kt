package com.maggie.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maggie.app.data.local.entity.EventEntity
import com.maggie.app.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Query("SELECT * FROM events ORDER BY startAt ASC")
    fun observeAll(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE startAt >= :start AND startAt < :end ORDER BY startAt ASC")
    fun observeInRange(start: String, end: String): Flow<List<EventEntity>>

    @Query("SELECT * FROM events ORDER BY startAt ASC")
    suspend fun getAll(): List<EventEntity>

    @Query("SELECT * FROM events WHERE rrule IS NOT NULL AND startAt < :before ORDER BY startAt ASC")
    suspend fun getRecurringBefore(before: String): List<EventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<EventEntity>)

    @Query("SELECT id FROM events WHERE syncStatus = :status")
    suspend fun getIdsByStatus(status: SyncStatus = SyncStatus.SYNCED): List<String>

    @Query("DELETE FROM events WHERE id IN (:ids) AND syncStatus = :status")
    suspend fun deleteSyncedByIds(ids: List<String>, status: SyncStatus = SyncStatus.SYNCED)

    @Query("SELECT * FROM events WHERE syncStatus != 'SYNCED'")
    suspend fun getPending(): List<EventEntity>

    @Query("DELETE FROM events WHERE id = :eventId")
    suspend fun deleteById(eventId: String)
}
