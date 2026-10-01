package com.maggie.app.ui

/**
 * The ids the Maestro journeys address the app by (MAG-98).
 *
 * Maestro reads the view hierarchy through UiAutomator, where a Compose node
 * has no resource id of its own — unless the root opts in with
 * `testTagsAsResourceId`, which `MainActivity` does. A `testTag` from this file
 * is then what `id: "…"` matches in `e2e/mobile/flows/`.
 *
 * Why tags and not the visible text: a journey anchored on « Demander à
 * Maggie... » breaks the day the placeholder is reworded, and the failure reads
 * as a broken chat rather than as a renamed string. Tags also disambiguate what
 * the screen says twice — that placeholder is both the collapsed bar and the
 * sheet's text field.
 *
 * Renaming one here means renaming it in the flows: `task e2e:mobile:lint`
 * checks every id a flow uses is declared below, so the mismatch fails in
 * seconds instead of on an emulator.
 */
object UiTags {
    /** Login — the single button the screen offers, whatever door is behind it. */
    const val LOGIN_SIGN_IN = "login_sign_in"

    /** Dashboard — the root, so a journey can wait for the screen itself. */
    const val DASHBOARD = "dashboard"

    /** The collapsed chat bar, on every main screen. */
    const val CHAT_OPEN = "chat_open"

    /** The microphone, which opens the chat sheet in voice mode. */
    const val CHAT_MIC = "chat_mic"

    /** The brain, which opens the context sheet. */
    const val CHAT_CONTEXTS = "chat_contexts"

    /** The chat sheet: its input and its two buttons. */
    const val CHAT_INPUT = "chat_input"
    const val CHAT_SEND = "chat_send"
    const val CHAT_CLOSE = "chat_close"

    /** The voice bar's state line — « Maggie parle... » and friends. */
    const val VOICE_STATE = "voice_state"
}
