package com.maggie.app.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
 *   bar at the bottom of the content; or, on a wide window, it is folded away and
 *   [chatPortrait] is Maggie's face in a strip under the content (MAG-624).
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
    chatPortrait: (@Composable () -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val frame: @Composable () -> Unit = {
        Row(modifier = Modifier.fillMaxSize()) {
            rail?.invoke()

            if (chatPortrait != null) {
                // Under the content and not over it: a floating button on top would hide the
                // content's own bottom-right action (« + » of the lists).
                Column(modifier = Modifier.weight(1f)) {
                    Row(modifier = Modifier.weight(1f)) { content() }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) { chatPortrait() }
                }
            } else {
                content()
            }

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
