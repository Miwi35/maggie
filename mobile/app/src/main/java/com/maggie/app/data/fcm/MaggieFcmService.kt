package com.maggie.app.data.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.maggie.app.MainActivity
import com.maggie.app.R
import com.maggie.app.data.api.MaggieApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MaggieFcmService : FirebaseMessagingService() {

    private val apiService: MaggieApiService by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token refreshed")
        serviceScope.launch {
            try {
                apiService.registerFcmToken(token, android.os.Build.MODEL)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register FCM token: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Skip notification when the app is in foreground — Mercure handles live delivery
        val isForegrounded = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        if (isForegrounded) return

        val messageId = message.data["messageId"]
        val title = message.notification?.title ?: "Maggie"
        val body = message.notification?.body ?: message.data["body"] ?: return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            messageId?.let { putExtra("messageId", it) }
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_CHAT)
            .setSmallIcon(R.drawable.maggie_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(messageId?.hashCode() ?: System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        private const val TAG = "MaggieFcmService"
        const val CHANNEL_CHAT = "chat"

        fun createNotificationChannels(context: android.content.Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_CHAT, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Messages de Maggie"
                }
            )
        }
    }
}
