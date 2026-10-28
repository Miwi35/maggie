package com.maggie.app.data.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DepartureTrip"

internal fun android.location.Location.toPosition() =
    Position(latitude, longitude, accuracy.toDouble(), Instant.ofEpochMilli(time), FixSource.of(provider, accuracy.toDouble()))

/** GPS fixes of the fused provider every [intervalMs], for as long as the flow is collected. */
class GmsLocationUpdates(private val context: Context) : LocationUpdates {

    @SuppressLint("MissingPermission")
    override fun gps(intervalMs: Long): Flow<Position> = callbackFlow {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { trySend(it.toPosition()) }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs).build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it) }
        awaitClose { client.removeLocationUpdates(callback) }
    }
}

/**
 * Connected to Android Auto — `CarConnection`, which answers on the main thread. Not connected only once it
 * has said so, or stayed silent for [SILENCE_MS]: a `false` sent before its answer would end the trip service
 * at the very moment the car connects.
 */
class AndroidCarConnection(private val context: Context) : CarConnectionSource {

    override val connected: Flow<Boolean> = callbackFlow {
        val main = Handler(Looper.getMainLooper())
        val answered = AtomicBoolean(false)
        val observer = Observer<Int> {
            answered.set(true)
            trySend(it == CarConnection.CONNECTION_TYPE_PROJECTION)
        }
        // `CarConnection` builds a Handler: it must be created on the main thread, not on this collector's worker.
        var connection: CarConnection? = null
        main.post {
            connection = CarConnection(context.applicationContext).also { it.type.observeForever(observer) }
        }
        val silence = launch {
            delay(SILENCE_MS)
            if (answered.compareAndSet(false, true)) trySend(false)
        }
        awaitClose {
            silence.cancel()
            main.post { connection?.type?.removeObserver(observer) }
        }
    }.distinctUntilChanged()

    private companion object {
        const val SILENCE_MS = 3_000L
    }
}

/** The battery's level, from the sticky `ACTION_BATTERY_CHANGED`. */
class AndroidBatteryLevel(private val context: Context) : BatteryLevel {

    override val percent: Flow<Int> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                level(intent)?.let { trySend(it) }
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Unknown: full, so that a phone that does not say never falls to the saving mode.
        trySend(sticky?.let(::level) ?: FULL)
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun level(intent: Intent): Int? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) null else level * 100 / scale
    }

    private companion object {
        const val FULL = 100
    }
}

/** `PRIORITY_PASSIVE`: the GPS fixes other apps (Maps, Waze) obtain, delivered to [PassiveLocationReceiver] at no cost. */
class PassiveLocationRegistrar(private val context: Context) : PassiveRegistrar {

    @SuppressLint("MissingPermission")
    override fun register() {
        guarded("register") {
            val request = LocationRequest.Builder(Priority.PRIORITY_PASSIVE, PASSIVE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(PASSIVE_INTERVAL_MS)
                .build()
            LocationServices.getFusedLocationProviderClient(context)
                .requestLocationUpdates(request, pendingIntent())
                .addOnFailureListener { Log.w(TAG, "Passive listening refused: ${it.message}") }
        }
    }

    @SuppressLint("MissingPermission")
    override fun unregister() {
        guarded("unregister") {
            LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(pendingIntent())
        }
    }

    // Mutable: Play services fills the intent with the result. Explicit, so nothing else can answer it.
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        1,
        Intent(context, PassiveLocationReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Passive listening $what failed: ${e.message}")
        }
    }

    private companion object {
        const val PASSIVE_INTERVAL_MS = 60_000L
    }
}

/** The system delivers the passive fixes here, even with the app closed; the work is handed to WorkManager. */
class PassiveLocationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!LocationResult.hasResult(intent)) return
        val result = LocationResult.extractResult(intent) ?: return
        val koin = GlobalContext.getOrNull() ?: return
        try {
            koin.get<LocationScheduler>().handlePassivePoints(result.locations.map { it.toPosition() })
        } catch (e: Exception) {
            Log.w(TAG, "Passive fixes not handled: ${e.message}")
        }
    }
}

/**
 * Starts [TripService] when [tracker] says there is a trip to follow and the permissions are there; a start the
 * system refuses (Android 12+ lets only some contexts start a foreground service) is left for the next event.
 */
class AndroidTripLauncher(
    private val context: Context,
    private val tracker: TripTracker,
    private val reporter: LocationReporter,
) : TripLauncher {

    override suspend fun startIfNeeded() {
        try {
            if (!reporter.mayListen() || tracker.currentMode() == TrackingMode.IDLE) return
            ContextCompat.startForegroundService(context, Intent(context, TripService::class.java))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Trip not started: ${e.message}")
        }
    }
}
