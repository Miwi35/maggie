package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.RecipeCreateRequest
import com.maggie.app.data.local.dao.RecipeDao
import com.maggie.app.data.local.entity.RecipeEntity
import com.maggie.app.data.local.entity.SyncStatus
import com.maggie.app.data.model.Recipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

class RecipeRepository(
    private val apiService: MaggieApiService,
    private val recipeDao: RecipeDao,
) {
    fun observeRecipes(): Flow<List<Recipe>> =
        recipeDao.observeAll().map { entities ->
            entities.map { it.toModel() }
        }

    suspend fun refreshRecipes(): Result<List<Recipe>> = runCatching {
        val serverRecipes = apiService.getRecipes()
        val serverIds = serverRecipes.map { it.id }.toSet()

        recipeDao.upsertAll(serverRecipes.map { RecipeEntity.fromModel(it) })

        val localSyncedIds = recipeDao.getIdsByStatus(SyncStatus.SYNCED)
        val staleIds = localSyncedIds.filter { it !in serverIds }
        if (staleIds.isNotEmpty()) {
            recipeDao.deleteSyncedByIds(staleIds)
        }

        serverRecipes
    }

    suspend fun getRecipe(id: String): Result<Recipe> = runCatching {
        apiService.getRecipe(id)
    }

    suspend fun createRecipe(request: RecipeCreateRequest): Result<Recipe> = runCatching {
        val recipe = apiService.createRecipe(request)
        recipeDao.upsertAll(listOf(RecipeEntity.fromModel(recipe)))
        recipe
    }

    suspend fun updateRecipe(id: String, data: JsonObject): Result<Recipe> = runCatching {
        val recipe = apiService.updateRecipe(id, data)
        recipeDao.upsertAll(listOf(RecipeEntity.fromModel(recipe)))
        recipe
    }

    suspend fun getMealCountOfDeletion(id: String): Result<Int> = runCatching {
        apiService.getRecipeDeletionImpact(id).mealCount
    }

    suspend fun deleteRecipe(id: String): Result<Unit> = runCatching {
        apiService.deleteRecipe(id)
        recipeDao.deleteById(id)
    }
}
