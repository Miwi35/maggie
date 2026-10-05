package com.maggie.app.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.maggie.app.MainActivity
import com.maggie.app.R

object WakeWordNotifications {

    const val CHANNEL_REACTIVATE = "wake_word_reactivate"
    private const val NOTIFICATION_ID = 2002

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_REACTIVATE,
                "Réactivation du mot d'activation",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Rappel quand l'écoute du mot d'activation doit être relancée à la main"
            },
        )
    }

    /** Android 15 forbids starting the microphone service from the background: the user's tap does it. */
    fun showReactivation(context: Context) {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REACTIVATE)
            .setSmallIcon(R.drawable.maggie_logo)
            .setContentTitle("Maggie")
            .setContentText("Touchez pour réactiver l'écoute")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    fun cancelReactivation(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
