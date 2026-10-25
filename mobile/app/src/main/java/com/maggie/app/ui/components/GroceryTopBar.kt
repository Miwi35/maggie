package com.maggie.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import com.maggie.app.ui.navigation.GrocerySection
import com.maggie.app.ui.navigation.LocalGroceryNav

/**
 * The top bar of a page of Courses (MAG-627): « Courses › Section ▾ », the three pages being siblings.
 *
 * @param actions the icons the page offers at the right of the breadcrumb.
 */
@Composable
fun GroceryTopBar(
    section: GrocerySection,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val nav = LocalGroceryNav.current
    ModuleBreadcrumb(
        module = "Courses",
        sections = GrocerySection.entries.map { it.breadcrumb },
        currentKey = section.key,
        onSelectSection = { picked -> GrocerySection.byKey(picked.key)?.let { nav?.onSelectSection?.invoke(it) } },
        onOpenDrawer = nav?.onOpenDrawer,
        actions = actions,
    )
}
