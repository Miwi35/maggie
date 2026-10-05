package com.maggie.app.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * How wide and how tall the window is, and what the app draws because of it (MAG-35).
 *
 * The app used to have one layout for every screen: a burger, a modal drawer and a
 * conversation that only ever existed as a sheet over what it was asked about. That
 * reads the same on the 280 dp cover screen of a Flip and on a 1280 dp tablet in
 * landscape, where half the window is a menu nobody needs.
 *
 * The decision is [appLayoutFor], a function of two integers — not a composable and
 * not `calculateWindowSizeClass(activity)`. The ticket asks for six formats to be
 * *verified* without buying a phone, so the whole rule has to be readable by a test
 * that names a format: `appLayoutFor(800, 1280).navigation == RAIL` is the tablet in
 * portrait. [rememberAppLayout] is the same function over `LocalConfiguration`,
 * which is what Robolectric's `qualifiers` and `@Preview`'s `widthDp` both write —
 * so the previews and the screen tests go through this rule rather than a copy.
 */
data class AppLayout(
    val width: WindowWidth,
    val height: WindowHeight,
    /** A rail, or the burger and the modal drawer the phone has always had. */
    val navigation: NavigationKind,
    /** The conversation has room to stay on screen beside the content. */
    val chatPanelFits: Boolean,
)

/** The Material 3 width breakpoints: compact < 600 ≤ medium < 840 ≤ expanded. */
enum class WindowWidth {
    COMPACT,
    MEDIUM,
    EXPANDED,
    ;

    companion object {
        fun of(dp: Int): WindowWidth = when {
            dp < 600 -> COMPACT
            dp < 840 -> MEDIUM
            else -> EXPANDED
        }
    }
}

/** The Material 3 height breakpoints: compact < 480 ≤ medium < 900 ≤ expanded. */
enum class WindowHeight {
    COMPACT,
    MEDIUM,
    EXPANDED,
    ;

    companion object {
        fun of(dp: Int): WindowHeight = when {
            dp < 480 -> COMPACT
            dp < 900 -> MEDIUM
            else -> EXPANDED
        }
    }
}

enum class NavigationKind {
    /** The burger and the drawer that slides over the content. */
    MODAL_DRAWER,

    /** The rail pinned to the left edge, always visible. */
    RAIL,
}

/** The rail: icons and short labels, no more. Material's own `NavigationRail` width. */
val RAIL_WIDTH = 80.dp

/** The permanent conversation panel. A narrower column truncates every bubble. */
val CHAT_PANEL_WIDTH = 360.dp

/** What the app draws in a window of `widthDp` × `heightDp`. */
fun appLayoutFor(widthDp: Int, heightDp: Int): AppLayout {
    val width = WindowWidth.of(widthDp)
    val height = WindowHeight.of(heightDp)

    val navigation = if (width == WindowWidth.COMPACT) {
        NavigationKind.MODAL_DRAWER
    } else {
        NavigationKind.RAIL
    }

    // A conversation in a 360 dp column needs vertical room to be worth the width:
    // on a phone in landscape (891 × 411) it would be a header, two bubbles and an
    // input, where the collapsed bar costs nothing and opens the full-height sheet.
    val chatPanelFits = width == WindowWidth.EXPANDED && height != WindowHeight.COMPACT

    return AppLayout(
        width = width,
        height = height,
        navigation = navigation,
        chatPanelFits = chatPanelFits,
    )
}

/**
 * [appLayoutFor] over the window the app is currently in.
 *
 * `LocalConfiguration` and not `WindowMetrics`: it is the one source Compose
 * recomposes on, and the one Robolectric's `qualifiers` and `@Preview`'s
 * `widthDp`/`heightDp` both write — so a preview, a screen test and a real foldable
 * being unfolded all go through this.
 */
@Composable
fun rememberAppLayout(): AppLayout {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        appLayoutFor(configuration.screenWidthDp, configuration.screenHeightDp)
    }
}
