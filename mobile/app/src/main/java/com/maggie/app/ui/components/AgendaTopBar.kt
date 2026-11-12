package com.maggie.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.maggie.app.ui.navigation.AgendaSection
import com.maggie.app.ui.navigation.LocalAgendaNav

/** The header of a page of Agenda (MAG-815): « Agenda › Section ▾ ». */
@Composable
fun AgendaTopBar(
    section: AgendaSection,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val nav = LocalAgendaNav.current
    ModuleBreadcrumb(
        module = "Agenda",
        sections = AgendaSection.entries.map { it.breadcrumb },
        currentKey = section.key,
        onSelectSection = { picked -> AgendaSection.byKey(picked.key)?.let { nav?.onSelectSection?.invoke(it) } },
        onOpenDrawer = nav?.onOpenDrawer,
        modifier = modifier,
        actions = actions,
    )
}
