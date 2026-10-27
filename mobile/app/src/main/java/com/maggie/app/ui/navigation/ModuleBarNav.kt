package com.maggie.app.ui.navigation

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * What a module whose screen draws its own breadcrumb instead of the generic bar (Tâches, Cuisine; MAG-670) takes from
 * the frame: the burger — `null` when a rail shows the destinations — and the search and notifications that a
 * window with a rail keeps in its bar. On a phone [actions] is empty: they are entries of the drawer (MAG-624).
 */
class ModuleBar(
    val onOpenDrawer: (() -> Unit)?,
    val actions: @Composable RowScope.() -> Unit,
)

/** Outside the graph (a screen drawn alone): no burger, no action. */
val LocalModuleBar = compositionLocalOf { ModuleBar(onOpenDrawer = null, actions = {}) }
