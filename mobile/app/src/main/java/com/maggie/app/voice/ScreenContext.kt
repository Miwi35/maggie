package com.maggie.app.voice

import android.content.Intent

/**
 * What the assistant knows about the screen it was summoned from (MAG-30).
 *
 * Android hands this to the session in pieces — `onHandleAssist` gives the view
 * tree and the `AssistContent`, `onHandleScreenshot` an image — and the overlay
 * runs in a different process-local activity, so the pieces travel as intent
 * extras. [AssistLaunchCoordinator] assembles one instance per invocation.
 *
 * The context reaches the model as a block ([toPromptBlock]) sent in its own
 * field beside the message. Prefixed to the message instead — how MAG-30 first
 * shipped — it was what the agent stored, and every reader of the conversation
 * then showed the page instead of the question: the mobile chat on reload, the
 * Mercure echo, the web chat. The bubble shows what was said; the model reads
 * the screen.
 *
 * [hasScreenshot] says the session left a JPEG at [ScreenshotEncoder.file]: the
 * image does not fit in an intent (extras cap out around 1 MB), and its path does
 * not travel either. The overlay is exported for `ACTION_ASSIST`, so any app can
 * start it with any extras — a path read from them would have had Maggie send and
 * delete any of her own files. The overlay sends the image with the next sentence
 * and deletes it ([PendingScreenshot], MAG-214). [screenshotPending] says the
 * overlay opened before the screenshot came: the session tells it when it does
 * ([LateScreenshot]), and [withArrivedScreenshot] turns it into [hasScreenshot].
 * [screenshotMiss] says why there is none when Android was asked for one: the
 * overlay shows it beside the rest, the model is told nothing of it — alone, it is
 * no context.
 */
data class ScreenContext(
    val appPackage: String? = null,
    val appLabel: String? = null,
    val webUri: String? = null,
    val texts: List<String> = emptyList(),
    val hasScreenshot: Boolean = false,
    val screenshotPending: Boolean = false,
    val screenshotMiss: ScreenshotMiss? = null,
) {
    /**
     * [appLabel] counts: a context naming the app but not its package is still
     * something to tell the model and something [source] would show, and a
     * definition that ignored it would have [toPromptBlock] return null for a
     * context the overlay displays.
     */
    val isEmpty: Boolean
        get() = appPackage == null && appLabel == null && webUri == null && texts.isEmpty() && !hasScreenshot &&
            !screenshotPending

    /**
     * The screenshot this context was waiting for is now in [ScreenshotEncoder.file].
     * Null when it was not waiting for one — its sentence is gone, or another
     * invocation replaced it — and the file is then nobody's to send.
     */
    fun withArrivedScreenshot(): ScreenContext? =
        if (screenshotPending) copy(hasScreenshot = true, screenshotPending = false) else null

    /** The screenshot this context was waiting for will not come, for [miss]; null as above. */
    fun withMissedScreenshot(miss: ScreenshotMiss): ScreenContext? =
        if (screenshotPending) copy(screenshotPending = false, screenshotMiss = miss) else null

    /**
     * The line the overlay shows under « Contexte : … » when there is no thumbnail to show
     * (MAG-214): still coming, or why it will not. Null when there is nothing to say.
     */
    fun screenshotStatus(): String? = when {
        screenshotPending -> "Capture d'écran en cours…"
        screenshotMiss != null -> "Capture d'écran indisponible : ${screenshotMiss.reason}"
        else -> null
    }

    /**
     * The provenance the overlay shows, so the user sees what Maggie is about to
     * read before speaking. Null when there is nothing to name.
     */
    fun source(): String? =
        appLabel?.takeIf { it.isNotBlank() }
            ?: webUri?.let { host(it) }
            ?: appPackage?.takeIf { it.isNotBlank() }

    /**
     * The block sent beside the first sentence, or null if there is nothing to say.
     *
     * With an image attached, the image carries the content: the block names the
     * app and the page's domain, never the texts nor the full address (MAG-214, the
     * owner's decision). Without one, the texts are all the model gets.
     * [imageAttached] is whether bytes actually go with the sentence, not
     * [hasScreenshot]: a file that could not be read must not be announced.
     */
    fun toPromptBlock(imageAttached: Boolean = false): String? {
        if (isEmpty) return null

        val lines = mutableListOf(PROMPT_HEADER)
        val app = listOfNotNull(
            appLabel?.takeIf { it.isNotBlank() },
            appPackage?.takeIf { it.isNotBlank() }?.let { "($it)" },
        ).joinToString(" ")
        if (app.isNotEmpty()) lines += "Application : $app"

        if (imageAttached) {
            webUri?.let { host(it) }?.let { lines += "Page : $it" }
            lines += "L'image jointe est une capture de cet écran."
        } else {
            webUri?.takeIf { it.isNotBlank() }?.let { lines += "Page : $it" }
            if (texts.isNotEmpty()) {
                lines += "Texte à l'écran :"
                texts.forEach { lines += "- $it" }
            }
        }

        // A screenshot that was not attached, and nothing else: no block.
        if (lines.size == 1) return null
        return lines.joinToString("\n")
    }

    fun putInto(intent: Intent) {
        intent.putExtra(EXTRA_PACKAGE, appPackage)
        intent.putExtra(EXTRA_LABEL, appLabel)
        intent.putExtra(EXTRA_WEB_URI, webUri)
        intent.putStringArrayListExtra(EXTRA_TEXTS, ArrayList(texts))
        intent.putExtra(EXTRA_SCREENSHOT, hasScreenshot)
        intent.putExtra(EXTRA_SCREENSHOT_PENDING, screenshotPending)
        intent.putExtra(EXTRA_SCREENSHOT_MISS, screenshotMiss?.name)
    }

    private fun host(uri: String): String? =
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)").find(uri)?.groupValues?.get(1)
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }

    companion object {
        /** The first line of [toPromptBlock], and the marker [withoutPromptBlock] looks for. */
        const val PROMPT_HEADER = "[Contexte de l'écran]"

        const val EXTRA_PACKAGE = "com.maggie.app.extra.SCREEN_PACKAGE"
        const val EXTRA_LABEL = "com.maggie.app.extra.SCREEN_LABEL"
        const val EXTRA_WEB_URI = "com.maggie.app.extra.SCREEN_WEB_URI"
        const val EXTRA_TEXTS = "com.maggie.app.extra.SCREEN_TEXTS"
        const val EXTRA_SCREENSHOT = "com.maggie.app.extra.SCREEN_SCREENSHOT"
        const val EXTRA_SCREENSHOT_PENDING = "com.maggie.app.extra.SCREEN_SCREENSHOT_PENDING"
        const val EXTRA_SCREENSHOT_MISS = "com.maggie.app.extra.SCREEN_SCREENSHOT_MISS"

        /**
         * What the user actually said, out of a message that carries a context
         * block.
         *
         * The block no longer travels inside the message — it has its own field
         * on the chat routes, and the agent stores the question alone. What is
         * left for this to catch are the exchanges recorded before that fix,
         * which the history still returns word for word, and a message sent
         * from a copy of the app that predates it. A bubble shows what was said
         * and nothing else (MAG-30).
         *
         * A message that starts with the header but has no blank line after the
         * block is left alone: better an ugly bubble than an empty one.
         *
         * The cut is the first blank line, which works because every line the
         * block holds is single-spaced — [AssistTextCollector] collapses the
         * whitespace of each entry. A field added to [toPromptBlock] that could
         * contain a blank line would leave half a block in the bubble.
         */
        fun withoutPromptBlock(content: String): String {
            if (!content.startsWith(PROMPT_HEADER)) return content
            val said = content.substringAfter("\n\n", missingDelimiterValue = "")
            return said.ifBlank { content }
        }

        /** Null when the intent carries no context, so the overlay stays as it was. */
        fun fromIntent(intent: Intent): ScreenContext? {
            val context = ScreenContext(
                appPackage = intent.getStringExtra(EXTRA_PACKAGE),
                appLabel = intent.getStringExtra(EXTRA_LABEL),
                webUri = intent.getStringExtra(EXTRA_WEB_URI),
                texts = intent.getStringArrayListExtra(EXTRA_TEXTS)?.toList() ?: emptyList(),
                hasScreenshot = intent.getBooleanExtra(EXTRA_SCREENSHOT, false),
                screenshotPending = intent.getBooleanExtra(EXTRA_SCREENSHOT_PENDING, false),
                screenshotMiss = ScreenshotMiss.fromName(intent.getStringExtra(EXTRA_SCREENSHOT_MISS)),
            )
            return context.takeIf { !it.isEmpty }
        }
    }
}
