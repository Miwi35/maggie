package com.maggie.app.ui.layout

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The frame around every screen: navigation on the left, the content in the middle,
 * the conversation on the right when it fits (MAG-35).
 *
 * It takes **no decision**. Which slots are filled is [appLayoutFor]'s answer turned
 * into a `Chrome` by the caller, so the rules are unit-tested next to the routes
 * they depend on and this file only arranges what it is handed. A `null` slot is a
 * part of the frame this window does not have:
 *
 * - `rail == null` — the destinations are behind the burger, so the whole frame goes
 *   inside a [ModalNavigationDrawer]. That is the phone, unchanged.
 * - `chatPanel == null` — the conversation is a sheet, reached from the collapsed
 *   bar at the bottom of the content.
 *
 * [content] is a `RowScope` and carries the app's `Scaffold`: the top bar and the
 * collapsed bar belong to the content, not to the frame, and the `Scaffold` needs
 * `Modifier.weight(1f)` to take what the rail and the panel leave.
 */
@Composable
fun AppShell(
    drawerState: DrawerState,
    drawerGesturesEnabled: Boolean,
    drawer: @Composable () -> Unit,
    rail: (@Composable () -> Unit)?,
    chatPanel: (@Composable () -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    val frame: @Composable () -> Unit = {
        Row(modifier = Modifier.fillMaxSize()) {
            rail?.invoke()

            content()

            if (chatPanel != null) {
                VerticalDivider()
                chatPanel()
            }
        }
    }

    if (rail != null) {
        frame()
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerGesturesEnabled,
            drawerContent = drawer,
            content = frame,
        )
    }
}
