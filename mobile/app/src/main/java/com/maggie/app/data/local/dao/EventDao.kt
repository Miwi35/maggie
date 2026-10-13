package com.maggie.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maggie.app.data.local.entity.EventEntity
import com.maggie.app.data.local.entity.FetchedPeriodEntity
import com.maggie.app.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Query("SELECT * FROM events ORDER BY COALESCE(startAt, startDate) ASC")
    fun observeAll(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE COALESCE(startAt, startDate) >= :start AND COALESCE(startAt, startDate) < :end ORDER BY COALESCE(startAt, startDate) ASC")
    fun observeInRange(start: String, end: String): Flow<List<EventEntity>>

    @Query("SELECT * FROM events ORDER BY COALESCE(startAt, startDate) ASC")
    suspend fun getAll(): List<EventEntity>

    @Query("SELECT * FROM events WHERE rrule IS NOT NULL AND COALESCE(startAt, startDate) < :before ORDER BY COALESCE(startAt, startDate) ASC")
    suspend fun getRecurringBefore(before: String): List<EventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<EventEntity>)

    @Query("SELECT id FROM events WHERE syncStatus = :status")
    suspend fun getIdsByStatus(status: SyncStatus = SyncStatus.SYNCED): List<String>

    @Query("DELETE FROM events WHERE id IN (:ids) AND syncStatus = :status")
    suspend fun deleteSyncedByIds(ids: List<String>, status: SyncStatus = SyncStatus.SYNCED)

    @Query("SELECT * FROM events WHERE syncStatus = 'SYNCED'")
    suspend fun getSynced(): List<EventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFetchedPeriod(period: FetchedPeriodEntity)

    /** Whether one read made since [since] (epoch ms) holds the whole of [firstDay]..[endDay]. */
    @Query("SELECT COUNT(*) FROM fetched_periods WHERE firstDay <= :firstDay AND endDay >= :endDay AND fetchedAt >= :since")
    suspend fun countFetchedPeriodsCovering(firstDay: String, endDay: String, since: Long): Int

    @Query("SELECT * FROM fetched_periods WHERE fetchedAt >= :since ORDER BY fetchedAt DESC")
    suspend fun getFetchedPeriodsSince(since: Long): List<FetchedPeriodEntity>

    @Query("DELETE FROM events WHERE agenda = :agendaIri AND syncStatus = 'SYNCED'")
    suspend fun deleteSyncedOfAgenda(agendaIri: String)

    @Query("DELETE FROM fetched_periods")
    suspend fun deleteFetchedPeriods()

    @Query("DELETE FROM fetched_periods WHERE fetchedAt < :before")
    suspend fun deleteFetchedPeriodsBefore(before: Long)

    @Query("SELECT * FROM events WHERE syncStatus != 'SYNCED'")
    suspend fun getPending(): List<EventEntity>

    @Query("SELECT * FROM events WHERE id = :eventId")
    suspend fun getById(eventId: String): EventEntity?

    @Query("DELETE FROM events WHERE id = :eventId")
    suspend fun deleteById(eventId: String)
}
