package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Errand

/** One page of past errands, and how many there are in all (null when the API did not say). */
data class ErrandPage(val errands: List<Errand>, val totalItems: Int?)

/** The past errands (MAG-488): read-only, written by « Terminer la course » alone. */
class ErrandRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getErrands(page: Int = 1): Result<ErrandPage> = runCatching {
        val collection = apiService.getErrands(page)
        ErrandPage(collection.member, collection.totalItems)
    }

    suspend fun getErrand(id: String): Result<Errand> = runCatching {
        apiService.getErrand(id)
    }
}
