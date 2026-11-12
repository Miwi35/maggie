package com.maggie.app.data.repository

import com.maggie.app.data.api.BodyMeasurementWriteRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.BodyMeasurement

/** The owner's body measurements (MAG-706): those since a date, newest first, a new one, « Écarter » and delete. */
class BodyMeasurementRepository(
    private val apiService: MaggieApiService,
) {
    /** [after]: an ISO date-time with its offset. */
    suspend fun getBodyMeasurements(after: String): Result<List<BodyMeasurement>> =
        runCatching { apiService.getBodyMeasurements(after) }

    suspend fun createBodyMeasurement(request: BodyMeasurementWriteRequest): Result<BodyMeasurement> =
        runCatching { apiService.createBodyMeasurement(request) }

    suspend fun deleteBodyMeasurement(id: String): Result<Unit> =
        runCatching { apiService.deleteBodyMeasurement(id) }

    suspend fun setExcluded(id: String, excluded: Boolean): Result<BodyMeasurement> =
        runCatching { apiService.setBodyMeasurementExcluded(id, excluded) }
}
