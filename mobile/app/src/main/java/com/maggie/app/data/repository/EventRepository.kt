package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Event

class EventRepository(private val apiService: MaggieApiService) {
    suspend fun getEvents(): Result<List<Event>> = runCatching {
        apiService.getEvents()
    }
}
