package com.maggie.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/** The modules whose screens wear their own hue (charter § 2): its (light, dark) pair. */
enum class ModuleHue(internal val hue: Pair<Color, Color>) {
    FINANCE(MaggieTokens.Module.finance),
    TASKS(MaggieTokens.Module.taches),
    CUISINE(MaggieTokens.Module.cuisine),
    HEALTH(MaggieTokens.Module.sport),
}

/** Whether the app is drawn on the dark Veilleuse — the theme's own choice, not the system's. */
@Composable
@ReadOnlyComposable
fun isMaggieDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** The mode's surfaces — `paper`, `raised`, `track` — which the colour scheme has no role for. */
@Composable
@ReadOnlyComposable
fun maggieSurface(): MaggieTokens.Surface = if (isMaggieDark()) MaggieTokens.surfaceDark else MaggieTokens.surfaceLight

/**
 * This scheme, with the module's hue where the violet accent was: active tabs, rings, curves,
 * buttons and selected chips of a module's screens take its colour. Surfaces, inks and errors stay.
 */
internal fun ColorScheme.inModule(module: ModuleHue): ColorScheme {
    val dark = surface.luminance() < 0.5f
    val accent = if (dark) module.hue.second else module.hue.first
    val onAccent = if (dark) MaggieTokens.Brand.onPrimary else MaggieTokens.Brand.onPrimaryLight
    val container = accent.copy(alpha = STATE_CONTAINER_ALPHA).compositeOver(surface)

    return copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = container,
        onPrimaryContainer = accent,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = container,
        onSecondaryContainer = accent,
    )
}

/**
 * Draws [content] in [module]'s hue; no module, no change. The wrapper is always there, so going
 * from one module to another never moves [content] in the composition and rebuilds what is inside.
 */
@Composable
fun ModuleTheme(module: ModuleHue?, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = if (module == null) scheme else scheme.inModule(module),
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}
