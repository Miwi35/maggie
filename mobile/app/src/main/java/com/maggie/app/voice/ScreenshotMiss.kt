package com.maggie.app.voice

/**
 * Why an invocation reaches the overlay without its screenshot (MAG-214).
 *
 * The owner's second refusal (15 Oct.) was « aucune vignette », with nothing on the
 * phone to tell which link broke. Each way the screenshot can go missing is named
 * here and shown under « Contexte : … » ([reason]), so the next try says where it
 * stopped — and the user knows Maggie is not looking at the screen.
 */
enum class ScreenshotMiss(val reason: String) {
    /** The session was shown without `SHOW_WITH_SCREENSHOT`: Android never meant to send one. */
    NOT_OFFERED("Android ne l'a pas proposée"),

    /** Android sent `null`: the setting is off, or the screen in front forbids capture. */
    REFUSED("Android l'a refusée (réglage « Analyse des images à l'écran » ?)"),

    /** It came, but could not be encoded or written. */
    UNREADABLE("elle n'a pas pu être enregistrée"),

    /** The overlay opened before it and it never came. */
    LATE("elle n'est pas arrivée à temps"),
    ;

    companion object {
        /** Null for anything else: the overlay is exported, its extras are not trusted. */
        fun fromName(name: String?): ScreenshotMiss? = entries.firstOrNull { it.name == name }
    }
}
