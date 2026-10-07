package com.maggie.app.voice

import android.content.Context
import android.content.Intent

/**
 * `maggie-e2e-assist://overlay?screenshot=fixture` stages a screenshot (MAG-214): a
 * Maestro flow cannot summon the assistant, so Android never calls `onHandleScreenshot`
 * for it. The bundled JPEG goes where the session would have written one, and the
 * overlay takes it from there as it would a real one. Compiled into the `e2e` flavor alone.
 */
fun fixtureScreenContext(context: Context, intent: Intent): ScreenContext? {
    if (intent.data?.getQueryParameter("screenshot") != "fixture") return null
    val file = ScreenshotEncoder.file(context)
    file.parentFile?.mkdirs()
    context.assets.open("assist-screenshot.jpg").use { input ->
        file.outputStream().use { input.copyTo(it) }
    }
    return ScreenContext(appLabel = "Boutique", screenshotPath = file.absolutePath)
}
