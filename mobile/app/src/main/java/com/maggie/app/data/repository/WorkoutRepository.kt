package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.SportWorkoutCreate
import com.maggie.app.data.api.SportWorkoutFinish
import com.maggie.app.data.api.WorkoutWriteRequest
import com.maggie.app.data.model.Workout

/** The owner's training sessions (MAG-705): the full list, newest first, and its writes. */
class WorkoutRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getWorkouts(): Result<List<Workout>> = runCatching { apiService.getWorkouts() }

    suspend fun getWorkout(id: String): Result<Workout?> = runCatching { apiService.getWorkout(id) }

    suspend fun getWorkoutOfEvent(eventId: String): Result<Workout?> = runCatching { apiService.getWorkoutOfEvent(eventId) }

    suspend fun getWorkoutsWithExercise(slug: String): Result<List<Workout>> = runCatching { apiService.getWorkoutsWithExercise(slug) }

    suspend fun createWorkout(request: WorkoutWriteRequest): Result<Workout> = runCatching {
        apiService.createWorkout(request)
    }

    suspend fun updateWorkout(id: String, request: WorkoutWriteRequest): Result<Workout> = runCatching {
        apiService.updateWorkout(id, request)
    }

    suspend fun createSportWorkout(request: SportWorkoutCreate): Result<Workout> = runCatching { apiService.createSportWorkout(request) }

    suspend fun finishSportWorkout(id: String, request: SportWorkoutFinish): Result<Workout> = runCatching {
        apiService.finishSportWorkout(id, request)
    }

    suspend fun deleteWorkout(id: String): Result<Unit> = runCatching { apiService.deleteWorkout(id) }

    /** The Health Connect id of the record Maggie wrote for the workout (MAG-708). */
    suspend fun setExternalId(id: String, externalId: String): Result<Workout> = runCatching {
        apiService.setWorkoutExternalId(id, externalId)
    }
}
