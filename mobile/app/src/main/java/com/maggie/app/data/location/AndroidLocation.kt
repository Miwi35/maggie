package com.maggie.app.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import java.time.Instant
import kotlin.coroutines.resume

private const val TAG = "DepartureLocation"

class AndroidLocationPermissions(private val context: Context) : LocationPermissions {
    override fun hasFineLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    override fun hasBackgroundLocation() = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    override fun hasActivityRecognition() = granted(Manifest.permission.ACTIVITY_RECOGNITION)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** One request to the fused provider: the best fix of [priority] within [timeoutMs], not older than [maxAgeMs]. Null when there is none. */
interface FixProvider {
    suspend fun fix(priority: Int, timeoutMs: Long, maxAgeMs: Long): Position?
}

/**
 * A fix of the fused provider. [PositionAccuracy.HIGH] asks the GPS and waits up to [GPS_TIMEOUT_MS]; with no
 * fix by then, the best position the cheap sources have is sent with its true precision rather than nothing.
 */
class FusedPositionSource(private val provider: FixProvider) : PositionSource {

    constructor(context: Context) : this(GmsFixProvider(context))

    override suspend fun current(accuracy: PositionAccuracy): Position? = try {
        when (accuracy) {
            PositionAccuracy.HIGH ->
                provider.fix(Priority.PRIORITY_HIGH_ACCURACY, GPS_TIMEOUT_MS, GPS_MAX_AGE_MS)
                    ?: provider.fix(Priority.PRIORITY_BALANCED_POWER_ACCURACY, FALLBACK_TIMEOUT_MS, MAX_AGE_MS)
            PositionAccuracy.BALANCED ->
                provider.fix(Priority.PRIORITY_BALANCED_POWER_ACCURACY, BALANCED_TIMEOUT_MS, MAX_AGE_MS)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "No position: ${e.message}")
        null
    }

    private companion object {
        const val GPS_TIMEOUT_MS = 30_000L
        const val FALLBACK_TIMEOUT_MS = 10_000L
        const val BALANCED_TIMEOUT_MS = 20_000L
        const val GPS_MAX_AGE_MS = 10_000L
        const val MAX_AGE_MS = 60_000L
    }
}

class GmsFixProvider(private val context: Context) : FixProvider {

    @SuppressLint("MissingPermission")
    override suspend fun fix(priority: Int, timeoutMs: Long, maxAgeMs: Long): Position? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationTokenSource()
                continuation.invokeOnCancellation { cancellation.cancel() }
                val request = CurrentLocationRequest.Builder()
                    .setPriority(priority)
                    .setMaxUpdateAgeMillis(maxAgeMs)
                    .setDurationMillis(timeoutMs)
                    .build()
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(request, cancellation.token)
                    .addOnSuccessListener { location ->
                        continuation.resume(
                            location?.let {
                                Position(it.latitude, it.longitude, it.accuracy.toDouble(), Instant.ofEpochMilli(it.time))
                            },
                        )
                    }
                    .addOnFailureListener { continuation.resume(null) }
            }
        }
}

/** Leaving a vehicle, and standing on one's feet — the two transitions [ParkingDetector] reads. */
class ActivityTransitionRegistrar(private val context: Context) : TransitionRegistrar {

    @SuppressLint("MissingPermission")
    override fun register() {
        val transitions = listOf(
            transition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            transition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
            transition(DetectedActivity.ON_FOOT, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            transition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
        )
        guarded("register") {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent())
                .addOnFailureListener { Log.w(TAG, "Transitions refused: ${it.message}") }
        }
    }

    @SuppressLint("MissingPermission")
    override fun unregister() {
        guarded("unregister") {
            ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent())
        }
    }

    private fun transition(activity: Int, type: Int) =
        ActivityTransition.Builder().setActivityType(activity).setActivityTransition(type).build()

    // Mutable: Play services fills the intent with the result. Explicit, so nothing else can answer it.
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ActivityTransitionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    // A phone without Play services, or a permission taken back, must never take the app down.
    private fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Transitions $what failed: ${e.message}")
        }
    }
}

/** The system delivers the transitions here, even with the app closed; the work is handed to WorkManager. */
class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val koin = GlobalContext.getOrNull() ?: return
        val events = TransitionEvent.from(result, SystemClock.elapsedRealtimeNanos(), Instant.now())
        try {
            koin.get<LocationScheduler>().handleTransitions(events)
        } catch (e: Exception) {
            Log.w(TAG, "Transition not handled: ${e.message}")
        }
    }
}
