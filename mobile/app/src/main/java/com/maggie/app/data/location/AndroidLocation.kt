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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

/** A fix of the fused provider, at most a minute old, within [TIMEOUT_MS]. */
class FusedPositionSource(private val context: Context) : PositionSource {

    @SuppressLint("MissingPermission")
    override suspend fun current(): Position? = try {
        withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationTokenSource()
                continuation.invokeOnCancellation { cancellation.cancel() }
                val request = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    .setMaxUpdateAgeMillis(MAX_AGE_MS)
                    .setDurationMillis(TIMEOUT_MS)
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "No position: ${e.message}")
        null
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val MAX_AGE_MS = 60_000L
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

/** The system delivers the transitions here, even with the app closed. */
class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val koin = GlobalContext.getOrNull() ?: return
        val events = TransitionEvent.from(result, SystemClock.elapsedRealtimeNanos(), Instant.now())
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                koin.get<ActivityTransitionHandler>().handle(events)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Transition not handled: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
