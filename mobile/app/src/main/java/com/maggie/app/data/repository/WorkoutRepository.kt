package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.WorkoutWriteRequest
import com.maggie.app.data.model.Workout

/** The owner's training sessions (MAG-705): the full list, newest first, and its writes. */
class WorkoutRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getWorkouts(): Result<List<Workout>> = runCatching { apiService.getWorkouts() }

    suspend fun createWorkout(request: WorkoutWriteRequest): Result<Workout> = runCatching {
        apiService.createWorkout(request)
    }

    suspend fun updateWorkout(id: String, request: WorkoutWriteRequest): Result<Workout> = runCatching {
        apiService.updateWorkout(id, request)
    }

    suspend fun deleteWorkout(id: String): Result<Unit> = runCatching { apiService.deleteWorkout(id) }
}
