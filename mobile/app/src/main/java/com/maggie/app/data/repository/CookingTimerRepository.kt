package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.CookingTimer
import com.maggie.app.data.model.CookingTimerAction
import com.maggie.app.data.model.CookingTimerChangeRequest
import com.maggie.app.data.model.CookingTimerStartRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The timers of the Cuisine activity (MAG-582); the API is the authority on when each one ends. */
class CookingTimerRepository(
    private val apiService: MaggieApiService,
    /** Outlives the activity: « Quitter » leaves at once and the cancellations still go out. */
    private val sendScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** Running, paused and rung ones. */
    suspend fun active(): Result<List<CookingTimer>> = runCatching { apiService.getCookingTimers() }

    suspend fun start(label: String, recipeId: String, stepPosition: Int, durationSeconds: Int): Result<CookingTimer> = runCatching {
        apiService.startCookingTimer(CookingTimerStartRequest(label, recipeId, stepPosition, durationSeconds))
    }

    suspend fun change(id: String, action: CookingTimerAction, seconds: Int? = null): Result<CookingTimer> = runCatching {
        apiService.changeCookingTimer(id, CookingTimerChangeRequest(action.wire, seconds.takeIf { action == CookingTimerAction.ADD }))
    }

    /** Fire and forget: the activity is already gone when they land. */
    fun cancelInBackground(ids: List<String>) {
        ids.forEach { id -> sendScope.launch { change(id, CookingTimerAction.CANCEL) } }
    }
}
