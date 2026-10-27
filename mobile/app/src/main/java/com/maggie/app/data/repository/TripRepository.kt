package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Trip
import java.time.LocalDate

/** The trips Maggie chose, read from `/api/trips`. Written only by her (`set_trip`): the app never edits them. */
class TripRepository(
    private val apiService: MaggieApiService,
) {
    /** The trips leaving on [day], read in Paris, soonest first. */
    suspend fun getTrips(day: LocalDate): Result<List<Trip>> = runCatching {
        apiService.getTrips(day.toString())
    }
}
