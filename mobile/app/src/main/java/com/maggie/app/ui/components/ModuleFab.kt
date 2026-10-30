package com.maggie.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieTokens

/** The module whose hue a [ModuleFab] wears. */
enum class FabModule(val color: Color) {
    AGENDA(MaggieTokens.Module.agenda.second),
    GROCERY(MaggieTokens.Module.cuisine.second),
    COOKBOOK(MaggieTokens.Module.cuisine.second),
    FINANCE(MaggieTokens.Module.finance.second),
    TASKS(MaggieTokens.Module.taches.second),
}

/**
 * The « + » of a module's root screen (MAG-720): 56 dp, 18 dp radius, the module's colour, a dark plus.
 * Placed by the screen's `Scaffold`, which keeps it clear of Maggie's bar.
 */
@Composable
fun ModuleFab(
    module: FabModule,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = UiTags.MODULE_FAB,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.testTag(tag),
        shape = RoundedCornerShape(18.dp),
        containerColor = module.color,
        contentColor = Color(0xFF14121C),
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
    ) {
        Icon(Icons.Default.Add, contentDescription = contentDescription)
    }
}
