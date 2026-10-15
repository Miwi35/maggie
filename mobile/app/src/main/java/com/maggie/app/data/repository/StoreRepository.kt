package com.maggie.app.data.repository

import com.maggie.app.data.api.AisleCreateRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.StoreCreateRequest
import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.Store
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    /** The store's aisles, in the order of the way through it (MAG-493). */
    suspend fun getAisles(storeId: String): Result<List<Aisle>> = runCatching {
        apiService.getAisles(storeId).sortedBy { it.position }
    }

    /** Created last in the store. */
    suspend fun createAisle(storeId: String, name: String): Result<Aisle> = runCatching {
        apiService.createAisle(AisleCreateRequest(store = "/api/stores/$storeId", name = name))
    }

    suspend fun renameAisle(id: String, name: String): Result<Aisle> = runCatching {
        apiService.updateAisle(id, buildJsonObject { put("name", name) })
    }

    /** Moves it to [position] (1 = first): one request, the API renumbers the others. */
    suspend fun moveAisle(id: String, position: Int): Result<Aisle> = runCatching {
        apiService.updateAisle(id, buildJsonObject { put("position", position) })
    }

    /** Its products become « Sans rayon » in that store. */
    suspend fun deleteAisle(id: String): Result<Unit> = runCatching {
        apiService.deleteAisle(id)
    }
}
