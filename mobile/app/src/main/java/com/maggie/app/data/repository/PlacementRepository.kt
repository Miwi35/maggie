package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.PlacementWriteRequest
import com.maggie.app.data.model.Placement
import com.maggie.app.data.model.PlacementSimulation

/** The owner's simulated placements (MAG-555): the list, its writes, and one placement month by month. */
class PlacementRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getPlacements(): Result<List<Placement>> = runCatching { apiService.getPlacements() }

    suspend fun createPlacement(request: PlacementWriteRequest): Result<Placement> = runCatching {
        apiService.createPlacement(request)
    }

    suspend fun updatePlacement(id: String, request: PlacementWriteRequest): Result<Placement> = runCatching {
        apiService.updatePlacement(id, request)
    }

    suspend fun deletePlacement(id: String): Result<Unit> = runCatching { apiService.deletePlacement(id) }

    suspend fun getSimulation(id: String): Result<PlacementSimulation> = runCatching {
        apiService.getPlacementSimulation(id)
    }
}
