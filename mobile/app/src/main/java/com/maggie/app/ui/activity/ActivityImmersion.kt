package com.maggie.app.ui.activity

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * What an activity takes from the device while it runs: the whole screen, and a screen that stays on.
 * [enter] and [exit] are the only two calls, so a test can watch them without a window.
 */
interface ActivityImmersion {
    fun enter()

    fun exit()
}

/**
 * Immersive mode (system bars hidden, back with a swipe from the edge) and `FLAG_KEEP_SCREEN_ON`, on the
 * window of the app's activity. [exit] gives back the bars, the flag and the behaviour the window had.
 */
class WindowImmersion(private val window: Window) : ActivityImmersion {
    private val controller: WindowInsetsControllerCompat = WindowCompat.getInsetsController(window, window.decorView)
    private var previousBehavior: Int? = null
    private var keptOnBefore = false

    override fun enter() {
        if (previousBehavior != null) return
        previousBehavior = controller.systemBarsBehavior
        keptOnBefore = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun exit() {
        val behavior = previousBehavior ?: return
        previousBehavior = null
        if (!keptOnBefore) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.show(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = behavior
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The immersion of the window this composition is drawn in; nothing to take when it has no activity. */
@Composable
fun rememberWindowImmersion(): ActivityImmersion {
    val window = LocalContext.current.findActivity()?.window
    return remember(window) { window?.let(::WindowImmersion) ?: NoImmersion }
}

private object NoImmersion : ActivityImmersion {
    override fun enter() = Unit

    override fun exit() = Unit
}
