package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Weather
import com.maggie.app.util.rethrowCancellation
import java.time.LocalDate

/** The day's weather in the user's city, read from `/api/weather`; a failure (no city, service down) is a failure. */
class WeatherRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun read(day: LocalDate): Result<Weather> = runCatching {
        apiService.getWeather(day.toString())
    }.rethrowCancellation()
}
