package com.maggie.app.data.repository

import com.maggie.app.data.api.CategoryCreateRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Category

class CategoryRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getCategories(): Result<List<Category>> = runCatching {
        apiService.getCategories()
    }

    suspend fun createCategory(request: CategoryCreateRequest): Result<Category> = runCatching {
        apiService.createCategory(request)
    }

    suspend fun deleteCategory(id: String): Result<Unit> = runCatching {
        apiService.deleteCategory(id)
    }
}
