package com.maggie.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The app's theme, derived from `design/tokens.json` — the same file the admin
 * reads (MAG-39).
 *
 * What changed when the tokens arrived: the scheme was six roles out of
 * Material's twenty-nine, so almost everything was Material's own baseline and
 * had nothing to do with Maggie. The **seventeen roles set below** are the
 * admin's values now, to the hex — the accent and its container pair, the
 * secondary pair, `background`, `surface`, `surfaceVariant` and their `on`
 * colours, `outline`, and `error` / `onError`. Three of them are a visible
 * change on the phone, all three deliberate:
 *
 *  - the **dark** mode's `primary` was the pale lavender `#E8DEFF` with dark
 *    text on it, Material's convention for a dark scheme. It is the brand violet
 *    in both modes now, as it is on the web: one identity, one accent;
 *  - the surfaces move from Material's near-white and near-black to radiant's
 *    `#F0F1F6` / `#110E1C`;
 *  - `error` is the admin's pink rather than Material's red.
 *
 * What is **not** covered: the roles left on Material's baseline —
 * `surfaceContainer`, `surfaceContainerLow/High/Highest`, `surfaceBright`,
 * `surfaceDim`, `outlineVariant`, the `tertiary*` family and `errorContainer`.
 * Live screens read some of them: `GroceryListsScreen` takes a dragged row from
 * `surfaceContainerHighest`, and `BudgetScreen`, `FinanceDashboardScreen`,
 * `CushionScreen` and `VoiceControlBar` use `tertiary` / `errorContainer` as
 * status colours — which is a signal wearing a Material role. Naming them is
 * MAG-90's module-by-module audit, screen by screen, not a guess made here.
 *
 * The typeface is not here: Gabarito has to be bundled in `res/font/`, with its
 * licence, and that is a ticket of its own — the colours and the shapes are what
 * this one declares. Until it lands the app writes in Roboto at Material's sizes,
 * which `typography.size` in the token file already names.
 */
internal val MaggieLightColorScheme = lightColorScheme(
    primary = MaggieTokens.Brand.primary,
    onPrimary = MaggieTokens.Brand.onPrimary,
    primaryContainer = MaggieTokens.Brand.containerLight,
    onPrimaryContainer = MaggieTokens.Brand.onContainerLight,
    secondary = MaggieTokens.Brand.secondaryLight,
    onSecondary = MaggieTokens.Brand.onPrimary,
    secondaryContainer = MaggieTokens.Brand.containerLight,
    onSecondaryContainer = MaggieTokens.Brand.onContainerLight,
    background = MaggieTokens.surfaceLight.background,
    onBackground = MaggieTokens.surfaceLight.text,
    surface = MaggieTokens.surfaceLight.paper,
    onSurface = MaggieTokens.surfaceLight.text,
    surfaceVariant = MaggieTokens.surfaceLight.background,
    onSurfaceVariant = MaggieTokens.surfaceLight.textMuted,
    outline = MaggieTokens.surfaceLight.textMuted,
    error = MaggieTokens.Feedback.error,
    onError = MaggieTokens.Brand.onPrimary,
)

internal val MaggieDarkColorScheme = darkColorScheme(
    primary = MaggieTokens.Brand.primary,
    onPrimary = MaggieTokens.Brand.onPrimary,
    primaryContainer = MaggieTokens.Brand.containerDark,
    onPrimaryContainer = MaggieTokens.Brand.onContainerDark,
    secondary = MaggieTokens.Brand.secondaryDark,
    // Not white: `#FF83F6` is a light pink, and white on it is a contrast of 2.14.
    onSecondary = MaggieTokens.surfaceDark.background,
    secondaryContainer = MaggieTokens.Brand.containerDark,
    onSecondaryContainer = MaggieTokens.Brand.onContainerDark,
    background = MaggieTokens.surfaceDark.background,
    onBackground = MaggieTokens.surfaceDark.text,
    surface = MaggieTokens.surfaceDark.paper,
    onSurface = MaggieTokens.surfaceDark.text,
    surfaceVariant = MaggieTokens.surfaceDark.paper,
    onSurfaceVariant = MaggieTokens.surfaceDark.textMuted,
    outline = MaggieTokens.surfaceDark.textMuted,
    error = MaggieTokens.Feedback.error,
    onError = MaggieTokens.Brand.onPrimary,
)

/** Material's five shape roles over the shared radii — `small` is radiant's 6 dp. */
val MaggieShapes = Shapes(
    extraSmall = RoundedCornerShape(MaggieTokens.Radius.xs),
    small = RoundedCornerShape(MaggieTokens.Radius.sm),
    medium = RoundedCornerShape(MaggieTokens.Radius.md),
    large = RoundedCornerShape(MaggieTokens.Radius.lg),
    extraLarge = RoundedCornerShape(MaggieTokens.Radius.xl),
)

@Composable
fun MaggieTheme(
    themePreference: String = "system",
    content: @Composable () -> Unit,
) {
    val colorScheme = when (themePreference) {
        "dark" -> MaggieDarkColorScheme
        "light" -> MaggieLightColorScheme
        else -> if (isSystemInDarkTheme()) MaggieDarkColorScheme else MaggieLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MaggieShapes,
        content = content,
    )
}
