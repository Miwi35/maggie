package com.maggie.app.data.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.maggie.app.R
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
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        val tracker = GlobalContext.getOrNull()?.get<TripTracker>()
        if (tracker == null) {
            stopSelf()
        } else if (trip?.isActive != true) {
            trip = scope.launch {
                try {
                    tracker.run()
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
        const val CHANNEL = "trip"
        const val NOTIFICATION_ID = 6660
    }
}
