package com.maggie.app.data.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.maggie.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * The GPS for the length of a trip (MAG-666). Background apps get a handful of fixes an hour; a foreground
 * service gets them all. It stops by itself when the owner is still again and the car disconnected.
 */
class TripService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var trip: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // Started from a context Android 12+ refuses, or the location permission was revoked meanwhile.
            Log.w(TAG, "Trip service not allowed to run: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        val tracker = GlobalContext.getOrNull()?.get<TripTracker>()
        if (tracker == null) {
            stopSelf()
        } else if (trip?.isActive != true) {
            trip = scope.launch {
                try {
                    tracker.run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A GPS that refuses (permission revoked, Play services gone) ends the trip, never the app.
                    Log.w(TAG, "Trip ended on an error: ${e.message}")
                } finally {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Trajets", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Maggie suit ta position pendant un déplacement, pour te faire partir à l'heure"
            },
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.maggie_logo)
            .setContentTitle("Maggie suit ton trajet")
            .setContentText("Pour te faire partir à l'heure")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private companion object {
        // Not in PushChannels: those are the server's, and PushChannelsContractTest holds them to its list.
        const val TAG = "DepartureTrip"
        const val CHANNEL = "trip"
        const val NOTIFICATION_ID = 6660
    }
}
