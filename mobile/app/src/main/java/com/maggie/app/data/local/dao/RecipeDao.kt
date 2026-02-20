package com.maggie.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maggie.app.data.local.entity.RecipeEntity
import com.maggie.app.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY name ASC")
    fun observeAll(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes ORDER BY name ASC")
    suspend fun getAll(): List<RecipeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(recipes: List<RecipeEntity>)

    @Query("SELECT id FROM recipes WHERE syncStatus = :status")
    suspend fun getIdsByStatus(status: SyncStatus = SyncStatus.SYNCED): List<String>

    @Query("DELETE FROM recipes WHERE id IN (:ids) AND syncStatus = :status")
    suspend fun deleteSyncedByIds(ids: List<String>, status: SyncStatus = SyncStatus.SYNCED)

    @Query("DELETE FROM recipes WHERE id = :recipeId")
    suspend fun deleteById(recipeId: String)
}
