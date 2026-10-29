package com.maggie.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags

/** 48 dp instead of 64: the height a short window gets back for its content. */
private val DENSE_HEIGHT = 48.dp

/**
 * @param onMenuClick `null` when a [MaggieNavigationRail] is on screen (MAG-35):
 *   the destinations are already visible, and a burger that opens a drawer over
 *   them would be a second way to the same six routes.
 * @param dense `true` on a window under 480 dp tall (MAG-35): a title and two icons
 *   over 64 dp is 16 % of a phone in landscape, and the icons stay 48 dp wide, so
 *   nothing but the empty band above and below the title is lost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaggieTopBar(
    title: String,
    onMenuClick: (() -> Unit)?,
    dense: Boolean = false,
) {
    TopAppBar(
        expandedHeight = if (dense) DENSE_HEIGHT else TopAppBarDefaults.TopAppBarExpandedHeight,
        title = { Text(title, modifier = Modifier.testTag(UiTags.TOP_BAR_TITLE)) },
        navigationIcon = {
            if (onMenuClick != null) {
                IconButton(onClick = onMenuClick, modifier = Modifier.testTag(UiTags.NAV_MENU)) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
            }
        },
        actions = {
            LocalHeaderActions.current.invoke(this)
        },
    )
}
