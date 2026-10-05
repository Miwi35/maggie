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
import com.maggie.app.ui.components.MaggieNavigationRail
import com.maggie.app.ui.components.MaggieTopBar
import com.maggie.app.ui.theme.MaggieTheme

/**
 * The six formats of MAG-35, in one annotation.
 *
 * The owner has no foldable and no tablet — the « Z Flip » ticket was deleted for
 * that reason — so these are how the layout is looked at: open a composable
 * annotated with this in Android Studio and the six windows render side by side.
 * The same numbers are asserted on the JVM in `WindowLayoutTest` and
 * `AppShellScreenTest`, so a preview that looks wrong is a test that is already red.
 *
 * `widthDp`/`heightDp` write the preview's `Configuration`, which is what
 * [rememberAppLayout] reads — so a preview goes through the real decision, not a
 * copy of it.
 */
@Preview(name = "Téléphone 21:9", widthDp = 412, heightDp = 1000)
@Preview(name = "Pliable fermé", widthDp = 374, heightDp = 840)
@Preview(name = "Écran externe (Flip)", widthDp = 280, heightDp = 290)
@Preview(name = "Pliable ouvert", widthDp = 674, heightDp = 841)
@Preview(name = "Tablette portrait", widthDp = 800, heightDp = 1280)
@Preview(name = "Tablette paysage", widthDp = 1280, heightDp = 800)
annotation class MaggieWindowPreviews

/**
 * The real shell in the six formats: the real rail, the real top bar, the real
 * collapsed bar, over a stand-in list and a stand-in conversation.
 *
 * Stand-ins and not the app's own screens because a `@Preview` has no Koin
 * container, so no `ChatViewModel` and no repositories. What is being looked at is
 * the frame — where the navigation is, and whether the conversation is beside the
 * content or behind a bar.
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
                { MaggieNavigationRail(currentRoute = "cookbook", onNavigate = {}) }
            } else {
                null
            },
            chatPanel = if (layout.chatPanelFits) {
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
                    )
                },
                bottomBar = {
                    if (!layout.chatPanelFits) {
                        ChatBottomBar(onOpenChat = {}, activeContextCount = 1)
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
