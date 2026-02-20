package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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

        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
            label = { Text("Tableau de bord") },
            selected = currentRoute == "dashboard",
            onClick = {
                onNavigate("dashboard")
                onCloseDrawer()
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        NavigationDrawerItem(
            icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
            label = { Text("Calendrier") },
            selected = currentRoute == "calendar",
            onClick = {
                onNavigate("calendar")
                onCloseDrawer()
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Restaurant, contentDescription = null) },
            label = { Text("Cuisine") },
            selected = currentRoute == "cookbook",
            onClick = {
                onNavigate("cookbook")
                onCloseDrawer()
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Chat, contentDescription = null) },
            label = { Text("Chat") },
            selected = currentRoute == "chat",
            onClick = {
                onNavigate("chat")
                onCloseDrawer()
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        NavigationDrawerItem(
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
            label = { Text("Proactions") },
            selected = currentRoute == "proactions",
            onClick = {
                onNavigate("proactions")
                onCloseDrawer()
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("Paramètres") },
            selected = currentRoute == "settings",
            onClick = {
                onNavigate("settings")
                onCloseDrawer()
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}
