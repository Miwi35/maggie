package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
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
            icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
            label = { Text("Courses") },
            selected = currentRoute == "grocery",
            onClick = {
                onNavigate("grocery")
                onCloseDrawer()
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        NavigationDrawerItem(
            icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) },
            label = { Text("Comptes") },
            selected = currentRoute == "accounts",
            onClick = {
                onNavigate("accounts")
                onCloseDrawer()
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Category, contentDescription = null) },
            label = { Text("Catégories") },
            selected = currentRoute == "categories",
            onClick = {
                onNavigate("categories")
                onCloseDrawer()
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Savings, contentDescription = null) },
            label = { Text("Budgets") },
            selected = currentRoute == "budgets",
            onClick = {
                onNavigate("budgets")
                onCloseDrawer()
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Shield, contentDescription = null) },
            label = { Text("Matelas") },
            selected = currentRoute == "cushion",
            onClick = {
                onNavigate("cushion")
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
