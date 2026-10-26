package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import com.maggie.app.data.model.ActivityStatusRequest
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The activities in progress (MAG-613, `/api/activity-status/{activity}`): read as they stand, written by
 * one [ActivityStatusWriter] per activity.
 */
class ActivityStatusRepository(
    private val apiService: MaggieApiService,
    private val sendScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val writers = ConcurrentHashMap<String, ActivityStatusWriter>()

    private fun writer(activity: String): ActivityStatusWriter = writers.getOrPut(activity) {
        ActivityStatusWriter(sendScope) { write ->
            when (write) {
                is ActivityWrite.Put -> apiService.putActivityStatus(activity, write.request)
                ActivityWrite.Delete -> apiService.deleteActivityStatus(activity)
            }
        }
    }

    /** The status after every write handed over so far landed; `null` when nothing is in progress. */
    suspend fun get(activity: String): Result<ActivityStatus?> = try {
        writer(activity).idle()
        Result.success(apiService.getActivityStatus(activity))
    } catch (e: Exception) {
        Result.failure<ActivityStatus?>(e).rethrowCancellation()
    }

    fun put(activity: String, origin: ActivityOrigin, state: JsonObject) =
        writer(activity).put(ActivityStatusRequest(origin, state))

    fun delete(activity: String) = writer(activity).delete()
}
