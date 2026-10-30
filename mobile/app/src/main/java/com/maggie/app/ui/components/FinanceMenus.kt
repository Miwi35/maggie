package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import com.maggie.app.ui.navigation.FinanceSection
import com.maggie.app.ui.navigation.LocalFinanceNav
import com.maggie.app.ui.navigation.financeSubviewState
import com.maggie.app.ui.navigation.subviewItems

/** A row of sub-views the screen draws itself, because it knows more than the section does (Revue's counts). */
class SubviewBar(
    val items: List<SubviewItem>,
    val selectedKey: String,
    val onSelect: (SubviewItem) -> Unit,
)

/**
 * A finance screen's top bar (MAG-537).
 *
 * A section's page wears the breadcrumb « Finance › Section ▾ » and, under it, one row of the section's sub-views
 * when it has some; [topBar] is not drawn there. A detail screen (a fiche, a list behind an icon) is not a page of any
 * section: it draws [topBar], its back arrow and its title, and no sections menu.
 *
 * @param actions the icons the page offers at the right of the breadcrumb.
 * @param subviewsTrailing what the row of sub-views carries at its far end (Budget's month selector).
 * @param subviews the row, when the screen supplies it; otherwise it is the section's own (a route per sub-view, or
 *   the state the graph holds for the section).
 */
@Composable
fun FinanceTopBar(
    route: String,
    actions: @Composable RowScope.() -> Unit = {},
    subviews: SubviewBar? = null,
    subviewsTrailing: (@Composable RowScope.() -> Unit)? = null,
    topBar: (@Composable () -> Unit)? = null,
) {
    val section = FinanceSection.pageOf(route)
    if (section == null) {
        topBar?.invoke()
        return
    }
    val nav = LocalFinanceNav.current

    Column {
        ModuleBreadcrumb(
            module = "Finance",
            sections = FinanceSection.entries.map { it.breadcrumb },
            currentKey = section.key,
            onSelectSection = { picked -> FinanceSection.byKey(picked.key)?.let { nav?.onSelectSection?.invoke(it) } },
            onOpenDrawer = nav?.onOpenDrawer,
            actions = actions,
        )
        when {
            subviews != null -> SubviewRow(subviews.items, subviews.selectedKey, subviews.onSelect, trailing = subviewsTrailing)
            section.subviews.isEmpty() -> Unit
            section.subviews.all { it.route != null } -> {
                val current = section.subviewOf(route)
                SubviewRow(
                    items = section.subviewItems(),
                    selectedKey = current?.key.orEmpty(),
                    onSelect = { item -> section.subview(item.key)?.let { nav?.onSelectSubview?.invoke(section, it) } },
                    trailing = subviewsTrailing,
                )
            }
            else -> {
                val (current, select) = financeSubviewState(section)
                SubviewRow(
                    items = section.subviewItems(),
                    selectedKey = current.key,
                    onSelect = { item -> section.subview(item.key)?.let(select) },
                    trailing = subviewsTrailing,
                )
            }
        }
    }
}
