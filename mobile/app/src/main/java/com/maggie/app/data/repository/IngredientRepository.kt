package com.maggie.app.data.repository

import com.maggie.app.data.api.IngredientCreateRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Ingredient

class IngredientRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getIngredients(): Result<List<Ingredient>> = runCatching {
        apiService.getIngredients()
    }

    suspend fun createIngredient(request: IngredientCreateRequest): Result<Ingredient> = runCatching {
        apiService.createIngredient(request)
    }
}
