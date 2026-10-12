package com.maggie.app.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.components.AppDrawerContent
import com.maggie.app.ui.components.ChatBottomBar
import com.maggie.app.ui.components.ChatRailActions
import com.maggie.app.ui.components.MaggieNavigationRail
import com.maggie.app.ui.components.MaggieTopBar
import com.maggie.app.ui.theme.MaggieTheme

/**
 * The six formats of MAG-35 — and the phone in landscape, which the recette refused.
 *
 * The owner has no foldable and no tablet, so this is how the layout is looked at:
 * annotate a composable and Android Studio renders the windows side by side.
 * `widthDp`/`heightDp` write the preview's `Configuration`, which is what
 * [rememberAppLayout] reads, and the same numbers are asserted in `WindowLayoutTest`
 * and `AppShellScreenTest` — so a preview goes through the real decision, and one
 * that looks wrong is a test that is already red.
 */
@Preview(name = "Téléphone 21:9", widthDp = 412, heightDp = 1000)
@Preview(name = "Téléphone paysage", widthDp = 891, heightDp = 411)
@Preview(name = "Pliable fermé", widthDp = 374, heightDp = 840)
@Preview(name = "Écran externe (Flip)", widthDp = 280, heightDp = 290)
@Preview(name = "Pliable ouvert", widthDp = 674, heightDp = 841)
@Preview(name = "Tablette portrait", widthDp = 800, heightDp = 1280)
@Preview(name = "Tablette paysage", widthDp = 1280, heightDp = 800)
annotation class MaggieWindowPreviews

/**
 * The real shell in every format — the real rail, top bar, collapsed bar and rail
 * actions — over a stand-in list and a stand-in conversation, because a `@Preview` has
 * no Koin container and so no `ChatViewModel`. What is looked at is the frame.
 */
@MaggieWindowPreviews
@Composable
internal fun AppShellPreview() {
    val layout = rememberAppLayout()
    val hasRail = layout.navigation == NavigationKind.RAIL

    MaggieTheme {
        AppShell(
            drawerState = rememberDrawerState(DrawerValue.Closed),
            drawerGesturesEnabled = true,
            drawer = {
                AppDrawerContent(currentRoute = "cookbook", onNavigate = {}, onCloseDrawer = {})
            },
            rail = if (hasRail) {
                {
                    MaggieNavigationRail(
                        currentRoute = "cookbook",
                        onNavigate = {},
                        chatAction = if (layout.chatEntry == ChatEntry.RAIL) {
                            { ChatRailActions(onOpenChat = {}) }
                        } else {
                            null
                        },
                    )
                }
            } else {
                null
            },
            chatPanel = if (layout.chatEntry == ChatEntry.PANEL) {
                { ChatPanelStandIn() }
            } else {
                null
            },
        ) {
            Scaffold(
                modifier = Modifier.weight(1f),
                topBar = {
                    MaggieTopBar(
                        title = "Cuisine",
                        onMenuClick = if (hasRail) null else { {} },
                        unreadCount = 2,
                        dense = layout.denseTopBar,
                    )
                },
                bottomBar = {
                    if (layout.chatEntry == ChatEntry.BOTTOM_BAR) {
                        ChatBottomBar(onOpenChat = {})
                    }
                },
            ) { paddingValues ->
                RecipeListStandIn(Modifier.padding(paddingValues))
            }
        }
    }
}

@Composable
private fun RecipeListStandIn(modifier: Modifier = Modifier) {
    val recipes = listOf("Chili sin carne", "Dal de lentilles corail", "Tarte aux poireaux", "Riz cantonais")

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(recipes) { name ->
            ListItem(
                headlineContent = { Text(name) },
                supportingContent = { Text("4 personnes · 35 min") },
            )
        }
    }
}

@Composable
private fun ChatPanelStandIn() {
    Surface(
        modifier = Modifier.width(CHAT_PANEL_WIDTH).fillMaxSize(),
        tonalElevation = 1.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Maggie", style = MaterialTheme.typography.titleMedium)
                Text(
                    "la conversation, en permanence",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
