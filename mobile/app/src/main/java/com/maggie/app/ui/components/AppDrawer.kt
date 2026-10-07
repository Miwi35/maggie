package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags

data class DrawerDestination(val route: String, val label: String, val icon: ImageVector)

// One entry per module: the finance dashboard leads to the rest of the module.
val DRAWER_DESTINATIONS = listOf(
    DrawerDestination("dashboard", "Tableau de bord", Icons.Default.Dashboard),
    DrawerDestination("chat", "Chat", Icons.Default.Chat),
    DrawerDestination("calendar", "Calendrier", Icons.Default.DateRange),
    DrawerDestination("cookbook", "Cuisine", Icons.Default.Restaurant),
    DrawerDestination("grocery", "Courses", Icons.Default.ShoppingCart),
    DrawerDestination("finance_dashboard", "Finance", Icons.Default.Insights),
)

internal val SETTINGS_DESTINATION = DrawerDestination("settings", "Paramètres", Icons.Default.Settings)

/** The destinations the rail shows, in order: the modules, then the settings. */
val RAIL_DESTINATIONS = DRAWER_DESTINATIONS + SETTINGS_DESTINATION

@Composable
fun AppDrawerContent(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onCloseDrawer: () -> Unit,
) {
    ModalDrawerSheet {
        Text(
            text = "Maggie",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
        )

        DRAWER_DESTINATIONS.forEach { DrawerItem(it, currentRoute, onNavigate, onCloseDrawer) }

        Spacer(modifier = Modifier.weight(1f))

        DrawerItem(SETTINGS_DESTINATION, currentRoute, onNavigate, onCloseDrawer)

        Spacer(modifier = Modifier.height(12.dp))
    }
}

/**
 * The same destinations, pinned to the left edge instead of hidden behind a burger
 * (MAG-35). Drawn from 600 dp wide, where a modal drawer would slide over a window
 * that has room to show it.
 *
 * Nothing to close: the rail is already visible, so a tap is a navigation and that
 * is all. [SETTINGS_DESTINATION] is the last entry rather than a footer pinned to
 * the bottom, and the rail scrolls — seven entries are ~500 dp, and a phone in
 * landscape is 411 dp tall. A `weight(1f)` spacer inside a scrollable column is a
 * crash on infinite constraints, so there is no pinning it either.
 *
 * The scroll is on a column *inside* the rail, not on the rail: `NavigationRail`
 * applies its own system-bar padding within its Surface, so scrolling the rail
 * itself would scroll the top inset away with the entries — on the short window
 * where the rail actually scrolls — and padding the rail from outside would stop
 * its container colour short of the edges.
 *
 * @param chatAction the rail's header, drawn above the destinations and outside the
 *   scroll: on a window too short for a band under the content, the conversation is
 *   reached from here ([com.maggie.app.ui.components.ChatRailActions]). `null`
 *   everywhere else, which is every window that still has that band.
 */
@Composable
fun MaggieNavigationRail(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    chatAction: (@Composable ColumnScope.() -> Unit)? = null,
) {
    NavigationRail(modifier = modifier.fillMaxHeight().testTag(UiTags.NAV_RAIL), header = chatAction) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            RAIL_DESTINATIONS.forEach { destination ->
                NavigationRailItem(
                    icon = { Icon(destination.icon, contentDescription = null) },
                    label = { Text(destination.label) },
                    selected = currentRoute == destination.route,
                    onClick = { onNavigate(destination.route) },
                    modifier = Modifier.testTag(UiTags.railItem(destination.route)),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DrawerItem(
    destination: DrawerDestination,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onCloseDrawer: () -> Unit,
) {
    NavigationDrawerItem(
        icon = { Icon(destination.icon, contentDescription = null) },
        label = { Text(destination.label) },
        selected = currentRoute == destination.route,
        onClick = {
            onNavigate(destination.route)
            onCloseDrawer()
        },
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .testTag(UiTags.drawerItem(destination.route)),
    )
}
