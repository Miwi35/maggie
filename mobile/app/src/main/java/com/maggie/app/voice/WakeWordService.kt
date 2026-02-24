package com.maggie.app.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.maggie.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel

class WakeWordService : Service() {

    private var engine: WakeWordEngine? = null
    private var engineJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESUME_LISTENING -> startEngine()
            else -> startEngine()
        }
        return START_STICKY
    }

    private fun startEngine() {
        if (engine != null) return

        try {
            val models = listOf(
                WakeWordModel("Maggie", "maggie.onnx", threshold = 0.5f),
            )
            engine = WakeWordEngine(
                context = this,
                models = models,
                detectionMode = DetectionMode.SINGLE_BEST,
            )

            engineJob = scope.launch {
                engine?.detections?.collect { detection ->
                    Log.i(TAG, "Wake word detected! Score: ${detection.score}")
                    engine?.stop()
                    launchAssistant()
                }
            }

            engine?.start()
            Log.i(TAG, "OpenWakeWord started, listening for wake word")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OpenWakeWord: ${e.message}", e)
            stopSelf()
        }
    }

    private fun stopEngine() {
        try {
            engineJob?.cancel()
            engine?.stop()
            engine?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping OpenWakeWord: ${e.message}")
        }
        engineJob = null
        engine = null
    }

    private fun launchAssistant() {
        val intent = Intent(this, AssistantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_FROM_WAKE_WORD, true)
        }
        startActivity(intent)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_WAKE_WORD)
            .setSmallIcon(R.drawable.maggie_logo)
            .setContentTitle("Maggie")
            .setContentText("Dites \u00ab Maggie \u00bb pour commencer")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopEngine()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WakeWordService"
        const val CHANNEL_WAKE_WORD = "wake_word"
        const val ACTION_RESUME_LISTENING = "com.maggie.app.RESUME_LISTENING"
        const val EXTRA_FROM_WAKE_WORD = "from_wake_word"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        fun resumeListening(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).apply {
                action = ACTION_RESUME_LISTENING
            }
            context.startForegroundService(intent)
        }

        fun createNotificationChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WAKE_WORD,
                    "Mot d'activation",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "\u00c9coute du mot d'activation \u00ab Maggie \u00bb"
                },
            )
        }
    }
}
