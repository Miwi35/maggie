package com.maggie.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags

/** 48 dp instead of 64: the height a short window gets back for its content. */
private val DENSE_HEIGHT = 48.dp

/**
 * @param onMenuClick `null` when a [MaggieNavigationRail] is on screen (MAG-35):
 *   the destinations are already visible, and a burger that opens a drawer over
 *   them would be a second way to the same six routes.
 * @param actionsInBar `false` on a phone (MAG-624): the bar is the menu and the title, as in the mock-up,
 *   and the search and the notifications are entries of the drawer. `true` where there is no burger
 *   to a drawer — a window with a rail — so those two do not become unreachable.
 * @param dense `true` on a window under 480 dp tall (MAG-35): a title and two icons
 *   over 64 dp is 16 % of a phone in landscape, and the icons stay 48 dp wide, so
 *   nothing but the empty band above and below the title is lost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaggieTopBar(
    title: String,
    onMenuClick: (() -> Unit)?,
    unreadCount: Int = 0,
    onNotificationsClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    dense: Boolean = false,
    actionsInBar: Boolean = true,
) {
    TopAppBar(
        expandedHeight = if (dense) DENSE_HEIGHT else TopAppBarDefaults.TopAppBarExpandedHeight,
        title = { Text(title, modifier = Modifier.testTag(UiTags.TOP_BAR_TITLE)) },
        navigationIcon = {
            if (onMenuClick != null) {
                IconButton(onClick = onMenuClick, modifier = Modifier.testTag(UiTags.NAV_MENU)) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
            }
        },
        actions = {
            if (actionsInBar) BarActions(unreadCount, onNotificationsClick, onSearchClick)
        },
    )
}

/** The search and the notifications at the right of a bar — of the generic one, or of a module's breadcrumb (MAG-670). */
@Composable
fun RowScope.BarActions(
    unreadCount: Int,
    onNotificationsClick: () -> Unit,
    onSearchClick: () -> Unit,
) {
    IconButton(onClick = onSearchClick) {
        Icon(Icons.Default.Search, contentDescription = "Rechercher")
    }
    IconButton(onClick = onNotificationsClick) {
        if (unreadCount > 0) {
            BadgedBox(badge = { Badge { Text("$unreadCount") } }) {
                Icon(Icons.Default.Notifications, contentDescription = "Notifications")
            }
        } else {
            Icon(Icons.Default.Notifications, contentDescription = "Notifications")
        }
    }
}
