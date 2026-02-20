package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.UserPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

class UserPreferenceRepository(
    private val apiService: MaggieApiService,
) {
    private val _preference = MutableStateFlow<UserPreference?>(null)
    val preference: StateFlow<UserPreference?> = _preference

    suspend fun refresh(): Result<UserPreference> {
        return try {
            val pref = apiService.getUserPreferences()
            _preference.value = pref
            Result.success(pref)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun update(data: JsonObject): Result<UserPreference> {
        return try {
            val pref = apiService.updateUserPreferences(data)
            _preference.value = pref
            Result.success(pref)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
