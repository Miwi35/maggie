package com.maggie.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.maggie.app.ui.screens.agenda.AgendaScreen
import com.maggie.app.ui.screens.chat.ChatScreen

sealed class Screen(val route: String, val label: String) {
    data object Agenda : Screen("agenda", "Agenda")
    data object Chat : Screen("chat", "Chat")
}

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.CalendarMonth, contentDescription = "Agenda") },
                    label = { Text("Agenda") },
                    selected = currentRoute == Screen.Agenda.route,
                    onClick = { navController.navigate(Screen.Agenda.route) { launchSingleTop = true } },
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Chat, contentDescription = "Chat") },
                    label = { Text("Chat") },
                    selected = currentRoute == Screen.Chat.route,
                    onClick = { navController.navigate(Screen.Chat.route) { launchSingleTop = true } },
                )
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Agenda.route,
            modifier = Modifier.padding(paddingValues),
        ) {
            composable(Screen.Agenda.route) { AgendaScreen() }
            composable(Screen.Chat.route) { ChatScreen() }
        }
    }
}
