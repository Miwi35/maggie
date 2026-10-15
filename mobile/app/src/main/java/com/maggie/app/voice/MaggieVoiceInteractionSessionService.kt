package com.maggie.app.voice

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.ComponentName
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
    private val coordinator = AssistLaunchCoordinator(::launchOverlay, ::lateScreenshot)
    private val giveUp = Runnable { coordinator.onTimeout() }
    private val stopWaitingForScreenshot = Runnable {
        Log.i(TAG, "Screenshot still missing after ${LATE_SCREENSHOT_TIMEOUT_MS} ms: given up")
        // Through lateScreenshot, which tells the overlay and finishes the session.
        coordinator.onScreenshotAbandoned(ScreenshotMiss.LATE)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (coordinator.waitingForScreenshot) {
            // Summoned again while still waiting for the last screenshot: that one
            // is dropped, and the overlay — already open — listens again.
            handler.removeCallbacks(stopWaitingForScreenshot)
            coordinator.onScreenshotAbandoned()
            ScreenshotEncoder.file(service).delete()
            service.startActivity(
                Intent(service, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
            return
        }
        // Which pieces Android promised: the one line that tells, from a logcat, whether a
        // missing thumbnail was never asked for (MAG-214).
        Log.i(TAG, "Shown, flags=$showFlags")
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

        val activity = structure?.activityComponent

        // The overlay itself in front means the user summoned Maggie while she
        // was already open. Handing her her own answers back as « the screen you
        // are looking at » is worse than handing her nothing, so: nothing — but
        // the wait still ends, or the overlay would sit there for 1.2 s.
        //
        // The overlay, not the package: Maggie's own agenda or grocery list is a
        // screen like any other, and « ajoute ça à mon agenda » from it is a fair
        // question.
        if (activity == ComponentName(service, AssistantActivity::class.java)) {
            coordinator.onAssist(appPackage = null, appLabel = null, webUri = null, texts = emptyList())
            return
        }

        val appPackage = activity?.packageName
        coordinator.onAssist(
            appPackage = appPackage,
            appLabel = appPackage?.let(::appLabel),
            webUri = content?.webUri?.toString(),
            texts = structure?.let { AssistTextCollector.collect(AssistTextCollector.assistRoots(it)) }
                ?: emptyList(),
        )
    }

    /**
     * Encoded and written off the main thread (MAG-214): the coordinator keeps the
     * overlay waiting for it, under the same 1.2 s timeout as the rest, and takes it
     * after that timeout too ([lateScreenshot]). A file and not an extra, because a
     * screenshot does not fit in an intent.
     */
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot == null) {
            Log.i(TAG, "Screenshot refused by Android")
            coordinator.onScreenshot(kept = false, miss = ScreenshotMiss.REFUSED)
            return
        }
        Log.i(TAG, "Screenshot received, ${screenshot.width}x${screenshot.height} ${screenshot.config}")
        val file = ScreenshotEncoder.file(service)
        Thread {
            // Throwable, not Exception: a bitmap too big to copy is an OutOfMemoryError,
            // and an uncaught one would leave the overlay waiting for nothing.
            val kept = try {
                file.parentFile?.mkdirs()
                file.writeBytes(ScreenshotEncoder.encode(screenshot))
                Log.i(TAG, "Screenshot kept, ${file.length()} bytes")
                true
            } catch (e: Throwable) {
                Log.w(TAG, "Screenshot not kept", e)
                false
            }
            handler.post {
                if (!coordinator.onScreenshot(kept) && kept) file.delete()
            }
        }.start()
    }

    /**
     * Dismissed without opening anything — the session object survives and
     * Android may show it again, so the screen it was about is forgotten here.
     * Without this, the next invocation launches at once with the previous
     * screen's content.
     */
    override fun onHide() {
        handler.removeCallbacks(giveUp)
        // Hidden while waiting for a late screenshot: Android will not deliver it
        // now, and a session left alive would swallow the next invocation.
        if (coordinator.waitingForScreenshot) {
            handler.removeCallbacks(stopWaitingForScreenshot)
            Log.i(TAG, "Hidden while waiting for the screenshot")
            coordinator.onScreenshotAbandoned(ScreenshotMiss.HIDDEN)
        }
        if (!coordinator.hasLaunched) ScreenshotEncoder.file(service).delete()
        coordinator.onDismissed()
        super.onHide()
    }

    override fun onDestroy() {
        handler.removeCallbacks(giveUp)
        handler.removeCallbacks(stopWaitingForScreenshot)
        super.onDestroy()
    }

    /**
     * Finished at once, unless the screenshot is still to come (MAG-214): Android
     * cancels whatever it has not delivered to a finished session, which is how the
     * screenshot used to be lost. Until it comes, the session stays without a UI, so
     * nothing of it covers the overlay.
     */
    private fun launchOverlay(screen: ScreenContext) {
        handler.removeCallbacks(giveUp)
        if (screen.screenshotPending) LateScreenshot.expect()
        val intent = Intent(service, AssistantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            screen.putInto(this)
        }
        service.startActivity(intent)
        if (screen.screenshotPending) {
            setUiEnabled(false)
            handler.postDelayed(stopWaitingForScreenshot, LATE_SCREENSHOT_TIMEOUT_MS)
        } else {
            finish()
        }
    }

    /**
     * The screenshot came after the overlay opened: it goes to the overlay if it is still
     * there — or, when it could not be kept, the reason does.
     */
    private fun lateScreenshot(miss: ScreenshotMiss?) {
        handler.removeCallbacks(stopWaitingForScreenshot)
        when {
            miss != null -> LateScreenshot.miss(miss)
            AssistantActivity.isShowing -> LateScreenshot.arrive()
            else -> ScreenshotEncoder.file(service).delete()
        }
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

        /**
         * How much longer the session waits for the screenshot once the overlay is
         * open. The user is still speaking by then; past this, it is not coming.
         */
        private const val LATE_SCREENSHOT_TIMEOUT_MS = 5_000L
    }
}
