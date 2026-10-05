package com.maggie.app.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var audioManager: AudioManager
    private var callActive = false
    private var mediaPlaying = false
    private var assistantOpen = false
    private var foregroundFailed = false

    private val modeListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AudioManager.OnModeChangedListener { mode ->
            callActive = ListeningPolicy.isCallMode(mode)
            evaluate()
        }
    } else {
        null
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            refreshAudioState()
            evaluate()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIFICATION_ID, buildNotification(paused = false))
        } catch (e: Exception) {
            // Android 15 refuses a microphone service started from the background
            // (boot, or a restart after the system killed us): hand over to the user.
            Log.w(TAG, "Cannot start in the foreground: ${e.message}")
            foregroundFailed = true
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                WakeWordNotifications.showReactivation(this)
            }
            stopSelf()
            return
        }
        audioManager = getSystemService(AudioManager::class.java)
        refreshAudioState()
        audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modeListener?.let { audioManager.addOnModeChangedListener(ContextCompat.getMainExecutor(this), it) }
        }
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundFailed) return START_NOT_STICKY
        // An explicit start while no assistant is on screen (app opened, notification tapped)
        // must never leave the engine parked behind a stale "assistant open".
        if (intent?.action == ACTION_RESUME_LISTENING || !AssistantActivity.isShowing) {
            assistantOpen = false
        }
        evaluate()
        return START_STICKY
    }

    private fun refreshAudioState() {
        callActive = ListeningPolicy.isCallMode(audioManager.mode)
        mediaPlaying = audioManager.isMusicActive
    }

    private fun evaluate() {
        if (foregroundFailed || assistantOpen) return
        val shouldListen = ListeningPolicy.shouldListen(callActive, mediaPlaying)
        if (shouldListen) startEngine() else stopEngine()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(paused = !shouldListen))
    }

    private fun startEngine() {
        if (engine != null) return

        try {
            val models = listOf(
                WakeWordModel("Maggie", "maggie.onnx", threshold = 0.5f),
            )
            val newEngine = WakeWordEngine(
                context = this,
                models = models,
                detectionMode = DetectionMode.SINGLE_BEST,
            )
            engine = newEngine

            engineJob = scope.launch {
                newEngine.detections.collect { detection ->
                    Log.i(TAG, "Wake word detected! Score: ${detection.score}")
                    mainHandler.post { onWakeWord() }
                }
            }

            newEngine.start()
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

    private fun onWakeWord() {
        if (engine == null || assistantOpen) return
        assistantOpen = true
        // The microphone belongs to the assistant until AssistantActivity hands it back.
        stopEngine()
        AssistantLauncher.launch(
            showSession = { MaggieVoiceInteractionService.showAssistantSession() },
            startActivity = {
                startActivity(
                    Intent(this, AssistantActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            },
        )
        // A launch the system silently dropped must not leave us deaf for good.
        mainHandler.removeCallbacks(launchTimeout)
        mainHandler.postDelayed(launchTimeout, ASSISTANT_LAUNCH_TIMEOUT_MS)
    }

    private val launchTimeout = Runnable {
        if (assistantOpen && !AssistantActivity.isShowing) {
            assistantOpen = false
            evaluate()
        }
    }

    private fun onAssistantClosed() {
        assistantOpen = false
        evaluate()
    }

    private fun buildNotification(paused: Boolean): Notification {
        return NotificationCompat.Builder(this, CHANNEL_WAKE_WORD)
            .setSmallIcon(R.drawable.maggie_logo)
            .setContentTitle("Maggie")
            .setContentText(
                if (paused) {
                    "Écoute en pause (appel ou lecture en cours)"
                } else {
                    "Dites « Maggie » pour commencer"
                },
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = null
        if (::audioManager.isInitialized) {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modeListener?.let { audioManager.removeOnModeChangedListener(it) }
            }
        }
        mainHandler.removeCallbacksAndMessages(null)
        stopEngine()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WakeWordService"
        const val CHANNEL_WAKE_WORD = "wake_word"
        const val ACTION_RESUME_LISTENING = "com.maggie.app.RESUME_LISTENING"
        private const val NOTIFICATION_ID = 2001
        private const val ASSISTANT_LAUNCH_TIMEOUT_MS = 10_000L

        @Volatile
        private var running: WakeWordService? = null

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        fun resumeListening(context: Context) {
            // A running service is told directly: startForegroundService is refused once the
            // assistant (often shown over the lock screen) was the last thing on screen.
            running?.let { service ->
                service.mainHandler.post { service.onAssistantClosed() }
                return
            }
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
                    description = "Écoute du mot d'activation « Maggie »"
                },
            )
            WakeWordNotifications.createChannel(context)
        }
    }
}
