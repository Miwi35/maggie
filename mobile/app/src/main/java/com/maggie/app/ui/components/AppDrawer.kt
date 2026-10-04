package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

private val SETTINGS_DESTINATION = DrawerDestination("settings", "Paramètres", Icons.Default.Settings)

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
