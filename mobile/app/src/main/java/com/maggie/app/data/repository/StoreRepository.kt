package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.StoreCreateRequest
import com.maggie.app.data.model.Store

class StoreRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getStores(): Result<List<Store>> = runCatching {
        apiService.getStores()
    }

    suspend fun createStore(request: StoreCreateRequest): Result<Store> = runCatching {
        apiService.createStore(request)
    }

    suspend fun deleteStore(id: String): Result<Unit> = runCatching {
        apiService.deleteStore(id)
    }
}
