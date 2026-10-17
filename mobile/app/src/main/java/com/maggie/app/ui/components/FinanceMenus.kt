package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.layout.NavigationKind
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.navigation.FinanceMenu

/**
 * What a tap on a Finance menu does. Provided once by the `NavGraph`; absent (`null`) wherever
 * a finance screen is drawn without the graph, which is then drawn without its tabs.
 */
val LocalFinanceMenuSelection = compositionLocalOf<((FinanceMenu) -> Unit)?> { null }

/**
 * A finance screen's top bar and, under it, the seven menus (MAG-426): the bar the screen
 * already had, then the tabs row.
 *
 * The tabs sit in the screen's own `topBar` slot rather than above the screens, because the
 * title bar belongs to each screen (its back arrow, its actions) and the row goes *under* it.
 * The bottom of the window stays Maggie's.
 */
@Composable
fun FinanceTopBar(route: String, topBar: @Composable () -> Unit) {
    Column {
        topBar()
        FinanceMenuTabs(route)
    }
}

/**
 * The seven menus, in a row that scrolls sideways; the active one is highlighted and brought
 * back on screen whenever it changes — `ScrollableTabRow` scrolls its selected tab into view
 * on first draw and on every selection, which is what opening Configuration from a link needs.
 *
 * Drawn on a window without a rail only: from 600 dp the same seven are [FinanceNavigationRail].
 */
@Composable
fun FinanceMenuTabs(route: String) {
    val onSelect = LocalFinanceMenuSelection.current ?: return
    val active = FinanceMenu.of(route) ?: return
    if (rememberAppLayout().navigation == NavigationKind.RAIL) return

    ScrollableTabRow(
        selectedTabIndex = active.ordinal,
        edgePadding = 8.dp,
        modifier = Modifier.testTag(UiTags.FINANCE_MENU_TABS),
    ) {
        FinanceMenu.entries.forEach { menu ->
            Tab(
                selected = menu == active,
                onClick = { onSelect(menu) },
                text = { Text(menu.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.testTag(UiTags.financeMenu(menu.key)),
            )
        }
    }
}

/**
 * The seven menus as a rail, on the windows that have one (tablet, foldable, phone in landscape).
 *
 * Built like [MaggieNavigationRail], for the same reasons: the destinations scroll inside the
 * rail on a window too short for seven of them, and [chatAction] is the rail header where the
 * window has no band at the bottom for the conversation.
 */
@Composable
fun FinanceNavigationRail(
    currentRoute: String?,
    onSelect: (FinanceMenu) -> Unit,
    modifier: Modifier = Modifier,
    chatAction: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val active = FinanceMenu.of(currentRoute)

    NavigationRail(modifier = modifier.fillMaxHeight().testTag(UiTags.FINANCE_MENU_RAIL), header = chatAction) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            FinanceMenu.entries.forEach { menu ->
                NavigationRailItem(
                    icon = { Icon(menu.icon, contentDescription = null) },
                    label = { Text(menu.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = menu == active,
                    onClick = { onSelect(menu) },
                    modifier = Modifier.testTag(UiTags.financeMenu(menu.key)),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
