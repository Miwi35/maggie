package com.maggie.app.ui.components

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.maggie.app.ui.UiTags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaggieTopBar(
    title: String,
    onMenuClick: () -> Unit,
    unreadCount: Int = 0,
    onNotificationsClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onMenuClick, modifier = Modifier.testTag(UiTags.NAV_MENU)) {
                Icon(Icons.Default.Menu, contentDescription = "Menu")
            }
        },
        actions = {
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
        },
    )
}
