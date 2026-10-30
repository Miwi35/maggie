package com.maggie.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.isMaggieDark
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The hue of a navigation entry (`charte/Tiroir`, MAG-620): the module's own where the theme has
 * one, Maggie's violet for the Accueil, a neutral for Réglages.
 */
enum class DrawerTint { ACCENT, AGENDA, TASKS, GROCERY, CUISINE, FINANCE, SPORT, NEUTRAL }

data class DrawerDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val tint: DrawerTint = DrawerTint.ACCENT,
)

// One entry per module, in the mock-up's order. No « Chat »: Maggie lives in the bottom bar
// (and in the rail header on a short window), not in the navigation. Loisirs joins when its
// module exists.
val DRAWER_DESTINATIONS = listOf(
    DrawerDestination("home", "Accueil", Icons.Default.Home, DrawerTint.ACCENT),
    DrawerDestination("calendar", "Agenda", Icons.Default.DateRange, DrawerTint.AGENDA),
    DrawerDestination("tasks", "Tâches", Icons.Default.TaskAlt, DrawerTint.TASKS),
    DrawerDestination("grocery", "Courses", Icons.Default.ShoppingCart, DrawerTint.GROCERY),
    DrawerDestination("cookbook", "Cuisine", Icons.Default.Restaurant, DrawerTint.CUISINE),
    DrawerDestination("finance_dashboard", "Finance", Icons.Default.Insights, DrawerTint.FINANCE),
    DrawerDestination("health/suivi", "Santé", Icons.Default.FitnessCenter, DrawerTint.SPORT),
)

internal val SETTINGS_DESTINATION = DrawerDestination("settings", "Réglages", Icons.Default.Settings, DrawerTint.NEUTRAL)

/** The destinations the rail shows, in order: the modules, then the settings. */
val RAIL_DESTINATIONS = DRAWER_DESTINATIONS + SETTINGS_DESTINATION

@Composable
internal fun DrawerTint.color(): Color {
    val dark = isMaggieDark()
    fun pick(pair: Pair<Color, Color>) = if (dark) pair.second else pair.first
    return when (this) {
        DrawerTint.ACCENT -> MaterialTheme.colorScheme.primary
        DrawerTint.AGENDA -> pick(MaggieTokens.Module.agenda)
        DrawerTint.TASKS -> pick(MaggieTokens.Module.taches)
        DrawerTint.GROCERY -> pick(MaggieTokens.Module.cuisine)
        DrawerTint.CUISINE -> pick(MaggieTokens.Module.cuisine)
        DrawerTint.FINANCE -> pick(MaggieTokens.Module.finance)
        DrawerTint.SPORT -> pick(MaggieTokens.Module.sport)
        DrawerTint.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** The share of its hue behind an entry's tile and under the selected entry. */
private const val TINT_ALPHA = 0.16f

private val dateFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

/** « Dimanche 25 octobre »: the drawer header's day. */
internal fun drawerDateLabel(date: LocalDate): String =
    date.format(dateFormatter).replaceFirstChar { it.uppercase(Locale.FRENCH) }

/** `charte/Tiroir`: 308 dp on a 390 dp phone, the rest being the page under the scrim that closes the drawer. */
private const val SHEET_WIDTH_FRACTION = 308f / 390f

private val SheetShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)

/**
 * The drawer of `charte/Tiroir` (MAG-620): Maggie's avatar and the day, one tinted tile per module with its
 * « state of the day » line and counter ([summary], from the data the app holds: an entry without one shows
 * nothing, never a made-up figure), the selection in the module's hue, Réglages at the bottom.
 */
@Composable
fun AppDrawerContent(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onCloseDrawer: () -> Unit,
    today: LocalDate = LocalDate.now(),
    summary: DrawerSummary = DrawerSummary(),
) {
    ModalDrawerSheet(
        drawerShape = SheetShape,
        modifier = Modifier.fillMaxWidth(SHEET_WIDTH_FRACTION).testTag(UiTags.DRAWER_SHEET),
    ) {
        DrawerHeader(today)

        DRAWER_DESTINATIONS.forEach {
            DrawerItem(it, currentRoute, onNavigate, onCloseDrawer, status = summary[it.route])
        }

        Spacer(modifier = Modifier.weight(1f))

        DrawerItem(SETTINGS_DESTINATION, currentRoute, onNavigate, onCloseDrawer)

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun DrawerHeader(today: LocalDate) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 28.dp, vertical = 24.dp)
            .testTag(UiTags.DRAWER_HEADER),
    ) {
        MaggieAvatar(size = 44.dp)
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(text = "Maggie", style = MaterialTheme.typography.titleLarge)
            Text(
                text = drawerDateLabel(today),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The entry's icon on a square tile of its hue, at [TINT_ALPHA]. */
@Composable
private fun DestinationTile(destination: DrawerDestination) {
    val tint = destination.tint.color()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = TINT_ALPHA)),
    ) {
        Icon(destination.icon, contentDescription = null, tint = tint)
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
                val tint = destination.tint.color()
                NavigationRailItem(
                    icon = { Icon(destination.icon, contentDescription = null) },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = tint,
                        selectedTextColor = tint,
                        indicatorColor = tint.copy(alpha = TINT_ALPHA),
                    ),
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
    status: DrawerEntryStatus? = null,
) {
    val tint = destination.tint.color()
    val count = status?.count
    NavigationDrawerItem(
        icon = { DestinationTile(destination) },
        label = {
            Column {
                Text(destination.label)
                status?.line?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(UiTags.drawerLine(destination.route)),
                    )
                }
            }
        },
        badge = count?.takeIf { it > 0 }?.let { n -> { Text("$n", modifier = Modifier.testTag(UiTags.drawerCount(destination.route))) } },
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = tint.copy(alpha = TINT_ALPHA),
            selectedTextColor = tint,
        ),
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
