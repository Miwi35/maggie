package com.maggie.app.data.repository

import com.maggie.app.data.api.CategoryCreateRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Category
import kotlinx.serialization.json.JsonObject

class CategoryRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getCategories(): Result<List<Category>> = runCatching {
        apiService.getCategories()
    }

    suspend fun createCategory(request: CategoryCreateRequest): Result<Category> = runCatching {
        apiService.createCategory(request)
    }

    suspend fun updateCategory(id: String, changes: JsonObject): Result<Category> = runCatching {
        apiService.updateCategory(id, changes)
    }

    suspend fun countRules(ids: Set<String>): Result<Int?> = runCatching {
        apiService.countCategorizationRulesOf(ids)
    }

    suspend fun countTransactions(id: String): Result<Int> = runCatching {
        apiService.countTransactionsOfCategory(id)
    }

    suspend fun deleteCategory(id: String): Result<Unit> = runCatching {
        apiService.deleteCategory(id)
    }
}
