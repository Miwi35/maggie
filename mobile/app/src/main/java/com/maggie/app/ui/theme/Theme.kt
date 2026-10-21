package com.maggie.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * The app's theme, derived from `design/tokens.json` — the same file the admin
 * reads (MAG-39), in the « Veilleuse » identity (MAG-311).
 *
 * One accent, no second colour: the secondary roles repeat the primary ones.
 * The accent is per mode — `primaryLight` on white-ish surfaces, `primary` on
 * the dark ones — and so is `error`, read from the mode's own feedback set.
 *
 * The charter's two states are named (MAG-535): `tertiary*` is « attention »
 * (the mode's warning — an envelope overcommitted, a task waiting) and
 * `errorContainer` the overspend, each a tint of its colour over the surface.
 *
 * Not covered: the roles left on Material's baseline — `surfaceContainer*`,
 * `surfaceBright`, `surfaceDim` and `outlineVariant`. Live screens read some
 * of them; naming them is MAG-90's module-by-module audit, not a guess made here.
 *
 * The typeface is not here: Geist has to be bundled in `res/font/`, with its
 * licence, and that is a ticket of its own. Until then the app writes in Roboto
 * at Material's sizes, which `typography.size` already names.
 */
/** A state colour as a container: the colour, lightly, over the surface it sits on. */
private fun stateContainer(state: Color, surface: Color): Color = state.copy(alpha = STATE_CONTAINER_ALPHA).compositeOver(surface)

internal const val STATE_CONTAINER_ALPHA = 0.22f

internal val MaggieLightColorScheme = lightColorScheme(
    primary = MaggieTokens.Brand.primaryLight,
    onPrimary = MaggieTokens.Brand.onPrimaryLight,
    primaryContainer = MaggieTokens.Brand.containerLight,
    onPrimaryContainer = MaggieTokens.Brand.onContainerLight,
    secondary = MaggieTokens.Brand.primaryLight,
    onSecondary = MaggieTokens.Brand.onPrimaryLight,
    secondaryContainer = MaggieTokens.Brand.containerLight,
    onSecondaryContainer = MaggieTokens.Brand.onContainerLight,
    background = MaggieTokens.surfaceLight.background,
    onBackground = MaggieTokens.surfaceLight.text,
    surface = MaggieTokens.surfaceLight.paper,
    onSurface = MaggieTokens.surfaceLight.text,
    surfaceVariant = MaggieTokens.surfaceLight.background,
    onSurfaceVariant = MaggieTokens.surfaceLight.textMuted,
    outline = MaggieTokens.surfaceLight.textMuted,
    error = MaggieTokens.feedbackLight.error,
    onError = MaggieTokens.Brand.onPrimaryLight,
    errorContainer = stateContainer(MaggieTokens.feedbackLight.error, MaggieTokens.surfaceLight.paper),
    onErrorContainer = MaggieTokens.feedbackLight.error,
    tertiary = MaggieTokens.feedbackLight.warning,
    onTertiary = MaggieTokens.Brand.onPrimaryLight,
    tertiaryContainer = stateContainer(MaggieTokens.feedbackLight.warning, MaggieTokens.surfaceLight.paper),
    onTertiaryContainer = MaggieTokens.feedbackLight.warning,
)

internal val MaggieDarkColorScheme = darkColorScheme(
    primary = MaggieTokens.Brand.primary,
    onPrimary = MaggieTokens.Brand.onPrimary,
    primaryContainer = MaggieTokens.Brand.containerDark,
    onPrimaryContainer = MaggieTokens.Brand.onContainerDark,
    secondary = MaggieTokens.Brand.primary,
    onSecondary = MaggieTokens.Brand.onPrimary,
    secondaryContainer = MaggieTokens.Brand.containerDark,
    onSecondaryContainer = MaggieTokens.Brand.onContainerDark,
    background = MaggieTokens.surfaceDark.background,
    onBackground = MaggieTokens.surfaceDark.text,
    surface = MaggieTokens.surfaceDark.paper,
    onSurface = MaggieTokens.surfaceDark.text,
    surfaceVariant = MaggieTokens.surfaceDark.paper,
    onSurfaceVariant = MaggieTokens.surfaceDark.textMuted,
    outline = MaggieTokens.surfaceDark.textMuted,
    error = MaggieTokens.feedbackDark.error,
    onError = MaggieTokens.Brand.onPrimary,
    errorContainer = stateContainer(MaggieTokens.feedbackDark.error, MaggieTokens.surfaceDark.paper),
    onErrorContainer = MaggieTokens.feedbackDark.error,
    tertiary = MaggieTokens.feedbackDark.warning,
    onTertiary = MaggieTokens.Brand.onPrimary,
    tertiaryContainer = stateContainer(MaggieTokens.feedbackDark.warning, MaggieTokens.surfaceDark.paper),
    onTertiaryContainer = MaggieTokens.feedbackDark.warning,
)

/** Material's five shape roles over the shared radii. */
val MaggieShapes = Shapes(
    extraSmall = RoundedCornerShape(MaggieTokens.Radius.xs),
    small = RoundedCornerShape(MaggieTokens.Radius.sm),
    medium = RoundedCornerShape(MaggieTokens.Radius.md),
    large = RoundedCornerShape(MaggieTokens.Radius.lg),
    extraLarge = RoundedCornerShape(MaggieTokens.Radius.xl),
)

/** The mock-ups are drawn on the dark Veilleuse: it is what an install shows until its owner picks another. */
const val THEME_DEFAULT = "dark"

/** « Clair » and « Système » are choices; anything else, an unset or unknown value included, is the dark default. */
internal fun usesDarkTheme(choice: String?, systemIsDark: Boolean): Boolean = when (choice) {
    "light" -> false
    "system" -> systemIsDark
    else -> true
}

@Composable
fun MaggieTheme(
    themePreference: String = THEME_DEFAULT,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        if (usesDarkTheme(themePreference, isSystemInDarkTheme())) MaggieDarkColorScheme else MaggieLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MaggieShapes,
        content = content,
    )
}
