package com.maggie.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maggie.app.data.local.entity.AgendaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgendaDao {

    @Query("SELECT * FROM agendas ORDER BY name ASC")
    fun observeAll(): Flow<List<AgendaEntity>>

    @Query("SELECT * FROM agendas ORDER BY name ASC")
    suspend fun getAll(): List<AgendaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(agendas: List<AgendaEntity>)

    @Query("DELETE FROM agendas")
    suspend fun deleteAll()
}
