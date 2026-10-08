package com.maggie.app.data.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MaggieFcmService : FirebaseMessagingService() {

    private val registrar: PushTokenRegistrar by inject()
    private val delivery: PushDelivery by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        serviceScope.launch { registrar.register(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = PushPayload.from(
            data = message.data,
            notificationTitle = message.notification?.title,
            notificationBody = message.notification?.body,
            notificationChannel = message.notification?.channelId,
        ) ?: return

        // Android calls this in the foreground only (a message with a `notification` block is
        // shown by the system otherwise): the delivery raises the interruption, not a notification.
        delivery.deliver(payload)
    }
}
