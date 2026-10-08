package com.maggie.app.data.fcm

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MaggieFcmService : FirebaseMessagingService() {

    private val registrar: PushTokenRegistrar by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        serviceScope.launch { registrar.register(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Foreground is MAG-314's: the interruption is raised inside the app, not by the system.
        val isForegrounded = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        if (isForegrounded) return

        val payload = PushPayload.from(
            data = message.data,
            notificationTitle = message.notification?.title,
            notificationBody = message.notification?.body,
            notificationChannel = message.notification?.channelId,
        ) ?: return

        PushNotifier(this).show(payload)
    }
}
