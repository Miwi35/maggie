package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Routine
import com.maggie.app.util.rethrowCancellation
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/** The routines (MAG-511), read and written straight on the API: nothing is cached. */
class RoutineRepository(private val apiService: MaggieApiService) {

    suspend fun getRoutines(): Result<List<Routine>> = runCatching {
        apiService.getRoutines()
    }.rethrowCancellation()

    suspend fun createRoutine(data: JsonObject): Result<Routine> = runCatching {
        apiService.createRoutine(data)
    }.rethrowCancellation()

    /** [data] holds only the fields that change. */
    suspend fun updateRoutine(id: String, data: JsonObject): Result<Routine> = runCatching {
        apiService.updateRoutine(id, data)
    }.rethrowCancellation()

    suspend fun deleteRoutine(id: String): Result<Unit> = runCatching {
        apiService.deleteRoutine(id)
    }.rethrowCancellation()

    /** Ticks or unticks [date]; the API answers the routine with its counts. */
    suspend fun setChecked(id: String, date: LocalDate, checked: Boolean): Result<Routine> = runCatching {
        apiService.setRoutineCheck(id, date.toString(), checked)
    }.rethrowCancellation()
}
