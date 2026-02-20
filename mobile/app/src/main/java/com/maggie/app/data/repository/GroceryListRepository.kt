package com.maggie.app.data.repository

import com.maggie.app.data.api.GroceryListCreateRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.GroceryList
import kotlinx.serialization.json.JsonObject

class GroceryListRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getGroceryLists(): Result<List<GroceryList>> = runCatching {
        apiService.getGroceryLists()
    }

    suspend fun getGroceryList(id: String): Result<GroceryList> = runCatching {
        apiService.getGroceryList(id)
    }

    suspend fun createGroceryList(request: GroceryListCreateRequest): Result<GroceryList> = runCatching {
        apiService.createGroceryList(request)
    }

    suspend fun updateGroceryList(id: String, data: JsonObject): Result<GroceryList> = runCatching {
        apiService.updateGroceryList(id, data)
    }
}
