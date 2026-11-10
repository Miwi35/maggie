package com.maggie.app.media

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Maggie as a media app (MAG-773): Android Auto lists one entry, the last digest's voice, and plays it
 * through this session. Nothing plays by itself — only a press in the car's media, or « Écouter » on
 * the digest's notification ([listenIntent]).
 */
class MaggieMediaService : MediaLibraryService() {
    private val library: DigestMediaLibrary by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private var focus: DigestAudioFocus? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                // Not ExoPlayer's own: the digest asks for the transient, duckable focus itself.
                false,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(DigestPlayback.SKIP_MS)
            .setSeekForwardIncrementMs(DigestPlayback.SKIP_MS)
            .build()
        val audioFocus = DigestAudioFocus(AndroidAudioFocusGateway(this)) { player.pause() }
        player.addListener(DigestFocusListener(player, audioFocus))
        focus = audioFocus
        session = MediaLibrarySession.Builder(this, player, DigestSessionCallback(library, scope)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_LISTEN) return super.onStartCommand(intent, flags, startId)
        scope.launch {
            val item = library.latest()?.let { library.playable(it) }
            val player = session?.player
            if (item == null || player == null) {
                stopSelf()
                return@launch
            }
            player.setMediaItem(item)
            player.prepare()
            player.play()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || player.playWhenReady.not() || player.playbackState == Player.STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        focus?.abandon()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_LISTEN = "com.maggie.app.media.LISTEN_DIGEST"

        /** « Écouter » on the digest's notification: the service plays the last digest voice. */
        fun listenIntent(context: Context): Intent =
            Intent(context, MaggieMediaService::class.java).setAction(ACTION_LISTEN)

        fun listenPendingIntent(context: Context, requestCode: Int): PendingIntent = PendingIntent.getForegroundService(
            context,
            requestCode,
            listenIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

object DigestPlayback {
    /** « Reculer de 15 secondes » / « Avancer de 15 secondes ». */
    const val SKIP_MS = 15_000L
}
