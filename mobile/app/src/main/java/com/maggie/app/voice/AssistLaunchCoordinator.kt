package com.maggie.app.voice

/**
 * Decides *when* the assistant overlay opens, and with what (MAG-30).
 *
 * The session used to start [AssistantActivity] from `onShow` and call `finish()`
 * on the next line, which killed it before Android had a chance to deliver
 * `onHandleAssist` — the screen content never arrived anywhere. But waiting is
 * not free either: the overlay must not sit blank while an app takes its time
 * building an `AssistStructure`, and a session shown without the assist flags
 * will never receive anything at all.
 *
 * So: open at once when there is nothing to wait for, otherwise wait for exactly
 * the pieces the show flags promised, and give up on [onTimeout] with whatever
 * arrived. Exactly one launch happens, whichever path gets there first — a late
 * callback after the timeout must not open a second overlay.
 *
 * Kept free of Android types so every one of those paths is a unit test; the
 * session owns the clock.
 */
class AssistLaunchCoordinator(private val onLaunch: (ScreenContext) -> Unit) {

    private var shown = false
    private var awaitingAssist = false
    private var awaitingScreenshot = false
    private var assistReceived = false
    private var screenshotReceived = false
    private var context = ScreenContext()

    var hasLaunched: Boolean = false
        private set

    /**
     * Returns true when the caller must arm the timeout — i.e. the overlay did
     * not open yet because something is still expected.
     */
    fun onShow(withAssist: Boolean, withScreenshot: Boolean): Boolean {
        if (hasLaunched) return false
        shown = true
        awaitingAssist = withAssist
        awaitingScreenshot = withScreenshot
        return !launchIfReady()
    }

    fun onAssist(appPackage: String?, appLabel: String?, webUri: String?, texts: List<String>) {
        if (hasLaunched || !shown) return
        assistReceived = true
        // First window wins: Android may deliver one call per activity in the
        // task, and the one in front is the one the user is looking at.
        context = context.copy(
            appPackage = context.appPackage ?: appPackage?.takeIf { it.isNotBlank() },
            appLabel = context.appLabel ?: appLabel?.takeIf { it.isNotBlank() },
            webUri = context.webUri ?: webUri?.takeIf { it.isNotBlank() },
            texts = context.texts.ifEmpty { texts },
        )
        launchIfReady()
    }

    /**
     * [kept] is whether the screenshot was written to [ScreenshotEncoder.file] —
     * false when Android refused it, which still ends the wait. Returns false when
     * the file was not taken (too late, or for a dismissed invocation): nothing will
     * send it, so the caller deletes it.
     */
    fun onScreenshot(kept: Boolean): Boolean {
        if (hasLaunched || !shown) return false
        screenshotReceived = true
        context = context.copy(hasScreenshot = context.hasScreenshot || kept)
        launchIfReady()
        return true
    }

    fun onTimeout() {
        if (hasLaunched || !shown) return
        launch()
    }

    /**
     * The session was hidden without opening anything. Android keeps the session
     * object and may show it again, over another app: everything gathered for
     * the invocation that was dismissed has to go, or the next one would open
     * with the previous screen's content — and open at once, believing its wait
     * is already over.
     *
     * Ignored once the overlay is up: `onHide` also fires behind the launch, and
     * clearing [hasLaunched] there would let a late callback open a second one.
     */
    fun onDismissed() {
        if (hasLaunched) return
        shown = false
        awaitingAssist = false
        awaitingScreenshot = false
        assistReceived = false
        screenshotReceived = false
        context = ScreenContext()
    }

    private fun launchIfReady(): Boolean {
        if (hasLaunched || !shown) return hasLaunched
        if (awaitingAssist && !assistReceived) return false
        if (awaitingScreenshot && !screenshotReceived) return false
        launch()
        return true
    }

    private fun launch() {
        hasLaunched = true
        onLaunch(context)
    }
}
