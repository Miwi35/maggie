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
 * The chat API takes a single string ([com.maggie.app.data.api.MaggieApiService.sendChatStream]),
 * so the context reaches the model as a block prefixed to the message the user
 * dictated — see [toPromptBlock]. The bubble in the conversation still shows
 * only what was said.
 *
 * [hasScreenshot] is deliberately a flag and not the image: nothing in the API
 * or the agent accepts one today, and telling the model « the screen is an image
 * I cannot read » is what keeps it from inventing the content of a screen whose
 * view tree carried no text.
 */
data class ScreenContext(
    val appPackage: String? = null,
    val appLabel: String? = null,
    val webUri: String? = null,
    val texts: List<String> = emptyList(),
    val hasScreenshot: Boolean = false,
) {
    val isEmpty: Boolean
        get() = appPackage == null && webUri == null && texts.isEmpty() && !hasScreenshot

    /**
     * The provenance the overlay shows, so the user sees what Maggie is about to
     * read before speaking. Null when there is nothing to name.
     */
    fun source(): String? =
        appLabel?.takeIf { it.isNotBlank() }
            ?: webUri?.let { host(it) }
            ?: appPackage?.takeIf { it.isNotBlank() }

    /** The block prefixed to the first message of the session, or null if empty. */
    fun toPromptBlock(): String? {
        if (isEmpty) return null

        val lines = mutableListOf("[Contexte de l'écran]")
        val app = listOfNotNull(
            appLabel?.takeIf { it.isNotBlank() },
            appPackage?.takeIf { it.isNotBlank() }?.let { "($it)" },
        ).joinToString(" ")
        if (app.isNotEmpty()) lines += "Application : $app"
        webUri?.takeIf { it.isNotBlank() }?.let { lines += "Page : $it" }

        if (texts.isNotEmpty()) {
            lines += "Texte à l'écran :"
            texts.forEach { lines += "- $it" }
        } else if (hasScreenshot) {
            lines += "Le contenu de l'écran n'est pas lisible : seule une image est disponible, " +
                "et je ne sais pas encore la regarder. Ne devine pas ce qu'elle montre."
        }

        return lines.joinToString("\n")
    }

    fun putInto(intent: Intent) {
        intent.putExtra(EXTRA_PACKAGE, appPackage)
        intent.putExtra(EXTRA_LABEL, appLabel)
        intent.putExtra(EXTRA_WEB_URI, webUri)
        intent.putStringArrayListExtra(EXTRA_TEXTS, ArrayList(texts))
        intent.putExtra(EXTRA_SCREENSHOT, hasScreenshot)
    }

    private fun host(uri: String): String? =
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)").find(uri)?.groupValues?.get(1)
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }

    companion object {
        const val EXTRA_PACKAGE = "com.maggie.app.extra.SCREEN_PACKAGE"
        const val EXTRA_LABEL = "com.maggie.app.extra.SCREEN_LABEL"
        const val EXTRA_WEB_URI = "com.maggie.app.extra.SCREEN_WEB_URI"
        const val EXTRA_TEXTS = "com.maggie.app.extra.SCREEN_TEXTS"
        const val EXTRA_SCREENSHOT = "com.maggie.app.extra.SCREEN_SCREENSHOT"

        /** Null when the intent carries no context, so the overlay stays as it was. */
        fun fromIntent(intent: Intent): ScreenContext? {
            val context = ScreenContext(
                appPackage = intent.getStringExtra(EXTRA_PACKAGE),
                appLabel = intent.getStringExtra(EXTRA_LABEL),
                webUri = intent.getStringExtra(EXTRA_WEB_URI),
                texts = intent.getStringArrayListExtra(EXTRA_TEXTS)?.toList() ?: emptyList(),
                hasScreenshot = intent.getBooleanExtra(EXTRA_SCREENSHOT, false),
            )
            return context.takeIf { !it.isEmpty }
        }
    }
}
