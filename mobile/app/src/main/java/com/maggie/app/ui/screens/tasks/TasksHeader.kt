package com.maggie.app.ui.screens.tasks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.maggie.app.data.model.TaskView
import com.maggie.app.ui.components.BreadcrumbSection
import com.maggie.app.ui.components.ModuleBreadcrumb
import com.maggie.app.ui.navigation.LocalModuleBar

/** What the header and the menu call the « Routines » entry, which is not a [TaskView]. */
internal const val ROUTINES_LABEL = "Routines"

/** The name of a view in the breadcrumb: a null view is « Routines ». */
internal fun viewNameOf(view: TaskView?): String = view?.label ?: ROUTINES_LABEL

/**
 * The header of the mockup, « Tâches › Aujourd'hui ▾ » (MAG-626), worn in the bar's place (MAG-670): the current
 * view's name opens the menu of [entries] (a null entry is « Routines »), each followed by its count when [countOf]
 * knows it. [current] is the one shown.
 */
@Composable
internal fun TasksHeader(
    current: TaskView?,
    entries: List<TaskView?>,
    countOf: (TaskView?) -> Int?,
    onPick: (TaskView?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bar = LocalModuleBar.current
    val sections = entries.map { BreadcrumbSection(key = it?.key ?: ROUTINES_TAB, label = viewNameOf(it), count = countOf(it)) }
    ModuleBreadcrumb(
        module = "Tâches",
        sections = sections,
        currentKey = current?.key ?: ROUTINES_TAB,
        onSelectSection = { picked -> onPick(entries.firstOrNull { (it?.key ?: ROUTINES_TAB) == picked.key }) },
        onOpenDrawer = bar.onOpenDrawer,
        modifier = modifier,
        actions = bar.actions,
    )
}
