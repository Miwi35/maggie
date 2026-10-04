package com.maggie.app.voice

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

class MaggieVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return MaggieVoiceInteractionSession(this)
    }
}

/**
 * The session Android shows when Maggie is the assistant (MAG-30).
 *
 * It does not draw anything: it collects the screen context Android offers, then
 * hands it to [AssistantActivity], which is the overlay. The *when* of that
 * hand-off is [AssistLaunchCoordinator]'s job — the session only owns the clock
 * and the Android plumbing.
 */
private class MaggieVoiceInteractionSession(
    private val service: VoiceInteractionSessionService,
) : VoiceInteractionSession(service) {

    private val handler = Handler(Looper.getMainLooper())
    private val coordinator = AssistLaunchCoordinator(::launchOverlay)
    private val giveUp = Runnable { coordinator.onTimeout() }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val waiting = coordinator.onShow(
            withAssist = showFlags and SHOW_WITH_ASSIST != 0,
            withScreenshot = showFlags and SHOW_WITH_SCREENSHOT != 0,
        )
        if (waiting) {
            handler.postDelayed(giveUp, ASSIST_TIMEOUT_MS)
        }
    }

    /**
     * Still the 2015 signature: `onHandleAssist(AssistState)` needs API 31 and
     * the app ships from 29. Its default implementation forwards the front
     * window here anyway, so this one override covers every supported release.
     */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        @Suppress("DEPRECATION")
        super.onHandleAssist(data, structure, content)

        val appPackage = structure?.activityComponent?.packageName

        // Our own overlay in front means the user summoned Maggie while she was
        // already open. Handing her her own answers back as « the screen you are
        // looking at » is worse than handing her nothing, so: nothing — but the
        // wait still ends, or the overlay would sit there for 1.2 s.
        if (appPackage == service.packageName) {
            coordinator.onAssist(appPackage = null, appLabel = null, webUri = null, texts = emptyList())
            return
        }

        coordinator.onAssist(
            appPackage = appPackage,
            appLabel = appPackage?.let(::appLabel),
            webUri = content?.webUri?.toString(),
            texts = structure?.let { AssistTextCollector.collect(AssistTextCollector.assistRoots(it)) }
                ?: emptyList(),
        )
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        coordinator.onScreenshot(available = screenshot != null)
    }

    /**
     * Dismissed without opening anything — the session object survives and
     * Android may show it again, so the screen it was about is forgotten here.
     * Without this, the next invocation launches at once with the previous
     * screen's content.
     */
    override fun onHide() {
        handler.removeCallbacks(giveUp)
        coordinator.onDismissed()
        super.onHide()
    }

    override fun onDestroy() {
        handler.removeCallbacks(giveUp)
        super.onDestroy()
    }

    private fun launchOverlay(screen: ScreenContext) {
        handler.removeCallbacks(giveUp)
        val intent = Intent(service, AssistantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            screen.putInto(this)
        }
        service.startActivity(intent)
        finish()
    }

    /** The name the user knows the app by; its package is a poor thing to read aloud. */
    private fun appLabel(appPackage: String): String? = try {
        val info = service.packageManager.getApplicationInfo(appPackage, 0)
        service.packageManager.getApplicationLabel(info).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, "No label for $appPackage", e)
        null
    }

    companion object {
        private const val TAG = "MaggieVoiceSession"

        /**
         * How long the overlay waits for the screen context before opening
         * without it. Long enough for an app to build an `AssistStructure`,
         * short enough that a user who long-pressed to talk does not notice.
         */
        private const val ASSIST_TIMEOUT_MS = 1_200L
    }
}
